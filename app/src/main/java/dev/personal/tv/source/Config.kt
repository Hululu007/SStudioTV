package dev.personal.tv.source

import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 站点类型。type 3 再按 api 细分：csp_ 开头走 jar，.js 结尾走 drpy（还没接） */
enum class SiteKind(val label: String, val supported: Boolean) {
    CMS_XML("CMS·XML", true),
    CMS("CMS", true),
    JAR("JAR", true),
    JS("JS", false),
    OTHER("?", false),
}

data class SiteCfg(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    /** 原样传给插件的 ext：字符串原样、对象/数组转成 JSON 文本 */
    val ext: String,
    /** 站点自带的 jar（少见），为空用全局 spider */
    val jar: String?,
    val searchable: Boolean,
    val quickSearch: Boolean,
    val timeout: Int,
    val header: Map<String, String>,
) {
    val kind: SiteKind = when {
        type == 0 -> SiteKind.CMS_XML
        type == 1 -> SiteKind.CMS
        type == 3 && api.startsWith("csp_") -> SiteKind.JAR
        type == 3 && api.endsWith(".js") -> SiteKind.JS
        else -> SiteKind.OTHER
    }

    /**
     * 不是影视站的原因（直播、音乐、听书、体育、儿歌、网盘搜索、配置页…），是影视站返回 null。
     * 大配置里这种站点能占一半：拿片名去搜只会白等、搜出不相干的东西，线路搜索不搜它们。
     * 只看名字和插件类名，猜错了可以在「设置 → 站点」里把它设成「优先」强制搜。
     */
    val notVideo: String? = run {
        val n = name.replace(Regex("[^\\p{L}\\p{N}]"), "")
        val a = api.removePrefix("csp_")
        NOT_VIDEO.firstOrNull { (_, words, apis) -> words.any { it in n } || apis.any { a.startsWith(it) } }?.first
    }

    private companion object {
        /** 类别 → 名字里的词、插件类名开头 */
        val NOT_VIDEO = listOf(
            Triple("直播", listOf("直播", "电台"), listOf("Live")),
            Triple("音乐", listOf("音乐", "舞曲", "KTV", "歌曲", "儿歌", "戏曲"), listOf("Music", "Children")),
            Triple("听书", listOf("听书", "有声", "小说", "漫画"), listOf("Book")),
            Triple("体育", listOf("体育", "球赛"), listOf("Sport")),
            Triple("课程", listOf("课堂", "教育", "教学", "学习"), emptyList()),
            Triple("网盘搜索", listOf("盘搜", "网盘搜", "云盘搜"), listOf("So")),
            Triple("工具", listOf("配置", "设置", "推送", "我的网盘", "本地"), listOf("Push", "PanConfig", "FishConfig", "MyPan", "Config", "Local")),
        )
    }
}

/** 解析接口。type 0 网页（加载后嗅探），1 JSON（直接返回地址），2/3 是聚合占位（url 不是 http），flags 为空表示不限来源 */
data class ParseCfg(val name: String, val type: Int, val url: String, val header: Map<String, String>, val flags: List<String>) {
    fun accepts(flag: String) = url.startsWith("http") && (flags.isEmpty() || flag in flags)
}

data class TvConfig(
    val url: String,
    val spider: String?,
    val spiderMd5: String?,
    val sites: List<SiteCfg>,
    val parses: List<ParseCfg>,
    val flags: List<String>,
    val ads: List<String>,
    val lives: List<LiveCfg> = emptyList(),
)

/** 直播源：url 指向 txt（「分组,#genre#」+「频道,地址」）或 m3u */
data class LiveCfg(val name: String, val url: String, val ua: String?, val header: Map<String, String>)

object ConfigLoader {

    fun load(url: String): TvConfig {
        val raw = Net.get(url, timeoutSec = 30)
        val text = decode(raw)
        return parse(url, text)
    }

    // ---------------------------------------------------------------- 解码

    /**
     * 常见的三种外壳：
     * 1. 明文 JSON（可能带 // 注释）
     * 2. 十六进制，以 "2423"（即 "$#"）开头：AES/CBC，key 在 $#…#$ 之间，iv 是末尾 13 位
     * 3. 图片后面藏 base64：找到 xxxxxxxx** 之后的部分
     */
    fun decode(raw: String): String {
        val s = raw.trim().removePrefix("﻿")
        if (s.startsWith("{") || s.startsWith("[")) return s
        if (s.startsWith("2423")) return decode(aesCbc(s))
        val m = Regex("[A-Za-z0-9]{8}\\*\\*").find(s)
        if (m != null) {
            val b64 = s.substring(m.range.last + 1)
            return decode(String(Base64.decode(b64, Base64.DEFAULT)))
        }
        // 纯 base64
        return runCatching { String(Base64.decode(s, Base64.DEFAULT)) }.getOrNull()
            ?.takeIf { it.trimStart().startsWith("{") } ?: s
    }

    private fun aesCbc(hex: String): String {
        val txt = String(hex2bytes(hex), Charsets.ISO_8859_1).lowercase()
        val key = pad(txt.substring(txt.indexOf("$#") + 2, txt.indexOf("#$")))
        val iv = pad(txt.substring(txt.length - 13))
        val data = hex.substring(hex.indexOf("2324") + 4, hex.length - 26)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return String(c.doFinal(hex2bytes(data)), Charsets.UTF_8)
    }

    private fun pad(s: String) = (s + "0".repeat(16)).take(16)

    private fun hex2bytes(h: String) = ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // ---------------------------------------------------------------- 解析

    fun parse(url: String, text: String): TvConfig {
        val reader = JsonReader(StringReader(text)).apply { isLenient = true }
        val o = JsonParser.parseReader(reader).asJsonObject
        val (spider, md5) = splitMd5(o.str("spider"))
        return TvConfig(
            url = url,
            spider = spider?.let { resolve(url, it) },
            spiderMd5 = md5,
            sites = o.arr("sites").mapNotNull { it.obj() }.map { s ->
                val ext = s.get("ext")
                SiteCfg(
                    key = s.str("key") ?: s.str("name").orEmpty(),
                    name = s.str("name").orEmpty(),
                    type = s.int("type", 0),
                    api = s.str("api").orEmpty(),
                    ext = when {
                        ext == null || ext.isJsonNull -> ""
                        ext.isJsonPrimitive -> ext.asString.let { if (it.startsWith("./") || it.startsWith("../")) resolve(url, it) else it }
                        else -> ext.toString()
                    },
                    jar = s.str("jar")?.let { resolve(url, splitMd5(it).first!!) },
                    searchable = s.int("searchable", 1) != 0,
                    quickSearch = s.int("quickSearch", 1) != 0,
                    timeout = s.int("timeout", 15),
                    header = s.map("header"),
                )
            },
            parses = o.arr("parses").mapNotNull { it.obj() }.map {
                val ext = it.get("ext")?.obj()
                ParseCfg(
                    name = it.str("name").orEmpty(),
                    type = it.int("type", 0),
                    url = it.str("url").orEmpty(),
                    header = ext?.map("header").orEmpty().ifEmpty { it.map("header") },
                    flags = ext?.arr("flag").orEmpty().mapNotNull { f -> runCatching { f.asString }.getOrNull() },
                )
            },
            flags = o.arr("flags").mapNotNull { runCatching { it.asString }.getOrNull() },
            ads = o.arr("ads").mapNotNull { runCatching { it.asString }.getOrNull() },
            lives = lives(url, o),
        )
    }

    /**
     * 两种写法：
     * 新：[{name, url, ua, header}]
     * 旧：[{group: "redirect", channels: [{name, urls: ["proxy://do=live&type=txt&ext=<base64 地址>"]}]}]
     */
    private fun lives(base: String, o: JsonObject): List<LiveCfg> = o.arr("lives").mapNotNull { it.obj() }.flatMap { l ->
        val url = l.str("url")
        if (url != null) {
            listOf(LiveCfg(l.str("name") ?: "直播", resolve(base, url), l.str("ua"), l.map("header")))
        } else {
            l.arr("channels").mapNotNull { it.obj() }.mapNotNull { c ->
                val u = c.arr("urls").firstOrNull()?.let { runCatching { it.asString }.getOrNull() } ?: return@mapNotNull null
                val real = if (u.startsWith("proxy://")) {
                    val ext = Regex("[?&]ext=([^&]+)").find(u.replaceFirst("proxy://", "proxy://x?"))?.groupValues?.get(1) ?: return@mapNotNull null
                    runCatching { String(Base64.decode(ext, Base64.DEFAULT or Base64.URL_SAFE)) }.getOrNull()
                        ?.takeIf { it.startsWith("http") } ?: ext.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                } else u
                LiveCfg(c.str("name") ?: "直播", resolve(base, real), null, emptyMap())
            }
        }
    }.filter { it.url.startsWith("http") }

    /** 多仓配置 {"urls": [{name, url}]}：一个地址里列了好几个配置 */
    fun depots(text: String): List<Pair<String, String>> = runCatching {
        val o = JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }).asJsonObject
        o.arr("urls").mapNotNull { it.obj() }.mapNotNull { u -> u.str("url")?.let { (u.str("name") ?: it) to it } }
    }.getOrDefault(emptyList())

    /** "./jar/spider.jar;md5;abc" → ("./jar/spider.jar", "abc") */
    private fun splitMd5(s: String?): Pair<String?, String?> {
        if (s.isNullOrBlank()) return null to null
        val parts = s.split(";md5;")
        return parts[0].trim() to parts.getOrNull(1)?.trim()
    }

    /** 相对路径按配置地址解析；img+ 之类的前缀先剥掉 */
    fun resolve(base: String, path: String): String {
        val p = path.removePrefix("img+")
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        return runCatching { URI(base.substringBefore('?')).resolve(p).toString() }.getOrDefault(p)
    }
}

// ---------------------------------------------------------------- Gson 小工具

internal fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
internal fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.int(k: String, def: Int): Int = runCatching { get(k)?.asInt }.getOrNull() ?: def
internal fun JsonObject.arr(k: String): List<JsonElement> = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()
internal fun JsonObject.map(k: String): Map<String, String> {
    val e = get(k) ?: return emptyMap()
    val o = when {
        e.isJsonObject -> e.asJsonObject
        e.isJsonPrimitive -> runCatching { JsonParser.parseString(e.asString).asJsonObject }.getOrNull() ?: return emptyMap()
        else -> return emptyMap()
    }
    return o.entrySet().associate { (kk, v) -> kk to (if (v.isJsonPrimitive) v.asString else v.toString()) }
}
