package dev.personal.tv.source

import android.content.Context
import android.util.Log
import dev.personal.tv.data.Kind
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在片源里搜片名：豆瓣上没有、站点里有的片子（短剧、刚上的、冷门的）。
 * 站点搜到的按片名 + 季数 + 年份合并成一部，和豆瓣结果对得上的去掉；剩下的做成 id 以「src:」开头的条目，
 * 点进去照常找线路（[Lines] 按片名搜），只是没有豆瓣评分和人数。
 */
object SourceSearch {
    private const val TAG = "SourceSearch"
    const val PREFIX = "src:"
    /** 最多列这么多部（按几个站点有排序，太散的零星结果不列） */
    private const val MAX = 60
    private val PIC_HEADERS = Regex("@(Referer|User-Agent|Cookie|Headers|Origin)=.*$", RegexOption.IGNORE_CASE)

    /**
     * 搜。每搜完一个站点回调一次 [onUpdate]（结果、已搜站数、总站数），在调用方的线程上。
     * 同时 8 个站点，单站 12 秒，整体 25 秒。[douban] 是豆瓣已经给出的结果，对得上的不重复列。
     */
    suspend fun search(ctx: Context, q: String, douban: List<Title>, onUpdate: (List<Title>, Int, Int) -> Unit) {
        val cfg = runCatching { Sources.config() }.getOrElse { onUpdate(emptyList(), 0, 0); return }
        val sites = cfg.sites.filter {
            it.searchable && it.kind.supported && it.key !in Prefs.blocked && (it.notVideo == null || Prefs.rank(it.key) != null)
        }
        val want = Match.norm(q)
        val groups = LinkedHashMap<String, MutableList<Pair<SiteCfg, Vod>>>()
        var searched = 0
        onUpdate(emptyList(), 0, sites.size)
        val app = ctx.applicationContext
        val sem = Semaphore(8)
        withTimeoutOrNull(25_000) {
            coroutineScope {
                sites.forEach { s ->
                    launch {
                        val vods = sem.withPermit {
                            // 插件的阻塞调用取消不了：超时就不等了，线程自己跑完
                            val job = CoroutineScope(Dispatchers.IO).async { Sites.get(app, cfg, s).search(q) }
                            runCatching { withTimeoutOrNull(12_000) { job.await() } }.getOrNull().orEmpty()
                        }
                        for (v in vods) {
                            if (v.name.isBlank() || !hit(Match.norm(v.name), want)) continue
                            if (douban.any { d -> Match.same(d.name, d.year, d.kind == Kind.MOVIE, v.name, v.year) }) continue
                            groups.getOrPut(groupKey(v)) { ArrayList() } += s to v
                        }
                        searched++
                        onUpdate(build(groups), searched, sites.size)
                    }
                }
            }
        }
        Log.i(TAG, "「$q」· ${groups.size} 部 · $searched/${sites.size} 站")
        onUpdate(build(groups), searched, sites.size)
    }

    /**
     * 片名里有搜的词。数字两头不能再接数字：搜「007」不要「苹果2007」「雪山飞狐2007」。
     */
    internal fun hit(name: String, want: String): Boolean {
        if (want.isEmpty()) return true
        var i = name.indexOf(want)
        while (i >= 0) {
            val before = name.getOrNull(i - 1)
            val after = name.getOrNull(i + want.length)
            val okBefore = !(want.first().isDigit() && before?.isDigit() == true)
            val okAfter = !(want.last().isDigit() && after?.isDigit() == true)
            if (okBefore && okAfter) return true
            i = name.indexOf(want, i + 1)
        }
        return false
    }

    private fun year(v: Vod) = Regex("(19|20)\\d{2}").find(v.year)?.value?.toInt() ?: 0

    private fun groupKey(v: Vod): String {
        val k = Match.key(v.name)
        return "${k.base}${if (k.season > 1) "#${k.season}" else ""}"
    }

    /** 每组挑信息最全的那条做代表；有几个站点有排前面 */
    private fun build(groups: Map<String, List<Pair<SiteCfg, Vod>>>): List<Title> =
        groups.entries.sortedByDescending { e -> e.value.map { it.first.key }.distinct().size }.take(MAX).map { (key, hits) ->
            val v = hits.map { it.second }.maxBy { (if (it.pic.isNotBlank()) 4 else 0) + (if (year(it) > 0) 2 else 0) + (if (it.content.isNotBlank()) 1 else 0) }
            val y = hits.map { year(it.second) }.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
            toTitle("$PREFIX$key", v, y, hits.map { it.first.key }.distinct().size)
        }

    // ------------------------------------------------------------ 站点浏览

    /** 浏览站点时点开的片：id = src:@站点key@片子id。线路直接用这个站点给的分集（见 [Lines]），不按片名去配 */
    data class Origin(val siteKey: String, val vodId: String)

    fun origin(id: String): Origin? {
        if (!id.startsWith("$PREFIX@")) return null
        val rest = id.substring(PREFIX.length + 1)
        val i = rest.indexOf('@')
        return if (i <= 0 || i == rest.lastIndex) null else Origin(rest.substring(0, i), rest.substring(i + 1))
    }

    /** 站点名去掉表情和分隔符：「🌶️┆骚火┆影视」→「骚火 影视」 */
    fun siteLabel(name: String) = name.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().ifEmpty { name }

    /** 站点列表里的一条做成条目 */
    fun siteTitle(s: SiteCfg, v: Vod): Title =
        toTitle("$PREFIX@${s.key}@${v.id}", v, year(v), 1).copy(
            oneLiner = "来自「${siteLabel(s.name)}」" + (v.remarks.trim().takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
        )

    /**
     * 拿到站点详情后补全条目：简介、海报、主演；分集多于 1 集的按剧集算（站点列表里常常看不出是电影还是剧），
     * 不然线路和选集都按电影处理，只剩一集。
     */
    fun enrich(t: Title, v: Vod): Title {
        val d = toTitle(t.id, v, year(v).takeIf { it > 0 } ?: t.year, 1)
        val eps = v.flags.maxOfOrNull { it.episodes.size } ?: 0
        val kind = when {
            eps > 1 && t.kind == Kind.MOVIE -> if (d.kind != Kind.MOVIE) d.kind else Kind.SERIES
            eps == 1 && t.kind == Kind.SERIES && d.kind == Kind.MOVIE -> Kind.MOVIE
            else -> t.kind
        }
        return t.copy(
            name = t.name.ifBlank { d.name }, year = if (t.year > 0) t.year else d.year,
            region = t.region.ifBlank { d.region }, kind = kind, dtype = if (eps > 1) "tv" else if (kind == Kind.MOVIE) "movie" else t.dtype,
            genres = t.genres.ifEmpty { d.genres }, poster = t.poster ?: d.poster,
            desc = d.desc.ifBlank { t.desc }, director = d.director.ifBlank { t.director }, cast = d.cast.ifEmpty { t.cast },
            totalEps = if (eps > 1) eps else t.totalEps, airedEps = if (eps > 1) eps else t.airedEps,
        )
    }

    private fun toTitle(id: String, v: Vod, year: Int, sites: Int): Title {
        val kind = kindOf(v)
        val desc = v.content.replace(Regex("<[^>]+>"), "").replace(Regex("&nbsp;?"), " ").replace(Regex("\\s+"), " ").trim()
        return Title(
            id = id, name = v.name.trim(), year = year,
            region = v.area.split(Regex("[,，/ ]")).firstOrNull { it.isNotBlank() }.orEmpty(),
            kind = kind,
            genres = listOfNotNull(v.typeName.trim().takeIf { it.isNotEmpty() }),
            score = null,
            oneLiner = "片源里有 · $sites 个站点" + (v.remarks.trim().takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
            // 影视仓的约定：「地址@Referer=…@User-Agent=…」，后面是取图要带的请求头；豆瓣图的 Referer 由图片加载器统一加
            poster = v.pic.trim().replace(PIC_HEADERS, "").takeIf { it.startsWith("http") },
            desc = desc,
            director = v.director.split(Regex("[,，/]")).map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" / "),
            cast = v.actor.split(Regex("[,，/]")).map { it.trim() }.filter { it.isNotEmpty() }.take(4),
            full = true,
        )
    }

    /** 站点的分类名五花八门：「国产剧」「动作片」「日韩动漫」「大陆综艺」「短剧」 */
    private fun kindOf(v: Vod): Kind {
        val t = v.typeName
        return when {
            Regex("动漫|动画|番剧|国漫|日漫").containsMatchIn(t) -> Kind.ANIME
            Regex("综艺|真人秀|晚会|脱口秀").containsMatchIn(t) -> Kind.VARIETY
            "纪录" in t -> Kind.DOC
            Regex("剧").containsMatchIn(t) -> Kind.SERIES
            Regex("电影|片$").containsMatchIn(t) -> Kind.MOVIE
            Regex("\\d+\\s*集|更新|全集|EP", RegexOption.IGNORE_CASE).containsMatchIn(v.remarks) -> Kind.SERIES
            else -> Kind.MOVIE
        }
    }
}
