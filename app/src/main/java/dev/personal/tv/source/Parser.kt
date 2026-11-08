package dev.personal.tv.source

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.JsonParser

/**
 * parse=1 / jx=1 的地址变成能播的直链。顺序和 TVBox 一致：
 * 1. 地址本身就是视频 → 直接播（有的插件给了 m3u8 还标 parse=1）
 * 2. VIP 来源（配置 flags 里的 qq/爱奇艺…）或 jx=1 → 先走 JSON 解析接口（并发，谁先回谁赢）
 * 3. 普通网页 → WebView 嗅探网页本身
 * 4. 还不行 → 网页解析接口逐个嗅探（同时最多 3 个 WebView）
 */
object Parser {
    private const val TAG = "Parser"

    class Result(val info: PlayInfo, val via: String)

    suspend fun resolve(
        ctx: Context,
        cfg: TvConfig,
        flag: String,
        p: PlayInfo,
        isVideo: ((String) -> Boolean)? = null,
        log: (String) -> Unit = {},
    ): Result? {
        if (!p.parse && !p.jx) return Result(p, "直链")
        if (Sniffer.isMedia(p.url)) return Result(PlayInfo(p.url, p.headers, parse = false, jx = false), "本身就是视频地址")

        val vip = p.jx || flag in cfg.flags
        val parses = cfg.parses.filter { it.accepts(flag) }
        val json = parses.filter { it.type == 1 }
        val web = parses.filter { it.type == 0 }

        // 普通网页先自己嗅；VIP 网页（腾讯、爱奇艺…）自己嗅不到，直接上解析接口
        if (!vip) {
            log("嗅探网页 ${p.url.take(60)}")
            Sniffer.sniff(ctx, p.url, p.headers, cfg.ads, isVideo = isVideo)?.let { return Result(it, "网页嗅探") }
        }
        // JSON 接口和网页接口同时跑，谁先拿到用谁
        return race(
            { jsonParse(json, p.url, log) },
            { webParse(ctx, web, p.url, cfg.ads, log) },
        )
    }

    /** 并发跑，返回第一个非空结果，其余取消 */
    private suspend fun <T : Any> race(vararg blocks: suspend () -> T?): T? = coroutineScope {
        val ch = Channel<T?>(blocks.size)
        val jobs = blocks.map { b -> launch { ch.send(runCatching { b() }.getOrNull()) } }
        var r: T? = null
        repeat(blocks.size) { if (r == null) r = ch.receive() }
        jobs.forEach { it.cancel() }
        r
    }

    /** JSON 解析接口：GET 接口+地址，返回 {url, header} 或 {data:{url}} */
    private suspend fun jsonParse(list: List<ParseCfg>, url: String, log: (String) -> Unit): Result? {
        if (list.isEmpty()) return null
        log("JSON 解析 · ${list.size} 个接口并发")
        val ch = Channel<Result?>(list.size)
        return withTimeoutOrNull(15_000) {
            coroutineScope {
                val jobs = list.map { pc ->
                    launch(Dispatchers.IO) {
                        ch.send(runCatching { callJson(pc, url) }.onFailure { Log.i(TAG, "json ${pc.name} ✗ ${it.message?.take(80)}") }.getOrNull())
                    }
                }
                var r: Result? = null
                repeat(list.size) { if (r == null) r = ch.receive() }
                jobs.forEach { it.cancel() }
                r
            }
        }
    }

    private fun callJson(pc: ParseCfg, url: String): Result? {
        val text = Net.get(pc.url + url, pc.header, timeoutSec = 12)
        val o = JsonParser.parseString(text).asJsonObject
        val data = o.get("data")?.obj() ?: o
        val u = data.str("url") ?: o.str("url") ?: return null
        if (!u.startsWith("http") || u == url) return null
        val h = LinkedHashMap<String, String>()
        h.putAll(data.map("header").ifEmpty { data.map("headers") })
        (data.str("user-agent") ?: data.str("User-Agent"))?.let { h["User-Agent"] = it }
        (data.str("referer") ?: data.str("Referer"))?.let { h["Referer"] = it }
        Log.i(TAG, "json ${pc.name} → $u")
        return Result(PlayInfo(u, h, parse = false, jx = false), "解析接口 ${pc.name}")
    }

    /** 网页解析接口：加载 接口+地址 再嗅探。同时最多开 3 个 WebView，谁先嗅到谁赢 */
    private suspend fun webParse(ctx: Context, list: List<ParseCfg>, url: String, ads: List<String>, log: (String) -> Unit): Result? {
        if (list.isEmpty()) return null
        log("网页解析 · ${list.size} 个接口")
        val gate = Semaphore(3)
        val ch = Channel<Result?>(list.size)
        return withTimeoutOrNull(40_000) {
            coroutineScope {
                val jobs = list.map { pc ->
                    launch {
                        gate.withPermit {
                            val r = Sniffer.sniff(ctx, pc.url + url, pc.header, ads, timeoutMs = 12_000)
                            if (r == null) Log.i(TAG, "web ${pc.name} ✗")
                            ch.send(r?.let { Result(it, "网页解析 ${pc.name}") })
                        }
                    }
                }
                var r: Result? = null
                repeat(list.size) { if (r == null) r = ch.receive() }
                jobs.forEach { it.cancel() }
                r
            }
        }
    }
}
