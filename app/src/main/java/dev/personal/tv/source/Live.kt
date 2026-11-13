package dev.personal.tv.source

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个频道：同名的多个地址算一个频道的多条信号 */
data class Channel(val name: String, val urls: List<String>, val logo: String?)

data class ChannelGroup(val name: String, val channels: List<Channel>)

/**
 * 直播：配置里的 lives。两种格式：
 * txt：「央视频道,#genre#」开一个分组，下面「CCTV1,http://…」，一个频道多个地址用 # 隔开或者重复写几行
 * m3u：#EXTINF:-1 tvg-logo="…" group-title="央视",CCTV1 + 下一行地址
 */
object Live {
    private const val TAG = "Live"
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var app: Context

    /** 当前片源的直播源（可能有好几个），选的是哪个 */
    var sources by mutableStateOf<List<LiveCfg>>(emptyList())
        private set
    var sourceIdx by mutableIntStateOf(0)
        private set
    var groups by mutableStateOf<List<ChannelGroup>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loadedFor: String? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)
    }

    private fun local(url: String) = runCatching { java.net.URI(url).host }.getOrNull()
        ?.let { it == "127.0.0.1" || it.equals("localhost", true) } == true

    /**
     * 直播源是 127.0.0.1:xxxx 的，是插件 jar 加载后自己起的本地服务；冷启动时 jar 还没加载，连不上。
     * 先唤醒「设置中心」那个插件站点，还不行再唤醒几个别的插件站点，每次唤醒后等它起服务。
     */
    private suspend fun fetchLocal(url: String, h: Map<String, String>): String {
        val first = runCatching { Net.get(url, h, 8) }
        first.getOrNull()?.let { return it }
        if (!::app.isInitialized) throw first.exceptionOrNull()!!
        val config = Sources.config()
        val jars = config.sites.filter { it.kind == SiteKind.JAR }
            .sortedByDescending { it.api.endsWith("Config") || "设置" in it.name || "配置" in it.name }
        var last = first.exceptionOrNull()!!
        for (batch in listOf(jars.take(1), jars.drop(1).take(6))) {
            if (batch.isEmpty()) continue
            Log.i(TAG, "本地直播源连不上，唤醒插件 ${batch.joinToString { it.name }}")
            batch.forEach { s -> runCatching { (Sites.get(app, config, s) as? JarSite)?.wake() } }
            repeat(4) {
                kotlinx.coroutines.delay(800)
                runCatching { Net.get(url, h, 8) }.onSuccess { return it }.onFailure { last = it }
            }
        }
        throw last
    }

    /** 上次看的频道名，下次直接从它开始 */
    var lastChannel: String?
        get() = if (::prefs.isInitialized) prefs.getString("last:${loadedFor}", null) else null
        set(v) { if (::prefs.isInitialized) prefs.edit().putString("last:${loadedFor}", v).apply() }

    /** 跟着当前片源加载；force = 重新下载 */
    suspend fun load(force: Boolean = false) {
        val c = try { Sources.config() } catch (e: Throwable) {
            sources = emptyList(); groups = emptyList(); error = e.message; return
        }
        if (c.lives.map { it.url } != sources.map { it.url }) {
            sources = c.lives
            sourceIdx = (if (::prefs.isInitialized) prefs.getInt("src:${c.url}", 0) else 0).coerceIn(0, (c.lives.size - 1).coerceAtLeast(0))
            loadedFor = null
        }
        val src = sources.getOrNull(sourceIdx) ?: run { groups = emptyList(); error = null; return }
        if (!force && loadedFor == src.url && groups.isNotEmpty()) return
        loading = true
        error = null
        try {
            val text = withContext(Dispatchers.IO) {
                val h = src.header + (src.ua?.let { mapOf("User-Agent" to it) } ?: emptyMap())
                if (local(src.url)) fetchLocal(src.url, h) else Net.get(src.url, h, 20)
            }
            groups = withContext(Dispatchers.Default) { parse(text) }
            loadedFor = src.url
            if (groups.isEmpty()) error = "「${src.name}」里没有频道"
            Log.i(TAG, "${src.name}: ${groups.size} 组 ${groups.sumOf { it.channels.size }} 个频道")
        } catch (e: Throwable) {
            Log.w(TAG, "load ${src.url}", e)
            groups = emptyList()
            error = "直播源下载失败：${e.message?.take(60)}"
        } finally { loading = false }
    }

    suspend fun selectSource(i: Int) {
        if (i == sourceIdx) return
        sourceIdx = i
        Sources.url?.let { u -> if (::prefs.isInitialized) prefs.edit().putInt("src:$u", i).apply() }
        load(force = true)
    }

    /** 请求头：直播源配置里写的 UA / header */
    fun headers(): Map<String, String> {
        val s = sources.getOrNull(sourceIdx) ?: return emptyMap()
        return s.header + (s.ua?.let { mapOf("User-Agent" to it) } ?: emptyMap())
    }

    fun parse(text: String): List<ChannelGroup> =
        if (text.trimStart().startsWith("#EXTM3U") || "#EXTINF" in text) m3u(text) else txt(text)

    private fun txt(text: String): List<ChannelGroup> {
        val out = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        var group = "未分组"
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.forEach { line ->
            val i = line.indexOf(',')
            if (i < 0) return@forEach
            val name = line.substring(0, i).trim()
            val rest = line.substring(i + 1).trim()
            if (rest.startsWith("#genre#")) { group = name; return@forEach }
            val urls = rest.split('#').map { it.trim() }.filter { it.contains("://") }
            if (name.isEmpty() || urls.isEmpty()) return@forEach
            out.getOrPut(group) { LinkedHashMap() }.getOrPut(name) { mutableListOf() }.addAll(urls)
        }
        return out.map { (g, chs) -> ChannelGroup(g, chs.map { (n, u) -> Channel(n, u.distinct(), null) }) }.filter { it.channels.isNotEmpty() }
    }

    private fun m3u(text: String): List<ChannelGroup> {
        val out = LinkedHashMap<String, LinkedHashMap<String, Pair<MutableList<String>, String?>>>()
        var name: String? = null
        var group = "未分组"
        var logo: String? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(',').trim()
                    group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.ifBlank { null } ?: "未分组"
                    logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.ifBlank { null }
                }
                line.isEmpty() || line.startsWith("#") -> {}
                name != null -> {
                    val e = out.getOrPut(group) { LinkedHashMap() }.getOrPut(name!!) { mutableListOf<String>() to logo }
                    e.first += line
                    name = null
                }
            }
        }
        return out.map { (g, chs) -> ChannelGroup(g, chs.map { (n, p) -> Channel(n, p.first.distinct(), p.second) }) }.filter { it.channels.isNotEmpty() }
    }
}
