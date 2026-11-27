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
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /** 站点里这部片的详情 id（冷启动续播用）；本地线路等没有 = 空 */
    val vodId: String = "",
) {
    val key get() = "$siteKey|$flag"
    /** 分集地址本身就是 m3u8/mp4，不用再解析，最稳 */
    val direct get() = episodes.firstOrNull()?.url?.let { DIRECT.containsMatchIn(it) } == true

    companion object {
        val DIRECT = Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE)
    }
}

/** 一部片的线路搜索状态，界面直接读 */
class LineSet(val titleId: String, val title: String = "") {
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
    /** 按 [Eps.scoreInfo] 排好的线路和分数（后台算，搜到新线路、有线路播放失败时重算）；刚搜到的线路可能还没排进来 */
    var ranking by mutableStateOf<List<Pair<PlayLine, Eps.Score>>>(emptyList())
        internal set
    internal var rankJob: kotlinx.coroutines.Job? = null
    internal var rankDirty = false
    /** 算 [ranking] 时失败过几条线路（失败的排后面，同分时影响先后） */
    internal var rankFailed = 0
    /** 每条线路拆好的分集名字（打分用），线路 key → */
    internal val infos = HashMap<String, Eps.LineInfo>()
    /** 站点给了年份 / 地区、而且跟豆瓣对得上的线路（还有从站点点开的）。没资料的可能是同名的另一部，排在这些后面 */
    internal val sure = HashSet<String>()
}

/** 不可中断的阻塞插件/网络调用共用的超时后台作用域：超时后取消作业，即使延迟抛异常也不会带崩主进程 */
internal val bgIoScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w("BgIO", "uncaught", e) },
)

/**
 * 多源合并：拿豆瓣片名去配置里所有能搜索的站点搜，名字 + 季数 + 年份对得上的，每个播放来源算一条线路。
 * 同时最多 8 个站点，单站 15 秒，整体 45 秒；搜到一条显示一条，不等全部搜完。
 */
object Lines {
    private const val TAG = "Lines"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.w(TAG, "uncaught", e) },
    )
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

    /** 正在解析播放（用户自己点的线路）：插件这时候的提示要给用户看（见 [PluginNotes]） */
    val resolving get() = playing.value > 0

    suspend fun <T> yieldTo(block: suspend () -> T): T {
        playing.value++
        try { return block() } finally { playing.value-- }
    }

    /** 把已知能用的线路（冷启动续播取到的、界面选定的）放进线路集；已经有同 key 的不动。返回集里的那一条 */
    fun adopt(set: LineSet, l: PlayLine): PlayLine {
        set.lines.firstOrNull { it.key == l.key }?.let { return it }
        set.sure += l.key
        set.lines.add(l)
        rerank(set)
        return l
    }

    /** 开始（或复用）一部片的线路搜索。搜完 30 分钟内不重搜 */
    fun find(ctx: Context, t: Title, force: Boolean = false): LineSet {
        sets[t.id]?.let { s ->
            val age = System.currentTimeMillis() - s.at
            // 没搜到的 5 分钟后可以再搜（可能是网络抖了），配置加载失败的马上重试
            val keep = !s.done || (s.error == null && age < if (s.lines.isEmpty()) 5 * 60_000 else 30 * 60_000)
            if (!force && keep) return s
        }
        val set = LineSet(t.id, t.name)
        sets[t.id] = set
        val app = ctx.applicationContext
        scope.launch { search(app, t, set) }
        return set
    }

    /**
     * 播放时的尝试顺序：手动选过的、继续观看时上次看的那条 → 设置里「优先」的站点（按排名）→ 上次能播的 → 直链 → 普通网页 → VIP 网页（要解析接口）。
     * 播放失败过的排到后面。
     */
    fun ordered(set: LineSet, prefer: String?, vipFlags: List<String>, pinned: Boolean = false): List<PlayLine> {
        val origin = SourceSearch.origin(set.titleId)?.siteKey
        return set.lines.sortedWith(
            compareByDescending<PlayLine> { pinned && it.key == prefer && it.key !in set.failed }
                .thenBy { it.key in set.failed }
                // 网盘线路（夸克原画、百度…）没登录那家网盘时播不了，插件还常常卡到超时：排到能直接播的后面
                .thenBy { panLocked(it.flag) }
                // 从站点里点开的：那个站点自己的线路最可靠（按片名配到的别的站可能是同名的另一部）
                .thenByDescending { it.siteKey == origin }
                .thenBy { Prefs.rank(it.siteKey) ?: Int.MAX_VALUE }
                // 站点资料跟豆瓣对得上的先试；没资料的可能是同名的另一部
                .thenByDescending { it.key in set.sure }
                .thenByDescending { it.key == prefer }
                .thenBy { if (it.direct) 0 else if (it.flag in vipFlags) 2 else 1 }
                .thenByDescending { it.episodes.size },
        )
    }

    /**
     * 选集按这条线路显示：正片最全的（综艺有的站有加更、上下期，有的站没有；夹了上百条花絮、纯享的不算多），一样多时上次播的那条优先。
     * 播放时按名字到各条线路里找同一集，所以显示哪条都能播。
     */
    @Suppress("UNUSED_PARAMETER")
    fun ref(set: LineSet?, lastKey: String?, title: String = ""): PlayLine? {
        if (set == null) return null
        choice[set.titleId]?.let { k -> set.lines.firstOrNull { it.key == k }?.let { return it } }
        // 有资料对得上的线路时，选集只按这些显示（没资料的可能是同名的另一部，集数多也不算）
        val r = ranked(set).let { all -> if (set.sure.isEmpty()) all else all.filter { it.first.key in set.sure }.ifEmpty { all } }
        SourceSearch.origin(set.titleId)?.let { o ->
            val own = set.lines.filter { it.siteKey == o.siteKey }
            if (own.isNotEmpty()) return own.firstOrNull { it.key == lastKey } ?: r.firstOrNull { it.first.siteKey == o.siteKey }?.first ?: own.first()
        }
        // 还没排好（刚搜到第一条）：先按播放顺序
        val best = r.firstOrNull() ?: return set.lines.firstOrNull { it.key == lastKey } ?: ordered(set, null, emptyList()).firstOrNull()
        // 上次播的那条接着用（选集名字跟上次看的一样），但不能少集：对得上的集数不比最好的少，其余的（名字好不好认）差一点没关系
        val last = r.firstOrNull { it.first.key == lastKey } ?: return best.first
        val (l, b) = last.second to best.second
        return if (l.good + l.fresh >= b.good + b.fresh && l.value >= b.value - maxOf(10, b.value / 8)) last.first else best.first
    }

    /** 线路名里的网盘关键词 → 网盘登录里的名字 */
    private val PANS = listOf(
        "夸克" to "夸克", "quark" to "夸克", "百度" to "百度", "baidu" to "百度", "UC" to "UC", "阿里" to "阿里",
        "115" to "115", "天翼" to "天翼", "189" to "天翼", "迅雷" to "迅雷", "123" to "123", "移动" to "移动", "139" to "移动", "光鸭" to "光鸭",
    )

    /** 是网盘线路、而且没看到那家网盘已登录（登录状态只在进过「网盘登录」后知道；不知道就按没登录算） */
    fun panLocked(flag: String): Boolean {
        val pan = PANS.firstOrNull { flag.contains(it.first, ignoreCase = it.first.length > 3) }?.second ?: return false
        return PanLogin.loggedIn().none { pan in it }
    }

    /** 手动选的「选集按哪条线路显示」：片子 id → 线路 key。只在这次打开 App 里有效 */
    private val choice = mutableStateMapOf<String, String>()

    fun chosen(titleId: String): String? = choice[titleId]

    fun choose(titleId: String, lineKey: String?) {
        if (lineKey == null) choice.remove(titleId) else choice[titleId] = lineKey
    }

    /**
     * 选集可以按哪几种名字显示：分数最高的几条线路，名字一模一样的只留一条（同一个站的几个来源常常一样）。
     * 第一个是自动选的。
     */
    fun versions(set: LineSet?, title: String = "", max: Int = 6): List<PlayLine> {
        if (set == null) return emptyList()
        val seen = HashSet<String>()
        return ranked(set).map { it.first }.filter { l -> seen.add(info(set, l, title).sig) }.take(max)
    }

    private fun info(set: LineSet, l: PlayLine, title: String): Eps.LineInfo =
        set.infos.getOrPut(l.key) { Eps.LineInfo(l.episodes, title) }

    /** 排好的线路；过时了（多了线路、有线路播放失败）就在后台重排，排好之前先用旧的 */
    private fun ranked(set: LineSet): List<Pair<PlayLine, Eps.Score>> {
        val r = set.ranking
        if (r.size != set.lines.size || set.rankFailed != set.failed.size) rerank(set)
        return r
    }

    /** 后台重排（几十条线路、每条上百集，放主线程会卡）；正在排的时候又要排，排完再来一遍 */
    private fun rerank(set: LineSet) {
        if (set.rankJob?.isActive == true) { set.rankDirty = true; return }
        set.rankJob = scope.launch(Dispatchers.Main) {
            do {
                set.rankDirty = false
                set.rankFailed = set.failed.size
                // 同分时按播放顺序（优先站点、直链在前）
                val ord = ordered(set, null, emptyList())
                val inf = ord.map { it.siteKey to info(set, it, set.title) }
                val sc = withContext(Dispatchers.Default) { Eps.scoreInfo(inf) }
                set.ranking = ord.indices.sortedByDescending { sc[it].value }.map { ord[it] to sc[it] }
            } while (set.rankDirty)
        }
    }

    private suspend fun search(ctx: Context, title: Title, set: LineSet) {
        var t = title
        val t0 = System.currentTimeMillis()
        val cfg = try { Sources.searchConfig() } catch (e: Throwable) {
            Log.w(TAG, "config", e)
            set.error = "片源配置加载失败：${e.message?.take(60)}"
            set.done = true
            return
        }
        // 设置里标了「不用」的站点不搜
        // 直播、音乐、听书、网盘搜索这类不是影视的站点也不搜（手动设成「优先」的除外）
        // 从「站点」里点开的：先用那个站点自己的分集（短剧、B站、网盘分享这类按片名配不上的也能播）
        val origin = SourceSearch.origin(t.id)?.let { o -> cfg.sites.firstOrNull { it.key == o.siteKey }?.let { it to o.vodId } }
        var originErr: String? = null
        if (origin != null) {
            val (s, vodId) = origin
            set.total = 1
            val v = fromOrigin(ctx, cfg, s, vodId, t, set).getOrElse { originErr = it.message?.take(80) ?: it.javaClass.simpleName; null }
            if (v != null) {
                val e = SourceSearch.enrich(t, v)
                if (e != t) { Catalog.putSource(listOf(e)); t = e }
            }
            set.searched = 1
            // 不是影视站（B站、音乐、网盘搜索…）的东西，别的站按名字也配不上，不去白搜
            if (s.notVideo != null && set.lines.isNotEmpty()) {
                set.done = true
                Log.i(TAG, "${t.name} · 站点「${s.name}」${set.lines.size} 条 · ${System.currentTimeMillis() - t0}ms")
                return
            }
        }
        val usable = cfg.sites.filter {
            it.searchable && it.kind.supported && it.key !in Prefs.blocked && (it.notVideo == null || Prefs.rank(it.key) != null) &&
                it.key != origin?.first?.key
        }
        // 先搜当前片源；其它片源的采集站只在值得的时候补搜（见 [needMore]），而且只留比当前片源好的线路（[worth]）
        // 继续观看时上次那个站排最前面先搜（播放在等它）；是其它片源的站也跟当前片源一起先搜
        val last = dev.personal.tv.data.Store.progress[t.id]?.line?.substringBefore('|')
        val (cross, sites) = usable.partition { CrossSources.isCross(it) && it.key != last }
            .let { (c, s) -> c to s.sortedByDescending { it.key == last } }
        set.total = sites.size + (if (origin != null) 1 else 0)
        val q = Match.query(t.name)
        Log.i(TAG, "${t.name} → 「$q」· ${sites.size} 个站点")
        // 插件站同时 8 个（插件进程扛不住更多）；采集站只是 HTTP 请求，同时 16 个
        val sem = Semaphore(8)
        val cmsSem = Semaphore(16)
        suspend fun run(list: List<SiteCfg>, accept: (PlayLine, Boolean) -> Boolean) = coroutineScope {
            list.forEach { s ->
                launch {
                    (if (s.kind == SiteKind.JAR) sem else cmsSem).withPermit {
                        playing.first { it == 0 }
                        one(ctx, cfg, s, t, q, set, accept)
                    }
                    set.doneSites += s.key
                    set.searched++
                }
            }
        }
        coroutineScope {
            val own = launch { withTimeoutOrNull(45_000) { run(sites) { _, _ -> true } } }
            if (cross.isEmpty()) return@coroutineScope
            // 当前片源搜完再判断；20 秒了还一条能播的都没有，就不等了，直接去补搜
            withTimeoutOrNull(20_000) { own.join() }
            if (own.isActive && baseLines(set).isNotEmpty()) own.join()
            val why = needMore(t, set) ?: return@coroutineScope
            Log.i(TAG, "${t.name} · 补搜其它片源 ${cross.size} 站：$why")
            set.total += cross.size
            withTimeoutOrNull(30_000) { run(cross) { l, sure -> worth(t, set, l, sure) } }
        }
        if (set.lines.isEmpty() && originErr != null) set.error = "「${SourceSearch.siteLabel(origin!!.first.name)}」打不开这部：$originErr"
        // 一条都没搜到、又是网络的问题：说网络不通，别说「站点里都没有」；有 error 下次进来马上重搜
        if (set.lines.isEmpty() && (!online(ctx) || set.netErrors > 0 && set.netErrors * 2 >= set.searched)) {
            set.error = "网络好像不通，没搜到线路。网络好了再试"
        }
        set.done = true
        Log.i(TAG, "${t.name} · ${set.lines.size} 条线路 · ${set.searched}/${set.total} 站 · ${System.currentTimeMillis() - t0}ms")
        if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val all = set.lines.toList()
            val sc = Eps.scoreInfo(all.map { it.siteKey to info(set, it, t.name) })
            val r = ref(set, null, t.name)?.key
            all.indices.sortedByDescending { sc[it].value }.forEach { k ->
                val l = all[k]
                val n = l.episodes.map { it.name }
                if (sc[k].noisy > 0) Log.d("LinesDump", "raw: " + n.take(3).joinToString(" ¦ "))
                Log.d("LinesDump", "${sc[k].value} ${sc[k]}|${if (l.key == r) "REF|" else ""}${l.siteName}|${l.flag}|${n.size}|${(n.take(6).map { Eps.label(it, t.name) } + listOf("…") + n.takeLast(6).map { Eps.label(it, t.name) }).joinToString(" ¦ ")}")
            }
        }
        if (t.isEpisodic && set.lines.isNotEmpty()) {
            // 豆瓣还没更新集数时按线路算；取第二多的，免得一个站把花絮也算进去
            // 综艺按名字分期（上下期、加更），条数不等于集数，不拿来算
            val counts = set.lines.filter { Eps.numeric(it.episodes, t.name, t.isVariety) }.map { l -> l.episodes.count { Eps.isNumber(it.name, t.name) } }.sortedDescending()
            val eps = if (counts.size >= 2) counts[1] else counts.firstOrNull() ?: 0
            if (eps > 0 && (t.totalEps <= 1 || eps <= t.totalEps)) Catalog.bumpAired(t.id, eps)
        }
    }

    /** 来源站点的详情：每个播放来源都算一条线路，不按片名、集数筛 */
    private suspend fun fromOrigin(ctx: Context, cfg: TvConfig, s: SiteCfg, vodId: String, t: Title, set: LineSet): Result<Vod?> {
        val job = bgIoScope.async { Sites.get(ctx, cfg, s).detail(vodId) }
        val v = try {
            withTimeoutOrNull(25_000) { job.await() } ?: return Result.failure(Exception("站点 25 秒没回应"))
        } catch (e: Throwable) {
            Log.d(TAG, "origin ${s.name}: ${e.message}")
            return Result.failure(e)
        } finally {
            job.cancel()
        }
        val name = v.name.ifBlank { t.name }
        val built = withContext(Dispatchers.Default) {
            v.flags.filter { it.episodes.isNotEmpty() }.map { f ->
                val line = PlayLine(s.key, s.name, f.name, name, v.remarks, Eps.ascending(Eps.trimCommon(f.episodes), name), vodId)
                line to Eps.LineInfo(line.episodes, name).also { it.sig }
            }
        }
        for ((line, info) in built) if (set.lines.none { it.key == line.key }) { set.infos[line.key] = info; set.sure += line.key; set.lines.add(line) }
        if (built.isNotEmpty()) rerank(set)
        else return Result.failure(Exception(if (v.flags.isEmpty()) "站点没给播放地址（网盘类的要先登录网盘）" else "分集是空的"))
        return Result.success(v)
    }

    /** 原名是拉丁字母的（英文片名）才拿去补搜；韩文、日文原名站点多半不收，白搜 */
    private fun altQuery(t: Title, q: String): String? {
        val o = t.origName.trim()
        if (o.length < 2 || o.count { it.code < 128 } < o.length * 0.8) return null
        return Match.query(o).takeIf { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- 跨片源补搜

    /** 当前片源（不算跨源来的）能播的线路：没登录的网盘、播放失败过的不算 */
    /**
     * 剧集里没资料（站点没给年份、地区）的线路像不像同名的另一部：已经有资料对得上的线路，它却跟这些线路一集都对不上；
     * 或者豆瓣说完结了，它的集数却多出一倍多。没有对得上的线路时不判断（没法比）。
     */
    internal fun stranger(t: Title, sures: List<PlayLine>, l: PlayLine): Boolean {
        if (sures.isEmpty()) return false
        if (!t.isVariety && !t.airing && t.totalEps > 1 && epCount(l, t) > t.totalEps * 2 + 2) return true
        val keys = l.episodes.mapTo(HashSet()) { Eps.key(it.name, t.name) }
        if (sures.any { v -> v.episodes.any { Eps.key(it.name, t.name) in keys } }) return false
        // 名字写法不一样（「第1集」和「01」、带不带日期）：抽一些按同一集的规则再找
        val step = (l.episodes.size + 39) / 40
        val sample = l.episodes.filterIndexed { i, _ -> i % step.coerceAtLeast(1) == 0 }
        return sures.none { v -> sample.any { e -> Eps.indexOf(v.episodes, e.name, t.name) >= 0 } }
    }

    private fun baseLines(set: LineSet) = set.lines.filter { !it.siteKey.startsWith("x:") && !panLocked(it.flag) && it.key !in set.failed }

    /** 线路有几集：按序号的数「第 n 集」（夹着的花絮不算）；综艺、按名字分集的（纪录片「永不落幕的青春秀场」这种）数条数 */
    private fun epCount(l: PlayLine, t: Title) =
        if (t.isVariety || !Eps.numeric(l.episodes, t.name, t.isVariety)) l.episodes.size else l.episodes.count { Eps.isNumber(it.name, t.name) }

    private val LOW_Q = Regex("HDTC|HDTS|抢先|枪版|(?<![A-Za-z])(TC|TS|CAM)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val Q4K = Regex("4K|2160|蓝光|原画|杜比|HDR", RegexOption.IGNORE_CASE)
    private val Q1080 = Regex("1080|超清|FHD|(?<![A-Za-z])BD(?![A-Za-z])", RegexOption.IGNORE_CASE)

    /** 画质（看线路名、备注、片名里的标记）：0 抢先/枪版，1 普通，2 1080，3 4K/蓝光 */
    internal fun quality(l: PlayLine): Int {
        val s = "${l.flag} ${l.remarks} ${l.vodName}"
        return when {
            LOW_Q.containsMatchIn(s) -> 0
            Q4K.containsMatchIn(s) -> 3
            Q1080.containsMatchIn(s) -> 2
            else -> 1
        }
    }

    /**
     * 要不要去其它片源补搜，要的话返回原因：当前片源没有能播的；集数比豆瓣少；还在更新（别的片源可能已经有下一集）；
     * 电影没有 1080 以上的；只有抢先、枪版。都不是就不搜——同一批采集站常常在好几个配置里，搜了也是重复的。
     */
    internal fun needMore(t: Title, set: LineSet): String? {
        val base = baseLines(set)
        if (base.isEmpty()) return "当前片源没有能直接播的线路"
        // 都是没年份、地区的站搜到的：可能全是同名的另一部（「后会有期」2011 比利时片，当前片源只搜到 B 站同名视频）
        if (base.none { it.key in set.sure }) return "当前片源的线路都对不上资料"
        val q = base.maxOf { quality(it) }
        if (q == 0) return "当前片源只有抢先版"
        if (t.isEpisodic) {
            val have = base.maxOf { epCount(it, t) }
            if (!t.isVariety && t.airedEps > have) return "当前片源只到第 $have 集，已经播到 ${t.airedEps} 集"
            if (t.airing) return "还在更新，别的片源可能更新得快"
        } else if (q < 2) return "当前片源没有 1080 以上的"
        return null
    }

    /** 补搜到的线路值不值得加进来：当前片源没有能播的、资料对得上而当前片源一条对得上的都没有、集数更多、画质更好，才加 */
    internal fun worth(t: Title, set: LineSet, l: PlayLine, sure: Boolean = false): Boolean {
        val base = baseLines(set)
        if (base.isEmpty()) return true
        if (sure && base.none { it.key in set.sure }) return true
        if (quality(l) > base.maxOf { quality(it) }) return true
        return t.isEpisodic && epCount(l, t) > base.maxOf { epCount(it, t) }
    }

    private suspend fun one(ctx: Context, cfg: TvConfig, s: SiteCfg, t: Title, q: String, set: LineSet, accept: (PlayLine, Boolean) -> Boolean = { _, _ -> true }) {
        val alt = altQuery(t, q)
        // 插件的阻塞调用取消不了：超时就不等了，线程自己跑完
        val job = bgIoScope.async {
            val api = Sites.get(ctx, cfg, s)
            fun ok(v: Vod) = Match.same(t.name, t.year, !t.isEpisodic, v.name, v.year) ||
                (alt != null && Match.same(t.origName, t.year, !t.isEpisodic, v.name, v.year))
            var hits = api.search(q).filter(::ok).take(2)
            // 中文名搜不到、原名是英文的（很多站只收英文名，比如 UNSEEN）：拿原名再搜一次
            if (hits.isEmpty() && alt != null) hits = api.search(alt).filter(::ok).take(2)
            hits.mapNotNull { v -> if (v.flags.isNotEmpty()) v else runCatching { api.detail(v.id) }.getOrNull() }
        }
        // 插件站头一回要先加载 jar、初始化（荐片实测 5～7 秒），8 个插件挤在一个进程里再搜索、拿详情，25 秒也常常不够。
        // 当前片源这批总共等 45 秒、线路边搜边加，不耽误开播，插件站就等到 40 秒
        val limit = if (s.kind == SiteKind.JAR) 40_000L else 15_000L
        val vods = try {
            withTimeoutOrNull(limit) { job.await() } ?: run { Log.d(TAG, "${s.name}: ${limit / 1000} 秒没搜完"); null }
        } catch (e: Throwable) {
            Log.d(TAG, "${s.name}: ${e.message}")
            if (generateSequence(e) { it.cause }.take(5).any { it is java.io.IOException }) set.netErrors++
            null
        } finally {
            job.cancel()
        } ?: return
        if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) vods.forEach { v ->
            Log.d("LinesDump", "vod ${s.name}|${v.name}|年=${v.year}|${v.typeName}|${v.area}|导=${v.director.take(30)}|演=${v.actor.take(40)}|${v.remarks}|${v.flags.joinToString { it.name + ":" + it.episodes.size }}")
        }
        // 详情里才有年份、地区的：再对一遍，对不上的是同名的另一部（比如同叫「后会有期」的美国电影和湖南卫视纪录片）
        val checked = vods.mapNotNull { v ->
            val c = Match.check(t.year, !t.isEpisodic, t.region, v.year, v.area)
            if (c < 0) { Log.d(TAG, "${s.name}「${v.name}」${v.year} ${v.area}：不是这部"); null } else v to (c > 0)
        }
        // 排序、拆分集名字（打分用）都是正则，几十条线路、每条上百集，放到后台算，别卡界面
        val built = withContext(Dispatchers.Default) {
            checked.flatMap { (v, sure) ->
                v.flags.filter { f ->
                    // 电影只要一集的来源；剧集的来源只有一集多半是预告
                    f.episodes.isNotEmpty() && !(!t.isEpisodic && f.episodes.size > 3) &&
                        !(t.isEpisodic && f.episodes.size == 1 && (t.totalEps > 1 || t.airedEps > 1))
                }.map { f ->
                    val line = PlayLine(s.key, s.name, f.name, v.name, v.remarks, Eps.ascending(Eps.trimCommon(f.episodes), t.name), v.id)
                    Triple(line, Eps.LineInfo(line.episodes, t.name).also { it.sig }, sure)
                }
            }
        }
        var added = false
        var newSure = false
        for ((line, info, sure) in built) {
            if (set.lines.any { it.key == line.key } || !accept(line, sure)) continue
            if (!sure && t.isEpisodic) {
                val sures = set.lines.filter { it.key in set.sure }
                if (withContext(Dispatchers.Default) { stranger(t, sures, line) }) { Log.d(TAG, "${s.name} · ${line.flag}：跟对得上的线路一集都不一样，不是这部"); continue }
                if (set.lines.any { it.key == line.key }) continue
            }
            added = true
            set.infos[line.key] = info
            if (sure) { set.sure += line.key; newSure = true }
            set.lines.add(line)
        }
        // 剧集：头一回有对得上的线路时，把先到的、没资料又跟它一集都对不上的线路拿掉
        if (newSure && t.isEpisodic) {
            val sures = set.lines.filter { it.key in set.sure }
            val rest = set.lines.filter { it.key !in set.sure }
            val odd = withContext(Dispatchers.Default) { rest.filter { stranger(t, sures, it) } }.map { it.key }.toSet()
            if (odd.isNotEmpty()) {
                Log.d(TAG, "${t.name} · 拿掉不是这部的 ${odd.size} 条：$odd")
                set.lines.removeAll { it.key in odd }
                odd.forEach { set.infos.remove(it) }
            }
        }
        if (added) rerank(set)
    }
}
