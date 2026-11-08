package dev.personal.tv.source

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.personal.tv.data.gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 片源（影视仓配置）：可以存好几个，同一时间用一个。
 * App 不自带任何片源。自己编译时可以放一个 assets/presets.json（[{"name":"…","url":"…"}]，已在 .gitignore 里），
 * 第一次启动会把它导进来。
 */
object Sources {
    private const val TAG = "Sources"

    data class Entry(val name: String, val url: String)

    private lateinit var prefs: android.content.SharedPreferences
    val list = mutableStateListOf<Entry>()
    /** 当前片源的名字，空 = 还没有片源 */
    var name by mutableStateOf("")
        private set
    var url by mutableStateOf<String?>(null)
        private set
    private var cfg: TvConfig? = null
    private val lock = Mutex()

    val empty get() = url == null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("sources", Context.MODE_PRIVATE)
        val saved = prefs.getString("list", null)
        if (saved != null) {
            runCatching { gson.fromJson(saved, Array<Entry>::class.java).toList() }.getOrNull()?.let { list.addAll(it) }
        } else {
            // 老版本只存了一个 name/url
            val oldUrl = prefs.getString("url", null)
            if (oldUrl != null) list += Entry(prefs.getString("name", null) ?: oldUrl, oldUrl)
            runCatching {
                ctx.assets.open("presets.json").bufferedReader().use { gson.fromJson(it, Array<Entry>::class.java) }
            }.getOrNull()?.forEach { e -> if (list.none { it.url == e.url }) list += e }
            save()
        }
        val cur = prefs.getString("url", null)
        (list.firstOrNull { it.url == cur } ?: list.firstOrNull())?.let { name = it.name; url = it.url }
    }

    suspend fun config(): TvConfig = lock.withLock {
        val u = url ?: error("还没有片源：到「设置 → 片源」里添加一个影视仓配置地址")
        cfg?.takeIf { it.url == u } ?: withContext(Dispatchers.IO) { ConfigLoader.load(u) }.also { cfg = it }
    }

    fun select(e: Entry) {
        if (url == e.url) return
        Lines.clear()
        Sites.clear()
        name = e.name; url = e.url; cfg = null
        prefs.edit().putString("url", e.url).putString("name", e.name).apply()
    }

    fun remove(e: Entry) {
        list.remove(e)
        save()
        if (url == e.url) {
            list.firstOrNull()?.let { select(it) } ?: run {
                name = ""; url = null; cfg = null; Lines.clear()
                prefs.edit().remove("url").remove("name").apply()
            }
        }
    }

    /**
     * 加一个地址：先下载验证。多仓地址（{"urls":[…]}）会展开成好几个。
     * 返回加进来的条目；失败抛异常（信息给界面看）。
     */
    suspend fun add(name: String?, rawUrl: String): List<Entry> {
        val u = rawUrl.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val added = withContext(Dispatchers.IO) {
            val text = ConfigLoader.decode(Net.get(u, timeoutSec = 30))
            val depots = ConfigLoader.depots(text)
            if (depots.isNotEmpty()) depots.map { (n, d) -> Entry(n, ConfigLoader.resolve(u, d)) }
            else {
                val c = try { ConfigLoader.parse(u, text) } catch (e: Exception) {
                    Log.w(TAG, "parse $u", e)
                    throw IllegalArgumentException(
                        if (text.trimStart().startsWith("<")) "这是个网页，不是影视仓 / TVBox 配置地址" else "不是影视仓 / TVBox 配置（解析不了）",
                    )
                }
                require(c.sites.isNotEmpty() || c.lives.isNotEmpty()) { "这个地址里没有站点也没有直播" }
                listOf(Entry(name?.takeIf { it.isNotBlank() } ?: defaultName(u), u))
            }
        }
        val fresh = added.filter { e -> list.none { it.url == e.url } }
        list.addAll(fresh)
        save()
        Log.i(TAG, "added ${fresh.size} from $u")
        if (url == null) (fresh.firstOrNull() ?: added.firstOrNull())?.let { select(it) }
        return added
    }

    /** 源调试页加载成功的配置直接拿来用，也存进列表 */
    fun use(name: String, url: String, c: TvConfig) {
        if (list.none { it.url == url }) { list += Entry(name, url); save() }
        select(Entry(name, url))
        cfg = c
    }

    private fun defaultName(u: String) = runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull() ?: u.take(24)

    private fun save() {
        if (::prefs.isInitialized) prefs.edit().putString("list", gson.toJson(list.toList())).apply()
    }
}
