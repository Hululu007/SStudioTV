package dev.personal.tv.source

import android.content.Context
import android.util.Log
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 跨片源搜索：搜片、找线路时，除了当前片源的站点，把其它片源里的采集站（CMS，type 0/1）也一起搜。
 * 插件站（jar）不跨源：每个片源一个 jar，全加载太占内存、插件进程容易崩。
 *
 * 其它片源的站点表存在 files/cross-sites.json，后台一天刷新一次，搜索时不等网络。
 * 跨源来的站点 key 加 "x:" 前缀，不和当前片源的站点撞。
 */
object CrossSources {
    private const val TAG = "CrossSources"
    private const val TTL = 24 * 3600_000L

    /** 存盘用的精简字段（SiteCfg 里有构造时算的字段，不能直接让 Gson 反序列化） */
    private data class S(val key: String, val name: String, val type: Int, val api: String, val ext: String, val quick: Boolean, val timeout: Int, val header: Map<String, String>?)
    private data class Entry(val at: Long, val sites: List<S>)

    private var file: File? = null
    /** 片源地址 → 它的采集站 */
    private var cache: MutableMap<String, Entry> = mutableMapOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshing = false

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "cross-sites.json").also { file = it }
        cache = runCatching {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Entry>>() {}.type
            gson.fromJson<Map<String, Entry>>(f.readText(), type).toMutableMap()
        }.getOrNull() ?: mutableMapOf()
    }

    /** 地址比较时不管协议、末尾斜杠、大小写 */
    private fun norm(api: String) = api.trim().lowercase().substringAfter("://").trimEnd('/')

    /** [base]（当前片源）加上其它片源里不重复的采集站。开关关着、没有别的片源时原样返回 */
    fun merge(base: TvConfig): TvConfig {
        if (!Prefs.searchAll) return base
        val others = Sources.list.map { it.url }.filter { it != base.url }
        if (others.isEmpty()) return base
        refreshIfStale(others)
        val seen = base.sites.map { norm(it.api) }.toHashSet()
        val extra = ArrayList<SiteCfg>()
        synchronized(this) {
            for (u in others) for (s in cache[u]?.sites.orEmpty()) {
                if (!seen.add(norm(s.api))) continue
                extra += SiteCfg("x:${s.key}", s.name, s.type, s.api, s.ext, null, true, s.quick, s.timeout, s.header.orEmpty())
            }
        }
        return if (extra.isEmpty()) base else base.copy(sites = base.sites + extra)
    }

    /** 跨源来的站点数（设置页显示用） */
    fun count(base: TvConfig?) = if (base == null) 0 else merge(base).sites.size - base.sites.size

    fun isCross(s: SiteCfg) = s.key.startsWith("x:")

    private fun refreshIfStale(urls: List<String>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val stale = synchronized(this) { urls.filter { force || (cache[it]?.at ?: 0) < now - TTL } }
        if (stale.isEmpty() || refreshing) return
        refreshing = true
        scope.launch {
            try {
                val sem = Semaphore(3)
                stale.map { u ->
                    async {
                        sem.withPermit {
                            val c = withTimeoutOrNull(40_000) { runCatching { ConfigLoader.load(u) }.getOrNull() }
                            // 拉不下来：留着旧的，过一小时再试
                            if (c == null) {
                                synchronized(this@CrossSources) { cache[u]?.let { cache[u] = it.copy(at = now - TTL + 3600_000L) } }
                                return@withPermit
                            }
                            val sites = c.sites.filter {
                                (it.kind == SiteKind.CMS || it.kind == SiteKind.CMS_XML) && it.searchable && it.notVideo == null
                            }.map { S(it.key, it.name, it.type, it.api, it.ext, it.quickSearch, it.timeout, it.header) }
                            synchronized(this@CrossSources) { cache[u] = Entry(now, sites) }
                            Log.i(TAG, "${sites.size} 个采集站 ← $u")
                        }
                    }
                }.awaitAll()
                // 已经删掉的片源不留
                synchronized(this@CrossSources) {
                    val keep = Sources.list.map { it.url }.toSet()
                    cache.keys.retainAll(keep)
                    file?.writeText(gson.toJson(cache))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "refresh", e)
            } finally {
                refreshing = false
            }
        }
    }
}
