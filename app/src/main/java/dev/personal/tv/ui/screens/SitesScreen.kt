package dev.personal.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Title
import dev.personal.tv.source.SiteCfg
import dev.personal.tv.source.Sites
import dev.personal.tv.source.SourceSearch
import dev.personal.tv.source.Sources
import dev.personal.tv.source.VodClass
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Chip
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.PosterCell
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.TopAlignScroll
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 首页这一栏的分类 id */
private const val HOME = "\u0000home"
/** 左栏第一项「豆瓣找片」 */
private const val DOUBAN = "\u0000douban"

/** 一个站点一个分类的列表，存在单例里：切走再回来不重拉 */
private class SitePage {
    val items = mutableStateListOf<Title>()
    var classes by mutableStateOf<List<VodClass>?>(null)
    var page = 0
    var loading by mutableStateOf(false)
    var end by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** 海报宽高比（插件的 style），null = 竖版 */
    var ratio by mutableStateOf<Float?>(null)
    /** 这一页里的文件夹：条目 id → 站点里的 vod_id（点开用 categoryContent 拉下一层） */
    val folders = HashMap<String, String>()
}

private object SitePages {
    private val pages = LinkedHashMap<String, SitePage>()
    fun get(key: String): SitePage = pages.getOrPut(key) {
        if (pages.size > 40) pages.remove(pages.keys.first())
        SitePage()
    }
}

/** 站点浏览里列出来的站点：插件、CMS 能用的，设置里「不用」的和配置页、推送这类工具站不列 */
private fun browsable(s: SiteCfg) = s.kind.supported && s.key !in Prefs.blocked && s.notVideo != "工具"

/**
 * 找片：左栏第一项是「豆瓣找片」（[FindScreen]，按豆瓣榜单和筛选找），下面是当前片源的各个站点——
 * 像影视仓那样直接逛每个站点自己的首页和分类。短剧、B站、动漫、网盘分享这类按豆瓣片名配不上的内容在站点里看；
 * 点开以后线路用这个站点自己的分集（见 [dev.personal.tv.source.Lines]），不按片名去别的站配。
 * 站点给的文件夹（vod_tag=folder，B站个人中心、媒体库目录）点开是下一层列表，返回键回上一层。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SitesScreen(mem: FocusMemory) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var sites by remember { mutableStateOf<List<SiteCfg>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Sources.url) {
        if (Sources.empty) { err = "还没有片源，先到「设置 → 片源」里加一个"; sites = null; return@LaunchedEffect }
        err = null
        try { sites = Sources.config().sites.filter(::browsable) } catch (e: Throwable) { err = "片源配置加载失败：${e.message?.take(60)}" }
    }
    var siteKey by rememberSaveable { mutableStateOf(DOUBAN) }
    /** 左栏焦点停在哪个站点上：停 0.6 秒才切过去，免得上下翻的时候每个站都去拉一遍 */
    var hovered by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hovered) { val h = hovered ?: return@LaunchedEffect; delay(600); siteKey = h }
    val list = sites
    val site = list?.firstOrNull { it.key == siteKey }
    val douban = site == null
    var cls by rememberSaveable(site?.key) { mutableStateOf(HOME) }
    /** 点进去的文件夹，一层一行「vod_id\u0001名字」；从详情回来要还在，所以存成字符串 */
    var trailS by rememberSaveable(site?.key, cls) { mutableStateOf("") }
    val trail = trailS.split('\n').filter { it.isNotEmpty() }.map { it.substringBefore('\u0001') to it.substringAfter('\u0001') }
    val folder = trail.lastOrNull()?.first
    fun popFolder() { trailS = trail.dropLast(1).joinToString("\n") { "${it.first}\u0001${it.second}" } }
    BackHandler(enabled = trail.isNotEmpty()) { popFolder() }

    val cfgUrl = Sources.url.orEmpty()
    val page = remember(cfgUrl, site?.key, cls, folder) { SitePages.get("$cfgUrl|${site?.key}|$cls" + (folder?.let { "|f:$it" } ?: "")) }
    val head = remember(cfgUrl, site?.key) { SitePages.get("$cfgUrl|${site?.key}|$HOME") }
    val scope = rememberCoroutineScope()

    /** 拉 [c] 这一栏的下一页到 [p]。首页只有一页，分类表也从首页拿 */
    fun load(p: SitePage, c: String) {
        val s = site ?: return
        if (p.loading || p.end) return
        p.loading = true
        scope.launch {
            try {
                val cfg = Sources.config()
                // 插件的阻塞调用取消不了：超时就不等了
                val job = CoroutineScope(Dispatchers.IO).async {
                    val api = Sites.get(ctx.applicationContext, cfg, s)
                    if (c == HOME) api.home() else api.category(c, p.page + 1)
                }
                val got = withTimeoutOrNull(25_000) { job.await() } ?: error("站点 25 秒没回应")
                if (c == HOME) p.classes = got.classes
                if (p.ratio == null) p.ratio = got.ratio ?: got.list.firstNotNullOfOrNull { it.ratio }
                val have = p.items.map { it.id }.toHashSet()
                val fresh = got.list.filter { it.id.isNotBlank() && it.name.isNotBlank() }.mapNotNull { v ->
                    SourceSearch.siteTitle(s, v).takeIf { have.add(it.id) }?.also { if (v.folder) p.folders[it.id] = v.id }
                }
                p.items.addAll(fresh)
                p.page++
                if (c == HOME || fresh.isEmpty()) p.end = true
                p.error = null
                // 首页没列表（不少插件只给分类）：直接进第一个分类
                if (c == HOME && p.items.isEmpty() && got.classes.isNotEmpty() && cls == HOME && site?.key == s.key && trailS.isEmpty()) cls = got.classes.first().id
            } catch (e: Throwable) {
                p.error = "这个站点打不开：${e.message?.take(70) ?: e.javaClass.simpleName}"
                if (c == HOME) p.classes = emptyList()
                p.end = true
            } finally { p.loading = false }
        }
    }
    // 分类表在首页的结果里：先拉首页，再拉选中的分类
    LaunchedEffect(head) { if (site != null && head.classes == null) load(head, HOME) }
    LaunchedEffect(page) { if (site != null && page !== head && page.items.isEmpty() && !page.end) load(page, folder ?: cls) }

    val grid = rememberLazyGridState()
    var lastKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(site?.key, cls, folder) {
        val k = "${site?.key}|$cls|$folder"
        if (k != lastKey) { if (lastKey.isNotEmpty()) runCatching { grid.scrollToItem(0) }; lastKey = k }
    }
    val size by rememberUpdatedState(page.items.size)
    val moreNow by rememberUpdatedState({ load(page, folder ?: cls) })
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (size > 0 && last >= size - 10) moreNow()
        }
    }

    Row(
        Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 66.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // ---------------- 左：站点 ----------------
        // 从标签栏、右边进来时落在当前选中的站点上，不然落到最上面的「豆瓣找片」，停一下就切过去了
        val selReq = remember { FocusRequester() }
        var selShown by remember { mutableStateOf(false) }
        val sel = Modifier.focusRequester(selReq)
        @Composable fun Shown() = DisposableEffect(Unit) { selShown = true; onDispose { selShown = false } }
        Column(Modifier.width(158.dp).fillMaxHeight().focusProperties { enter = { if (selShown) selReq else FocusRequester.Default } }.focusGroup()) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = Dimens.SafeV)) {
                item(key = DOUBAN) {
                    if (douban) Shown()
                    SiteItem(
                        "豆瓣找片", "榜单 · 筛选", douban,
                        Modifier.remembered(mem, "site:$DOUBAN", isDefault = douban).then(if (douban) sel else Modifier),
                        onFocus = { hovered = DOUBAN },
                    ) { hovered = null; siteKey = DOUBAN }
                }
                item(key = "\u0000head") {
                    T(
                        if (list == null) "片源里的站点" else "片源里的站点 · ${list.size}", 9.sp, Palette.T3, FontWeight.SemiBold,
                        lines = 2, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                if (list == null) item(key = "\u0000wait") {
                    T(err ?: "正在加载…", 10.sp, Palette.T3, lines = 4, modifier = Modifier.padding(start = 10.dp))
                } else items(list, key = { it.key }) { s ->
                    val on = s.key == site?.key
                    if (on) Shown()
                    SiteItem(
                        SourceSearch.siteLabel(s.name), s.notVideo ?: if (!s.searchable) "只能逛，不能搜" else "", on,
                        Modifier.remembered(mem, "site:${s.key}", isDefault = on).then(if (on) sel else Modifier),
                        onFocus = { hovered = s.key },
                    ) { hovered = null; siteKey = s.key }
                }
            }
        }
        // ---------------- 右：分类 + 海报 ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (site == null) FindScreen(mem, embedded = true)
            else {
                // 第一排海报按上：系统的方向查找会越过分类这一排直接跳到标签栏，这里自己指过去
                val clsReq = remember { FocusRequester() }
                val backReq = remember { FocusRequester() }
                var posterIdx by remember { mutableStateOf(-1) }
                val classes = head.classes.orEmpty()
                LazyRow(
                    Modifier.fillMaxWidth().height(30.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    item(key = HOME) {
                        Chip("首页", cls == HOME, { cls = HOME }, Modifier.remembered(mem, "cls:$HOME").then(if (cls == HOME) Modifier.focusRequester(clsReq) else Modifier), size = 11.sp, height = 22.dp, idleBg = Color.White.copy(alpha = 0.08f))
                    }
                    items(classes, key = { "c:" + it.id }) { c ->
                        Chip(c.name, cls == c.id, { cls = c.id }, Modifier.remembered(mem, "cls:${c.id}").then(if (cls == c.id) Modifier.focusRequester(clsReq) else Modifier), size = 11.sp, height = 22.dp, idleBg = Color.White.copy(alpha = 0.08f))
                    }
                }
                if (trail.isNotEmpty()) Row(
                    Modifier.padding(start = 4.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("‹ 返回上一层", false, { popFolder() }, Modifier.focusRequester(backReq), size = 10.5.sp, height = 22.dp, idleBg = Color.White.copy(alpha = 0.08f))
                    T(trail.joinToString(" › ") { it.second }, 10.sp, Palette.T2)
                }
                val status = when {
                    page.error != null && page.items.isEmpty() -> page.error!!
                    page.loading && page.items.isEmpty() -> "正在打开「${SourceSearch.siteLabel(site.name)}」…"
                    page.items.isEmpty() && page.end -> if (cls == HOME && folder == null && classes.isNotEmpty()) "首页没有推荐，选一个分类看看" else "这一栏是空的"
                    else -> ""
                }
                if (status.isNotEmpty()) T(status, 11.sp, if (page.error != null) Palette.Bad else Palette.T3, lines = 3, modifier = Modifier.padding(start = 6.dp, top = 24.dp))
                else TopAlignScroll(8.dp) {
                    val ratio = page.ratio ?: (2f / 3f)
                    val base = Prefs.cols(6)
                    // 横版海报一排少放几张
                    val cols = if (ratio >= 1.2f) maxOf(2, (base * 2 + 1) / 3) else base
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        state = grid,
                        modifier = Modifier.padding(top = 4.dp).fillMaxHeight().onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionUp || posterIdx !in 0 until cols) false
                            else runCatching { (if (trail.isNotEmpty()) backReq else clsReq).requestFocus() }.isSuccess
                        },
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = Dimens.SafeV),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(page.items, key = { _, it -> it.id }) { i, t ->
                            val dir = page.folders[t.id]
                            PosterCell(
                                t,
                                focusModifier = Modifier.remembered(mem, "p:${t.id}"),
                                onFocus = { posterIdx = i },
                                onClick = {
                                    if (dir != null) trailS = (trail + (dir to t.name.replace('\n', ' '))).joinToString("\n") { "${it.first}\u0001${it.second}" }
                                    else { Catalog.putSource(listOf(t)); nav.openDetail(t.id) }
                                },
                                onMenu = if (dir != null) null else ({ Catalog.putSource(listOf(t)); nav.posterMenu(t) }),
                                sub = t.oneLiner.substringAfter(" · ", "").ifEmpty { t.genres.firstOrNull().orEmpty() },
                                badge = if (dir != null) "文件夹" else null,
                                ratio = ratio,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteItem(label: String, sub: String, on: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(onClick, modifier.fillMaxWidth(), corner = 8.dp, scale = 1.02f, ring = false, onFocus = onFocus) { f ->
        val fg = when { f -> Palette.Bg; on -> Palette.Douban; else -> Palette.T1 }
        Column(
            Modifier.fillMaxWidth().height(36.dp)
                .background(if (f) Color.White else if (on) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            T(label, 11.5.sp, fg, if (on || f) FontWeight.SemiBold else FontWeight.Normal)
            if (sub.isNotEmpty()) T(sub, 8.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
        }
    }
}
