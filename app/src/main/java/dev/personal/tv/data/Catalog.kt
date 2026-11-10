package dev.personal.tv.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
internal val gson = Gson()

/**
 * 存一份 JSON 到 filesDir：先写临时文件、刷到盘上（fsync）再改名。
 * 电视棒靠电视 USB 供电，一关电视就断电；不刷盘的话改名可能先落盘、内容还没写进去。
 */
internal fun saveJson(f: File, v: Any) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    java.io.FileOutputStream(tmp).use { out ->
        out.write(gson.toJson(v).toByteArray())
        out.flush()
        out.fd.sync()
    }
    tmp.renameTo(f)
}

/**
 * 片库：豆瓣条目的本地缓存 + 各种列表。界面直接读这里的 state，接口回来后自动刷新。
 * 缓存存在 filesDir/catalog.json，冷启动先显示上次的，再后台刷新。
 */
object Catalog {
    private const val TAG = "Catalog"
    private val titles = mutableStateMapOf<String, Title>()
    private val hotTv = mutableStateListOf<String>()
    private val hotMovie = mutableStateListOf<String>()
    private val heats = mutableStateMapOf<String, Heat>()
    private val recs = mutableStateMapOf<String, List<String>>()
    private var hotAt = 0L
    private val inflight = HashSet<String>()

    /** 最近一次拉豆瓣失败的原因，首页没数据时显示 */
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    private lateinit var file: File

    private class Saved(val titles: List<Title>?, val hotTv: List<String>?, val hotMovie: List<String>?, val hotAt: Long)

    @OptIn(FlowPreview::class)
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "catalog.json")
        runCatching {
            if (file.exists()) {
                val s = gson.fromJson(file.readText(), Saved::class.java)
                s.titles?.filter { it.name != null && it.genres != null && it.cast != null }?.forEach { titles[it.id] = it }
                s.hotTv?.let { hotTv.addAll(it) }
                s.hotMovie?.let { hotMovie.addAll(it) }
                hotAt = s.hotAt
            }
        }.onFailure { Log.w(TAG, "load", it) }
        appScope.launch {
            snapshotFlow { titles.size to titles.values.count { it.full } + hotTv.size }.drop(1).debounce(2000).collect { save() }
        }
        appScope.launch {
            if (System.currentTimeMillis() - hotAt > 3600_000 || hotTv.isEmpty()) refreshHot()
            refreshAllAiring()
            prefetch()
        }
    }

    /**
     * 后台预取：首页热门的详情、评分、相似，找片各类别的第一页。
     * 都会进豆瓣接口的磁盘缓存，之后点进去不用等网络。同时最多 3 个请求。
     */
    private suspend fun prefetch() {
        val sem = Semaphore(3)
        coroutineScope {
            listOf("tv" to "电视剧", "movie" to "电影", "tv" to "综艺", "tv" to "动画", "tv" to "纪录片").forEach { (type, tag) ->
                launch { sem.withPermit { runCatching { browse(type, listOfNotNull(tag), "U", 0, 60, 0f) } } }
            }
            hot().take(18).forEach { t ->
                launch {
                    sem.withPermit {
                        val full = ensureFull(t.id) ?: t
                        loadHeat(full)
                        loadSimilar(full)
                    }
                }
            }
        }
        Log.i(TAG, "prefetch done")
    }

    private fun save() = runCatching {
        // 只留和自己有关的和最近的，免得越存越大
        val keep = (Store.progress.keys + Store.wish + hotTv + hotMovie).toSet()
        val list = titles.values.sortedByDescending { it.id in keep }.take(4000)
        saveJson(file, Saved(list, hotTv.toList(), hotMovie.toList(), hotAt))
    }.onFailure { Log.w(TAG, "save", it) }

    fun get(id: String): Title? = titles[id]

    /** 片源里搜到的（豆瓣没有）：放进片库好打开详情；同 id 已有的照样换成新的（海报、简介可能更全） */
    fun putSource(list: List<Title>) {
        list.forEach { titles[it.id] = it }
    }

    /** 合并：列表接口的残缺数据不覆盖已经补全的详情，只更新评分和海报 */
    fun put(list: List<Title>) {
        for (t in list) {
            val old = titles[t.id]
            titles[t.id] = when {
                old == null -> t
                old.full && !t.full -> old.copy(score = t.score ?: old.score, ratingCount = maxOf(t.ratingCount, old.ratingCount), poster = old.poster ?: t.poster)
                    .withEpsFrom(t)
                else -> t.copy(poster = t.poster ?: old.poster)
            }
        }
    }

    /** 线路找到的集数比豆瓣写的多（豆瓣还没更新），按线路的算 */
    fun bumpAired(id: String, eps: Int) {
        val t = titles[id] ?: return
        if (!t.isEpisodic || eps <= t.airedEps) return
        titles[id] = t.copy(airedEps = eps, totalEps = maxOf(t.totalEps, eps), schedule = t.schedule.takeIf { eps < t.totalEps })
    }

    // ---------------------------------------------------------------- 最近热门

    /** 剧集和电影穿插：两部剧一部电影 */
    fun hot(): List<Title> {
        val tv = hotTv.mapNotNull { titles[it] }.filter { it.id !in Store.hidden }
        val mv = hotMovie.mapNotNull { titles[it] }.filter { it.id !in Store.hidden }
        val out = ArrayList<Title>()
        var i = 0; var j = 0
        while (i < tv.size || j < mv.size) {
            repeat(2) { if (i < tv.size) out += tv[i++] }
            if (j < mv.size) out += mv[j++]
        }
        return out
    }

    suspend fun refreshHot() = withContext(Dispatchers.IO) {
        loading = true
        try {
            val tv = Douban.recentHot("tv", 30)
            val mv = Douban.recentHot("movie", 20)
            withContext(Dispatchers.Main) {
                put(tv + mv)
                hotTv.clear(); hotTv.addAll(tv.map { it.id })
                hotMovie.clear(); hotMovie.addAll(mv.map { it.id })
                hotAt = System.currentTimeMillis()
                error = null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "hot", e)
            error = "豆瓣连不上：${e.message}"
        } finally { loading = false }
    }

    // ---------------------------------------------------------------- 详情

    private val detailLock = Mutex()

    /** 补全详情（导演、简介、集数），已补全的直接返回；连载中的顺手在后台重拉一次集数 */
    suspend fun ensureFull(id: String): Title? {
        titles[id]?.takeIf { it.full }?.let { if (it.airing) appScope.launch { refreshAiring(id) }; return it }
        val t = titles[id] ?: return null
        return detailLock.withLock {
            titles[id]?.takeIf { it.full }?.let { return@withLock it }
            runCatching { withContext(Dispatchers.IO) { Douban.detail(id, t.dtype) } }
                .onFailure { Log.w(TAG, "detail $id", it) }
                .getOrNull()?.let { d ->
                    val merged = d.copy(poster = d.poster ?: t.poster)
                    withContext(Dispatchers.Main) { titles[id] = merged }
                    merged
                } ?: t
        }
    }

    /** 本次启动已经重拉过集数的，不重复拉 */
    private val airingChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 连载中的剧重拉详情看出到第几集、完结没有。补全过的条目存在 catalog.json 里不会再拉，
     * 不重拉的话完结了首页还一直写「更新中」。详情缓存 6 小时内直接用。
     */
    private suspend fun refreshAiring(id: String) {
        val old = titles[id]?.takeIf { it.full && it.airing } ?: return
        if (!airingChecked.add(id)) return
        val d = runCatching { withContext(Dispatchers.IO) { Douban.detail(id, old.dtype, maxAge = 6 * 3600_000L) } }
            .onFailure { Log.w(TAG, "airing $id", it); airingChecked.remove(id) }
            .getOrNull() ?: return
        withContext(Dispatchers.Main) {
            val cur = titles[id] ?: old
            titles[id] = d.copy(poster = d.poster ?: cur.poster).withEpsFrom(cur)
        }
    }

    /** 启动时把在看的、想看的、首页热门里还在连载的都重拉一遍集数 */
    private suspend fun refreshAllAiring() {
        val ids = (Store.progress.keys + Store.wish + hotTv).distinct().filter { titles[it]?.airing == true }
        val sem = Semaphore(3)
        coroutineScope { ids.forEach { id -> launch { sem.withPermit { refreshAiring(id) } } } }
        Log.i(TAG, "airing refreshed ${ids.size}")
        // 只改了集数，条目数没变，存盘的监听不会触发，这里自己存
        if (ids.isNotEmpty()) save()
    }

    fun heat(id: String): Heat? = heats[id]

    suspend fun loadHeat(t: Title) {
        if (t.fromSource) return
        if (heats.containsKey(t.id) || !inflight.add("heat:${t.id}")) return
        try {
            runCatching { withContext(Dispatchers.IO) { Douban.rating(t) } }.getOrNull()?.let { heats[t.id] = it }
        } finally { inflight.remove("heat:${t.id}") }
    }

    // ---------------------------------------------------------------- 找类似

    suspend fun loadSimilar(t: Title) {
        if (t.fromSource) return
        if (recs.containsKey(t.id) || !inflight.add("rec:${t.id}")) return
        try {
            val list = runCatching { withContext(Dispatchers.IO) { Douban.recommendations(t) } }.getOrNull() ?: return
            put(list)
            recs[t.id] = list.map { it.id }
        } finally { inflight.remove("rec:${t.id}") }
    }

    /** 喜欢这部的也喜欢 来自豆瓣；同导演 / 同主演 / 同标签 在本地已知的片里找 */
    fun similar(t: Title, source: SimilarSource): List<Title> {
        val others = titles.values.filter { it.id != t.id && it.id !in Store.hidden }
        val list = when (source) {
            SimilarSource.ALSO_LIKED -> recs[t.id].orEmpty().mapNotNull { titles[it] }.filter { it.id !in Store.hidden }
            SimilarSource.DIRECTOR -> {
                val ds = t.director.split(" / ").filter { it.isNotBlank() }
                others.filter { o -> ds.any { it in o.director } }
            }
            SimilarSource.CAST -> others.filter { o -> o.cast.any { it in t.cast } }
            SimilarSource.TAG -> others.filter { o -> o.genres.isNotEmpty() && o.genres.firstOrNull() == t.genres.firstOrNull() && o.kind == t.kind }
                .sortedByDescending { it.score ?: 0f }
        }
        return list.take(12)
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 首字母只能在本地认识的片里找（豆瓣接口不认拼音）：热门、找片翻过的、看过的、想看的，
     * 大头在 [SearchIndex]，见 [search]。
     */
    /**
     * 搜索：本地认识的片（看过、想看、热门、找片翻过的）+ 片库索引（一万多部，见 [SearchIndex]）。
     * [remote] = true 时（输入法打的中文 / 英文）再加豆瓣联想，覆盖全部豆瓣。
     */
    suspend fun search(q: String, remote: Boolean): List<Title> {
        val key = q.uppercase().replace(" ", "")
        val idx = withContext(Dispatchers.Default) { SearchIndex.search(q) }
        val sug = if (remote) remoteSearch(q) else emptyList()
        // 改表在主线程；匹配、排序（要算拼音首字母）放后台，几千部也不卡遥控器
        val all = withContext(Dispatchers.Main) {
            put(sug)
            // 索引里的条目只在本地还没有时放进来，不覆盖已有的完整数据
            idx.forEach { e -> if (titles[e.id] == null) titles[e.id] = e.toTitle() }
            titles.values.toList()
        }
        val hidden = Store.hidden.toSet()
        val mine = Store.progress.keys.toSet() + Store.wish.toSet()
        return withContext(Dispatchers.Default) {
            val byId = all.associateBy { it.id }
            val idxIds = idx.map { it.id }.toSet()
            val out = LinkedHashMap<String, Title>()
            // 联想排最前（豆瓣自己排的相关度），然后是完全匹配，再按评价人数
            sug.forEach { byId[it.id]?.let { t -> out[t.id] = t } }
            all.asSequence()
                .filter { it.id !in hidden && (it.id in idxIds || matches(it, q, key)) }
                .sortedWith(
                    compareByDescending<Title> { it.initials == key || it.name == q }
                        .thenByDescending { it.id in mine }
                        .thenByDescending { it.ratingCount },
                )
                .forEach { out.putIfAbsent(it.id, it) }
            out.values.filter { it.id !in hidden }.take(60)
        }
    }

    /**
     * 输入法搜索：豆瓣联想 + 全站搜索 + 英文换个单复数再搜，一起拉，合起来按人数排。
     * 联想带英文原名，搜索带类型和评分，同一部两边都有就拼起来。
     */
    private suspend fun remoteSearch(q: String): List<Title> = coroutineScope {
        val jobs = listOf(async(Dispatchers.IO) { runCatching { Douban.suggest(q) }.getOrNull().orEmpty() }) +
            (listOf(q) + SearchQuery.variants(q)).map { v -> async(Dispatchers.IO) { runCatching { Douban.search(v) }.getOrNull().orEmpty() } }
        val byId = LinkedHashMap<String, Title>()
        jobs.awaitAll().flatten().forEach { t ->
            val o = byId[t.id]
            byId[t.id] = if (o == null) t else t.copy(origName = t.origName.ifEmpty { o.origName }, poster = t.poster ?: o.poster)
        }
        SearchQuery.rank(byId.values.toList(), q)
    }

    private fun matches(t: Title, q: String, key: String) =
        key.isNotEmpty() && (t.initials.startsWith(key) || t.name.contains(q, true) || (key.length >= 2 && t.initials.contains(key)))

    suspend fun suggest(q: String): List<Title> {
        val list = runCatching { withContext(Dispatchers.IO) { Douban.suggest(q) } }.getOrNull().orEmpty()
        withContext(Dispatchers.Main) { put(list) }
        return list.mapNotNull { titles[it.id] }
    }


    // ---------------------------------------------------------------- 找片

    suspend fun browse(type: String, tags: List<String>, sort: String, start: Int, count: Int, minScore: Float): List<Title> {
        val list = withContext(Dispatchers.IO) { Douban.recommend(type, tags, sort, start, count, minScore) }
        withContext(Dispatchers.Main) { put(list) }
        return list.map { titles[it.id] ?: it }
    }
}

/**
 * 本地状态：观看进度、本地想看、不感兴趣。只存在本机（filesDir/store.json），不关联任何账号。
 */
object Store {
    val progress = mutableStateMapOf<String, Progress>()
    val wish: SnapshotStateList<String> = mutableStateListOf()
    val hidden: SnapshotStateList<String> = mutableStateListOf()

    private lateinit var file: File
    private class Saved(val progress: List<Progress>?, val wish: List<String>?, val hidden: List<String>?)

    @OptIn(FlowPreview::class)
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "store.json")
        runCatching {
            if (file.exists()) {
                val s = gson.fromJson(file.readText(), Saved::class.java)
                s.progress?.forEach { progress[it.titleId] = it }
                s.wish?.let { wish.addAll(it) }
                s.hidden?.let { hidden.addAll(it) }
            }
        }.onFailure {
            // 读不出来（写坏了）：先把坏文件留一份，别让下一次保存用空表盖掉，以后还能手动救
            Log.w("Store", "load", it)
            runCatching { file.renameTo(File(file.parentFile, "store.json.bad-${System.currentTimeMillis()}")) }
        }
        appScope.launch {
            snapshotFlow { Triple(progress.toMap(), wish.toList(), hidden.toList()) }.drop(1).debounce(800).collect { (p, w, h) ->
                runCatching { saveJson(file, Saved(p.values.toList(), w, h)) }.onFailure { Log.w("Store", "save", it) }
            }
        }
    }

    fun continueWatching(): List<Pair<Title, Progress>> =
        progress.values.sortedByDescending { it.lastWatched }
            .mapNotNull { p -> Catalog.get(p.titleId)?.let { it to p } }

    /** 看完了最新一集，但又出了新集：返回新出的集数 */
    fun newEpisodes(t: Title, p: Progress): Int {
        if (!t.isEpisodic || !p.finishedEp || t.airedEps <= p.ep) return 0
        // 综艺按名字分期（上下期、加更），序号和豆瓣的集数对不上，不算；0.7.1 之前的综艺记录说不清是什么，也不算
        if (p.isNamed(t.name) || t.isVariety && p.named == null && p.epName == null) return 0
        return t.airedEps - p.ep
    }

    fun toggleWish(id: String) {
        if (id in wish) wish.remove(id) else wish.add(0, id)
    }

    fun hide(id: String) {
        if (id !in hidden) hidden.add(id)
        wish.remove(id)
    }
}
