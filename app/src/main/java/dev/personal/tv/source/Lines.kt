package dev.personal.tv.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一条线路 = 一个站点里的一个播放来源（flag），带完整的分集列表 */
data class PlayLine(
    val siteKey: String,
    val siteName: String,
    val flag: String,
    val vodName: String,
    val remarks: String,
    val episodes: List<Episode>,
) {
    val key get() = "$siteKey|$flag"
    /** 分集地址本身就是 m3u8/mp4，不用再解析，最稳 */
    val direct get() = episodes.firstOrNull()?.url?.let { DIRECT.containsMatchIn(it) } == true

    companion object {
        val DIRECT = Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE)
    }
}

/** 一部片的线路搜索状态，界面直接读 */
class LineSet(val titleId: String) {
    val lines = mutableStateListOf<PlayLine>()
    var searched by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val at = System.currentTimeMillis()
    /** 播放失败过的线路：key → 原因。换线时跳过 */
    val failed = mutableStateMapOf<String, String>()
    /** 已经搜完的站点 key（主线程读写） */
    val doneSites = HashSet<String>()
    /** 因为网络错误（连不上、超时）没搜成的站点数 */
    var netErrors = 0
}

/**
 * 多源合并：拿豆瓣片名去配置里所有能搜索的站点搜，名字 + 季数 + 年份对得上的，每个播放来源算一条线路。
 * 同时最多 8 个站点，单站 15 秒，整体 45 秒；搜到一条显示一条，不等全部搜完。
 */
object Lines {
    private const val TAG = "Lines"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sets = mutableStateMapOf<String, LineSet>()

    fun get(id: String): LineSet? = sets[id]

    fun count(id: String): Int {
        val s = sets[id] ?: return -1
        return if (s.lines.isEmpty() && !s.done) -1 else s.lines.size
    }

    fun clear() = sets.clear()

    /** 有没有联网。不看「已验证」：国内连不上谷歌的检测地址，验证永远不过 */
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            val n = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } catch (e: Throwable) { true }
    }

    /** 播放正在解析时 > 0：线路搜索先不开新的站点，把插件进程让给播放（实测并发搜索时解析从 4 秒拖到 36 秒） */
    private val playing = MutableStateFlow(0)

    suspend fun <T> yieldTo(block: suspend () -> T): T {
        playing.value++
        try { return block() } finally { playing.value-- }
    }

    /** 开始（或复用）一部片的线路搜索。搜完 30 分钟内不重搜 */
    fun find(ctx: Context, t: Title, force: Boolean = false): LineSet {
        sets[t.id]?.let { s ->
            val age = System.currentTimeMillis() - s.at
            // 没搜到的 5 分钟后可以再搜（可能是网络抖了），配置加载失败的马上重试
            val keep = !s.done || (s.error == null && age < if (s.lines.isEmpty()) 5 * 60_000 else 30 * 60_000)
            if (!force && keep) return s
        }
        val set = LineSet(t.id)
        sets[t.id] = set
        val app = ctx.applicationContext
        scope.launch { search(app, t, set) }
        return set
    }

    /**
     * 播放时的尝试顺序：手动选过的那条 → 设置里「优先」的站点（按排名）→ 上次能播的 → 直链 → 普通网页 → VIP 网页（要解析接口）。
     * 播放失败过的排到后面。
     */
    fun ordered(set: LineSet, prefer: String?, vipFlags: List<String>, pinned: Boolean = false): List<PlayLine> =
        set.lines.sortedWith(
            compareByDescending<PlayLine> { pinned && it.key == prefer }
                .thenBy { it.key in set.failed }
                .thenBy { Prefs.rank(it.siteKey) ?: Int.MAX_VALUE }
                .thenByDescending { it.key == prefer }
                .thenBy { if (it.direct) 0 else if (it.flag in vipFlags) 2 else 1 }
                .thenByDescending { it.episodes.size },
        )

    /**
     * 选集按这条线路显示：正片最全的（综艺有的站有加更、上下期，有的站没有；夹了上百条花絮、纯享的不算多），一样多时上次播的那条优先。
     * 播放时按名字到各条线路里找同一集，所以显示哪条都能播。
     */
    fun ref(set: LineSet?, lastKey: String?): PlayLine? {
        if (set == null) return null
        return ordered(set, lastKey, emptyList(), pinned = true).maxByOrNull { Eps.mainCount(it.episodes) }
    }

    private suspend fun search(ctx: Context, t: Title, set: LineSet) {
        val t0 = System.currentTimeMillis()
        val cfg = try { Sources.config() } catch (e: Throwable) {
            Log.w(TAG, "config", e)
            set.error = "片源配置加载失败：${e.message?.take(60)}"
            set.done = true
            return
        }
        // 设置里标了「不用」的站点不搜
        // 直播、音乐、听书、网盘搜索这类不是影视的站点也不搜（手动设成「优先」的除外）
        val sites = cfg.sites.filter {
            it.searchable && it.kind.supported && it.key !in Prefs.blocked && (it.notVideo == null || Prefs.rank(it.key) != null)
        }
        set.total = sites.size
        val q = Match.query(t.name)
        Log.i(TAG, "${t.name} → 「$q」· ${sites.size} 个站点")
        val sem = Semaphore(8)
        withTimeoutOrNull(45_000) {
            coroutineScope {
                sites.forEach { s ->
                    launch {
                        sem.withPermit {
                            playing.first { it == 0 }
                            one(ctx, cfg, s, t, q, set)
                        }
                        set.doneSites += s.key
                        set.searched++
                    }
                }
            }
        }
        // 一条都没搜到、又是网络的问题：说网络不通，别说「站点里都没有」；有 error 下次进来马上重搜
        if (set.lines.isEmpty() && (!online(ctx) || set.netErrors > 0 && set.netErrors * 2 >= set.searched)) {
            set.error = "网络好像不通，没搜到线路。网络好了再试"
        }
        set.done = true
        Log.i(TAG, "${t.name} · ${set.lines.size} 条线路 · ${set.searched}/${set.total} 站 · ${System.currentTimeMillis() - t0}ms")
        if (t.isEpisodic && set.lines.isNotEmpty()) {
            // 豆瓣还没更新集数时按线路算；取第二多的，免得一个站把花絮也算进去
            // 综艺按名字分期（上下期、加更），条数不等于集数，不拿来算
            val counts = set.lines.filter { Eps.numeric(it.episodes, t.name, t.isVariety) }.map { l -> l.episodes.count { Eps.isNumber(it.name, t.name) } }.sortedDescending()
            val eps = if (counts.size >= 2) counts[1] else counts.firstOrNull() ?: 0
            if (eps > 0 && (t.totalEps <= 1 || eps <= t.totalEps)) Catalog.bumpAired(t.id, eps)
        }
    }

    private suspend fun one(ctx: Context, cfg: TvConfig, s: SiteCfg, t: Title, q: String, set: LineSet) {
        // 插件的阻塞调用取消不了：超时就不等了，线程自己跑完
        val job = CoroutineScope(Dispatchers.IO).async {
            val api = Sites.get(ctx, cfg, s)
            val hits = api.search(q).filter { Match.same(t.name, t.year, !t.isEpisodic, it.name, it.year) }.take(2)
            hits.mapNotNull { v -> if (v.flags.isNotEmpty()) v else runCatching { api.detail(v.id) }.getOrNull() }
        }
        val vods = try {
            withTimeoutOrNull(15_000) { job.await() }
        } catch (e: Throwable) {
            Log.d(TAG, "${s.name}: ${e.message}")
            if (generateSequence(e) { it.cause }.take(5).any { it is java.io.IOException }) set.netErrors++
            null
        } ?: return
        for (v in vods) {
            for (f in v.flags) {
                if (f.episodes.isEmpty()) continue
                // 电影只要一集的来源；剧集的来源只有一集多半是预告
                if (!t.isEpisodic && f.episodes.size > 3) continue
                if (t.isEpisodic && f.episodes.size == 1 && (t.totalEps > 1 || t.airedEps > 1)) continue
                val line = PlayLine(s.key, s.name, f.name, v.name, v.remarks, Eps.ascending(Eps.trimCommon(f.episodes), t.name))
                if (set.lines.none { it.key == line.key }) set.lines.add(line)
            }
        }
    }
}
