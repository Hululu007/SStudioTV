package dev.personal.tv.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Store
import dev.personal.tv.source.SiteCfg
import dev.personal.tv.source.SourceSearch
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.screens.FindFilter
import dev.personal.tv.ui.screens.FindPages
import dev.personal.tv.ui.screens.GENRES
import dev.personal.tv.ui.screens.HOME
import dev.personal.tv.ui.screens.KINDS
import dev.personal.tv.ui.screens.LISTS
import dev.personal.tv.ui.screens.REGIONS
import dev.personal.tv.ui.screens.SCORES
import dev.personal.tv.ui.screens.SitePages
import dev.personal.tv.ui.screens.YEARS
import dev.personal.tv.ui.screens.browsable
import dev.personal.tv.ui.screens.load
import dev.personal.tv.ui.theme.Palette

/**
 * 找片：上面切「豆瓣找片」和「片源站点」。
 * 豆瓣：类别、榜单、筛选，翻到底自动加载；站点：选站点、分类，文件夹点进去是下一层，返回键回上一层。
 * 数据和电视的找片共用一份缓存。
 */
@Composable
fun PhoneFind() {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = PC.Edge, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            listOf("豆瓣找片", "片源站点").forEachIndexed { i, s ->
                Tx(
                    s, if (mode == i) 22.sp else 17.sp, if (mode == i) Palette.T1 else Palette.T3, FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { mode = i }.padding(end = 16.dp, bottom = 2.dp),
                )
            }
        }
        if (mode == 0) DoubanFind() else SiteFind()
    }
}

@Composable
private fun DoubanFind() {
    var kind by rememberSaveable { mutableIntStateOf(1) }
    var list by rememberSaveable { mutableIntStateOf(0) }
    var genre by rememberSaveable { mutableIntStateOf(0) }
    var region by rememberSaveable { mutableIntStateOf(0) }
    var year by rememberSaveable { mutableIntStateOf(0) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    val filter = FindFilter(kind, list, genre, region, year, score)
    val page = remember(filter.key) { FindPages.get(filter.key) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(filter.key) { if (page.ids.isEmpty()) filter.more(page, scope) }
    val titles = page.ids.mapNotNull { Catalog.get(it) }.filter { it.id !in Store.hidden }
    val grid = rememberLazyGridState()
    var lastKey by rememberSaveable { mutableStateOf(filter.key) }
    LaunchedEffect(filter.key) { if (filter.key != lastKey) { lastKey = filter.key; grid.scrollToItem(0) } }
    LoadMore(grid, titles.size) { filter.more(page, scope) }

    Column(Modifier.fillMaxSize()) {
        PillRow(KINDS.map { it.label }, kind, Modifier.padding(top = 6.dp), strong = true) { kind = it }
        PillRow(LISTS.map { it.first }, list, Modifier.padding(top = 8.dp)) { list = it }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PC.Edge, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Dropdown(GENRES, genre) { genre = it }
            Dropdown(REGIONS, region) { region = it }
            Dropdown(YEARS, year) { year = it }
            Dropdown(SCORES.map { it.first }, score) { score = it }
        }
        Tx(
            when {
                page.error != null && titles.isEmpty() -> page.error!!
                page.loading && titles.isEmpty() -> "正在从豆瓣拉…"
                else -> "豆瓣选片 · ${titles.size}${if (page.end) "" else "+"} 部"
            },
            11.5.sp, Palette.T3, modifier = Modifier.padding(horizontal = PC.Edge),
        )
        if (titles.isEmpty() && !page.loading) Empty(page.error ?: "没有符合条件的片子", "放宽一下筛选试试")
        else PosterGrid(grid) {
            itemsIndexed(titles, key = { _, t -> t.id }) { _, t ->
                val nav = LocalNav.current
                PosterTile(t, onLong = { nav.posterMenu(t) }) { nav.openDetail(t.id) }
            }
        }
    }
}

/** 筛选下拉：胶囊上写着当前选的，点开是菜单 */
@Composable
private fun Dropdown(options: List<String>, value: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(options[value], value != 0, dropdown = true) { open = true }
        DropdownMenu(open, { open = false }, containerColor = PC.Card2) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o, color = if (i == value) Palette.Douban else Palette.T1, fontWeight = if (i == value) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; if (i != value) set(i) },
                )
            }
        }
    }
}

/** 滑到倒数两行附近就加载下一页 */
@Composable
fun LoadMore(grid: LazyGridState, size: Int, more: () -> Unit) {
    val n by rememberUpdatedState(size)
    val m by rememberUpdatedState(more)
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (n > 0 && last >= n - 9) m()
        }
    }
}

/** 海报网格（下面留出手势条的空） */
@Composable
fun PosterGrid(
    grid: LazyGridState,
    cols: GridCells = PosterCols,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        cols, state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PC.Edge, end = PC.Edge, top = 10.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** 逛片源里的站点 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteFind() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<SiteCfg>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Sources.url) {
        if (Sources.empty) { err = "还没有片源"; sites = null; return@LaunchedEffect }
        err = null
        try { sites = Sources.config().sites.filter(::browsable) } catch (e: Throwable) { err = "片源配置加载失败：${e.message?.take(60)}" }
    }
    val list = sites
    if (list == null) {
        if (err != null) Empty(err!!, if (Sources.empty) "先加一个影视仓 / TVBox 配置地址" else "", button = if (Sources.empty) "添加片源" else null) { nav.openSources() }
        else CenterNote("正在加载片源…")
        return
    }
    if (list.isEmpty()) { Empty("这个片源里没有能逛的站点"); return }
    var siteKey by rememberSaveable { mutableStateOf<String?>(null) }
    val site = list.firstOrNull { it.key == siteKey } ?: list.first()
    var picking by remember { mutableStateOf(false) }
    var cls by rememberSaveable(site.key) { mutableStateOf(HOME) }
    var trailS by rememberSaveable(site.key, cls) { mutableStateOf("") }
    val trail = trailS.split('\n').filter { it.isNotEmpty() }.map { it.substringBefore('\u0001') to it.substringAfter('\u0001') }
    val folder = trail.lastOrNull()?.first
    fun popFolder() { trailS = trail.dropLast(1).joinToString("\n") { "${it.first}\u0001${it.second}" } }
    BackHandler(enabled = trail.isNotEmpty()) { popFolder() }

    val cfgUrl = Sources.url.orEmpty()
    val page = remember(cfgUrl, site.key, cls, folder) { SitePages.get("$cfgUrl|${site.key}|$cls" + (folder?.let { "|f:$it" } ?: "")) }
    val head = remember(cfgUrl, site.key) { SitePages.get("$cfgUrl|${site.key}|$HOME") }
    fun load(p: dev.personal.tv.ui.screens.SitePage, c: String) =
        p.load(ctx, scope, site, c) { first -> if (cls == HOME && trailS.isEmpty() && siteKey.let { it == null || it == site.key }) cls = first }
    LaunchedEffect(head) { if (head.classes == null) load(head, HOME) }
    LaunchedEffect(page) { if (page !== head && page.items.isEmpty() && !page.end) load(page, folder ?: cls) }
    val grid = rememberLazyGridState()
    var lastKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(site.key, cls, folder) {
        val k = "${site.key}|$cls|$folder"
        if (k != lastKey) { if (lastKey.isNotEmpty()) runCatching { grid.scrollToItem(0) }; lastKey = k }
    }
    LoadMore(grid, page.items.size) { load(page, folder ?: cls) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = PC.Edge, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(SourceSearch.siteLabel(site.name), true, dropdown = true, strong = true) { picking = true }
            Tx("${list.size} 个站点 · 「${Sources.name}」", 11.5.sp, Palette.T3, modifier = Modifier.padding(start = 10.dp))
        }
        val classes = head.classes.orEmpty()
        if (classes.isNotEmpty()) {
            val ids = listOf(HOME) + classes.map { it.id }
            val names = listOf("首页") + classes.map { it.name }
            PillRow(names, ids.indexOf(cls).coerceAtLeast(0), Modifier.padding(top = 2.dp)) { cls = ids[it] }
        }
        if (trail.isNotEmpty()) {
            Row(Modifier.padding(start = PC.Edge, end = PC.Edge, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("‹ 上一层", false) { popFolder() }
                Tx(trail.joinToString(" / ") { it.second }, 12.sp, Palette.T2, modifier = Modifier.padding(start = 10.dp))
            }
        }
        val ratio = page.ratio ?: 2f / 3f
        when {
            page.items.isEmpty() && page.loading -> CenterNote("正在打开站点…")
            page.items.isEmpty() && page.error != null -> Empty("这个站点打不开", page.error!!)
            page.items.isEmpty() && page.end -> Empty("这一栏是空的", "换个分类看看")
            else -> PosterGrid(grid, if (ratio >= 1.2f) GridCells.Adaptive(160.dp) else PosterCols) {
                itemsIndexed(page.items, key = { _, t -> t.id }) { _, t ->
                    val fid = page.folders[t.id]
                    PosterTile(
                        t, ratio = ratio, badge = if (fid != null) "文件夹" else null,
                        sub = t.year.takeIf { it > 0 }?.toString().orEmpty(),
                        onLong = if (fid == null) ({ nav.posterMenu(t) }) else null,
                    ) {
                        if (fid != null) trailS = (trail + (fid to t.name)).joinToString("\n") { "${it.first}\u0001${it.second}" }
                        else nav.openDetail(t.id)
                    }
                }
            }
        }
    }

    if (picking) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet({ picking = false }, sheetState = state, containerColor = PC.Card) {
            Tx("选站点", 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(list, key = { it.key }) { s ->
                    val on = s.key == site.key
                    Row(
                        Modifier.fillMaxWidth().clickable { siteKey = s.key; picking = false }
                            .background(if (on) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tx(SourceSearch.siteLabel(s.name), 15.sp, if (on) Palette.Douban else Palette.T1, if (on) FontWeight.SemiBold else FontWeight.Normal, Modifier.weight(1f))
                        val note = s.notVideo ?: if (!s.searchable) "只能逛，不能搜" else if (Prefs.rank(s.key) != null) "优先" else ""
                        if (note.isNotEmpty()) Tx(note, 11.5.sp, Palette.T3)
                    }
                }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}
