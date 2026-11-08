package dev.personal.tv.source

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/** 源调试页的状态：放在单例里，进出播放器不丢 */
object Lab {
    /** 已保存的片源（设置 → 片源里管理） */
    val presets get() = Sources.list.map { it.name to it.url }
    val keywords = listOf("首页", "繁花", "庆余年", "哪吒", "流浪地球", "狂飙")

    var config by mutableStateOf<TvConfig?>(null)
    var configName by mutableStateOf("")
    var site by mutableStateOf<SiteCfg?>(null)
    var results by mutableStateOf<List<Vod>>(emptyList())
    var vod by mutableStateOf<Vod?>(null)
    var flagIdx by mutableStateOf(0)
    var busy by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()
    /** 调试广播要求直接播放：源调试页看到它就跳播放器 */
    var playRequest by mutableStateOf<Pair<String, PlayInfo>?>(null)

    init {
        dev.personal.tv.spider.SpiderClient.onDeath = { why ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { note("✗ $why") }
        }
    }

    private fun note(s: String) {
        Log.i("Lab", s)
        log.add(0, s)
        if (log.size > 30) log.removeAt(log.lastIndex)
    }

    private suspend fun <T> step(label: String, timeoutMs: Long = 40_000, block: () -> T): T? {
        busy = label
        val t = System.currentTimeMillis()
        return try {
            // 插件的阻塞调用没法被取消：超时就不等它了（线程留在后台自己结束），界面和别的站点不受影响
            val job = CoroutineScope(Dispatchers.IO).async { block() }
            withTimeout(timeoutMs) { job.await() }
        } catch (e: Throwable) {
            val root = generateSequence(e) { it.cause }.last()
            note("✗ $label · ${root.javaClass.simpleName}: ${root.message?.take(120)}")
            Log.w("Lab", label, e)
            null
        } finally {
            busy = null
            Log.i("Lab", "$label ${System.currentTimeMillis() - t}ms")
        }
    }

    private fun ms(t: Long) = "%.1fs".format((System.currentTimeMillis() - t) / 1000f)

    suspend fun loadConfig(name: String, url: String) {
        val t = System.currentTimeMillis()
        val c = step("加载配置 $name") { ConfigLoader.load(url) } ?: return
        Sites.clear()
        config = c; configName = name; site = null; results = emptyList(); vod = null
        Sources.use(name, url, c)
        val byKind = c.sites.groupingBy { it.kind }.eachCount().entries.joinToString(" · ") { "${it.key.label} ${it.value}" }
        note("✓ $name · ${c.sites.size} 个站点（$byKind）· ${ms(t)}")
    }

    suspend fun query(ctx: Context, wd: String) {
        val c = config ?: return
        val s = site ?: return
        val t = System.currentTimeMillis()
        results = emptyList(); vod = null
        val list = step("${s.name} · $wd", timeoutMs = 60_000) {
            val api = Sites.get(ctx, c, s)
            if (wd == "首页") {
                val home = api.home()
                home.list.ifEmpty { home.classes.firstOrNull()?.let { api.category(it.id, 1).list }.orEmpty() }
            } else api.search(wd)
        } ?: return
        results = list
        note("✓ ${s.name} · $wd · ${list.size} 条 · ${ms(t)}")
    }

    suspend fun open(ctx: Context, v: Vod) {
        val c = config ?: return
        val s = site ?: return
        if (v.flags.isNotEmpty()) { vod = v; flagIdx = bestFlag(v); return }
        val t = System.currentTimeMillis()
        val d = step("详情 ${v.name}") { Sites.get(ctx, c, s).detail(v.id) } ?: return
        vod = d; flagIdx = bestFlag(d)
        note("✓ 详情 ${d.name} · ${d.flags.size} 个来源 · ${d.flags.sumOf { it.episodes.size }} 集 · ${ms(t)}")
    }

    /** 默认选能直接播的来源：CMS 常把「网页播放器」排在 m3u8 前面 */
    private val direct = Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE)
    private fun bestFlag(v: Vod) = v.flags.indexOfFirst { f -> f.episodes.firstOrNull()?.url?.let { direct.containsMatchIn(it) } == true }.coerceAtLeast(0)

    suspend fun resolve(ctx: Context, flag: String, ep: Episode): PlayInfo? {
        val c = config ?: return null
        val s = site ?: return null
        val t = System.currentTimeMillis()
        val api = step("站点") { Sites.get(ctx, c, s) } ?: return null
        val p = step("解析 ${ep.name}") { api.play(flag, ep.url) } ?: return null
        if (!p.parse && !p.jx) {
            note("✓ 直链 · ${ep.name} · ${ms(t)} · ${p.url.take(80)}")
            return p
        }
        note("⚠ 需要${if (p.jx) "解析接口" else "网页解析"} · ${ep.name} · ${p.url.take(80)}")
        val t2 = System.currentTimeMillis()
        busy = "网页解析 / 嗅探"
        val r = try {
            Parser.resolve(ctx, c, flag, p, api.videoCheck()) { note("… $it") }
        } catch (e: Throwable) {
            Log.w("Lab", "parse", e); null
        } finally { busy = null }
        if (r == null) {
            note("✗ 解析失败（所有接口和嗅探都没拿到视频地址）· ${ms(t2)}")
            return p
        }
        note("✓ ${r.via} · ${ms(t2)} · ${r.info.url.take(80)}")
        return r.info
    }

    /** 不开播放器，先探一下地址：状态码、类型、开头几个字节 */
    suspend fun probe(p: PlayInfo): String = step("探测", 20_000) {
        val req = okhttp3.Request.Builder().url(p.url).header("Range", "bytes=0-4095")
        p.headers.forEach { (k, v) -> req.header(k, v) }
        if (p.headers.keys.none { it.equals("User-Agent", true) }) req.header("User-Agent", "Mozilla/5.0 (Linux; Android 12) Chrome/120")
        Net.client.newCall(req.build()).execute().use { r ->
            val b = r.body?.byteStream()?.readNBytesCompat(4096) ?: ByteArray(0)
            val head = String(b, Charsets.UTF_8)
            val kind = when {
                head.startsWith("#EXTM3U") -> { p.format = "hls"; if ("#EXT-X-STREAM-INF" in head) "m3u8(master)" else "m3u8 ${Regex("#EXTINF").findAll(head).count()}+ 段" }
                "<MPD" in head -> { p.format = "dash"; "DASH ${Regex("<Representation").findAll(head).count()}+ 路" }
                b.size > 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> { p.format = "mp4"; "mp4" }
                b.size > 3 && b[0] == 'F'.code.toByte() && b[1] == 'L'.code.toByte() -> { p.format = "flv"; "flv" }
                head.trimStart().startsWith("<") -> { p.format = "html"; "HTML 页面" }
                else -> "未知 ${b.take(8).joinToString("") { "%02x".format(it) }}"
            }
            "HTTP ${r.code} · ${r.header("Content-Type") ?: "-"} · $kind"
        }
    }?.also { note("探测 · $it") } ?: "探测失败"

    /** 调试用：直接拿一个网页地址走解析 / 嗅探（--es purl 地址 --es flag qq） */
    suspend fun parseTest(ctx: Context, cfgName: String, flag: String, url: String, play: Boolean) {
        val cfgUrl = presets.firstOrNull { it.first == cfgName }?.second ?: cfgName
        if (configName != cfgName || config == null) loadConfig(cfgName, cfgUrl)
        val c = config ?: return
        note("▶ 解析测试 · $flag · ${url.take(60)}")
        val t = System.currentTimeMillis()
        busy = "网页解析 / 嗅探"
        val r = try { Parser.resolve(ctx, c, flag, PlayInfo(url, emptyMap(), parse = true, jx = false)) { note("… $it") } } finally { busy = null }
        if (r == null) { note("✗ 解析失败 · ${ms(t)}"); return }
        note("✓ ${r.via} · ${ms(t)} · ${r.info.url.take(80)}")
        probe(r.info)
        if (play) playRequest = "解析测试" to r.info
    }

    /**
     * 调试用：adb 一条命令跑完 加载配置 → 选站 → 搜索 → 详情 → 解析 → 探测。
     * adb shell am broadcast -a dev.personal.tv.LAB --es cfg <片源名或地址，省略=当前片源> --es site 暴風 --es wd 繁花
     */
    suspend fun auto(ctx: Context, cfgName: String, siteQ: String, wd: String, play: Boolean = false) {
        val url = presets.firstOrNull { it.first == cfgName }?.second ?: cfgName
        if (configName != cfgName || config == null) loadConfig(cfgName, url)
        val c = config ?: return
        val s = c.sites.firstOrNull { it.key == siteQ } ?: c.sites.firstOrNull { it.name.contains(siteQ) || it.api.endsWith(siteQ) }
            ?: run { note("✗ 找不到站点 $siteQ"); return }
        site = s
        note("▶ ${s.name}（${s.kind.label} ${s.api.take(30)}）")
        query(ctx, wd)
        val v = results.firstOrNull { it.name == wd } ?: results.firstOrNull() ?: run { note("✗ ${s.name} · 没有结果"); return }
        open(ctx, v)
        val d = vod ?: return
        val flag = d.flags.getOrNull(flagIdx) ?: run { note("✗ ${d.name} · 没有播放列表"); return }
        val ep = flag.episodes.first()
        val p = resolve(ctx, flag.name, ep) ?: return
        probe(p)
        if (play) playRequest = "${d.name} · ${ep.name}" to p
    }
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
    return buf.copyOf(off)
}
