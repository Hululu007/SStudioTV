package dev.personal.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Kind
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Chip
import dev.personal.tv.ui.components.DisplayTitle
import dev.personal.tv.ui.components.DoubanTag
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.PosterCell
import dev.personal.tv.ui.components.Stars
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.Toggle
import dev.personal.tv.ui.components.TopAlignScroll
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette

private val KINDS = listOf(Kind.MOVIE, Kind.SERIES, Kind.VARIETY, Kind.ANIME, Kind.DOC)
/** 榜单对应豆瓣选片接口的排序：近期热度 / 高分优先 / 首播时间 / 综合 */
private val LISTS = listOf("近期热门" to "U", "高分" to "S", "新上线" to "R", "综合" to "T")
// 都是豆瓣选片接口认的标签；改成下拉列表后不怕选项多
private val GENRES = listOf(
    "全部类型", "剧情", "喜剧", "爱情", "悬疑", "犯罪", "动作", "科幻", "奇幻", "惊悚",
    "恐怖", "家庭", "古装", "武侠", "历史", "战争", "冒险", "传记", "动画",
)
private val REGIONS = listOf(
    "全部地区", "华语", "欧美", "中国大陆", "中国香港", "中国台湾", "美国", "英国",
    "日本", "韩国", "泰国", "法国", "德国", "西班牙", "印度",
)
private val YEARS = listOf("全部年代", "2026", "2025", "2024", "2023", "2020年代", "2010年代", "2000年代", "90年代", "80年代", "70年代")
private val SCORES = listOf("不限分数" to 0f, "7.5 分以上" to 7.5f, "8 分以上" to 8f, "9 分以上" to 9f)

/** 类别 → 豆瓣大类和固定标签 */
private fun kindQuery(k: Kind): Pair<String, String?> = when (k) {
    Kind.MOVIE -> "movie" to "电影"
    Kind.SERIES -> "tv" to "电视剧"
    Kind.VARIETY -> "tv" to "综艺"
    Kind.ANIME -> "tv" to "动画"
    Kind.DOC -> "tv" to "纪录片"
}

/** 找片：先选类别和榜单，再加筛选；数据是豆瓣选片接口，翻到底自动加载下一页 */
@Composable
fun FindScreen(mem: FocusMemory) {
    var kind by rememberSaveable { mutableIntStateOf(1) }
    var list by rememberSaveable { mutableIntStateOf(0) }
    var genre by rememberSaveable { mutableIntStateOf(0) }
    var region by rememberSaveable { mutableIntStateOf(0) }
    var year by rememberSaveable { mutableIntStateOf(0) }
    var score by rememberSaveable { mutableIntStateOf(0) }

    val filterKey = listOf(kind, list, genre, region, year, score).joinToString()
    // 结果按筛选条件存在单例里：切走再回来不用重新拉
    val page = remember(filterKey) { FindPages.get(filterKey) }
    val (type, kindTag) = kindQuery(KINDS[kind])
    val tags = listOfNotNull(kindTag, GENRES[genre].takeIf { genre > 0 }, REGIONS[region].takeIf { region > 0 }, YEARS[year].takeIf { year > 0 })
    val scope = rememberCoroutineScope()
    fun more() {
        if (page.loading || page.end) return
        page.loading = true
        scope.launch {
            try {
                val got = Catalog.browse(type, tags, LISTS[list].second, page.ids.size, 60, SCORES[score].second)
                val fresh = got.map { it.id }.filter { it !in page.ids }
                page.ids.addAll(fresh)
                if (got.size < 60 || fresh.isEmpty()) page.end = true
                page.error = null
            } catch (e: Throwable) {
                page.error = "豆瓣连不上：${e.message}"
            } finally { page.loading = false }
        }
    }
    LaunchedEffect(filterKey) { if (page.ids.isEmpty()) more() }
    val titles = page.ids.mapNotNull { Catalog.get(it) }.filter { it.id !in Store.hidden }

    val grid = rememberLazyGridState()
    var lastKey by rememberSaveable { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) { if (filterKey != lastKey) { lastKey = filterKey; grid.scrollToItem(0) } }

    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(Brush.radialGradient(listOf(Color(0x3036545E), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, 0f), radius = size.width * 0.6f))
    }) {
        Column(
            Modifier.padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 66.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.background(Palette.Ghost6, RoundedCornerShape(50)).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    KINDS.forEachIndexed { i, k ->
                        Chip(
                            k.label, kind == i, { kind = i }, Modifier.remembered(mem, "kind:$i"),
                            size = 11.5.sp, height = 23.dp,
                        )
                    }
                }
                VLine()
                LISTS.forEachIndexed { i, l ->
                    Chip(l.first, list == i, { list = i }, Modifier.remembered(mem, "list:$i"), idleBg = Color.White.copy(alpha = 0.1f), selectedBg = Color.White.copy(alpha = 0.85f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("筛选", 9.5.sp, Palette.T3, modifier = Modifier.padding(end = 3.dp))
                Dropdown(GENRES, genre, { genre = it }, mem, "genre")
                Dropdown(REGIONS, region, { region = it }, mem, "region")
                Dropdown(YEARS, year, { year = it }, mem, "year")
                Dropdown(SCORES.map { it.first }, score, { score = it }, mem, "score")
                Spacer(Modifier.weight(1f))
                T(
                    when {
                        page.error != null && titles.isEmpty() -> page.error!!
                        page.loading && titles.isEmpty() -> "正在从豆瓣拉…"
                        else -> "豆瓣选片 · ${titles.size}${if (page.end) "" else "+"} 部"
                    },
                    10.sp, Palette.T3,
                )
            }
        }
        PosterGridWithInfo(
            titles, mem, grid, top = 142.dp,
            emptyText = if (page.loading) "" else page.error ?: "没有符合条件的片子，放宽一下筛选试试",
            onMore = ::more,
        )
    }
}

/** 找片每组筛选条件的结果 */
private class FindPage {
    val ids = mutableStateListOf<String>()
    var loading by mutableStateOf(false)
    var end by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private object FindPages {
    private val pages = LinkedHashMap<String, FindPage>()
    fun get(key: String): FindPage = pages.getOrPut(key) {
        if (pages.size > 20) pages.remove(pages.keys.first())
        FindPage()
    }
}

/**
 * 下拉筛选：OK 在胶囊下面弹出全部选项，焦点直接落在当前选中的那项，上下选、OK 确定，返回键关掉。
 * 选项多时分几列摆（每列最多 11 项），一屏看全，不用滚。关掉后焦点回到胶囊上。
 */
@Composable
private fun Dropdown(options: List<String>, value: Int, set: (Int) -> Unit, mem: FocusMemory, key: String) {
    var open by remember { mutableStateOf(false) }
    var wasOpen by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    LaunchedEffect(open) {
        if (!open && wasOpen) { delay(30); runCatching { anchor.requestFocus() } }
        wasOpen = open
    }
    Box {
        Chip(
            options[value], value != 0, { open = true }, Modifier.focusRequester(anchor).remembered(mem, key),
            idleBg = Color.White.copy(alpha = 0.1f), dropdown = true,
        )
        if (open) {
            val dy = with(LocalDensity.current) { 26.dp.roundToPx() }
            Popup(
                offset = IntOffset(0, dy),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                OptionList(options, value) { i -> open = false; if (i != value) set(i) }
            }
        }
    }
}

@Composable
private fun OptionList(options: List<String>, value: Int, pick: (Int) -> Unit) {
    val current = remember { FocusRequester() }
    val cols = (options.size + 10) / 11
    val rows = (options.size + cols - 1) / cols
    Row(
        Modifier.shadow(18.dp, RoundedCornerShape(10.dp))
            .background(Color(0xFF1D2025), RoundedCornerShape(10.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (c in 0 until cols) {
            Column(Modifier.width(118.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                for (i in c * rows until minOf(options.size, (c + 1) * rows)) {
                    val sel = i == value
                    Focusable(
                        { pick(i) },
                        Modifier.fillMaxWidth().then(if (sel) Modifier.focusRequester(current) else Modifier),
                        corner = 6.dp, scale = 1.03f, ring = false,
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth().height(26.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(
                                options[i], 11.sp, if (f) Palette.Bg else if (sel) Palette.T1 else Palette.T2,
                                if (sel || f) FontWeight.SemiBold else FontWeight.Normal, Modifier.weight(1f),
                            )
                            if (sel) T("✓", 11.sp, if (f) Palette.Bg else Palette.Douban, FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(30); runCatching { current.requestFocus() } }
}

@Composable
private fun VLine() = Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 16.dp).background(Color.White.copy(alpha = 0.15f)))

/** 想看：只存在本机 */
@Composable
fun WishScreen(mem: FocusMemory) {
    val titles = Store.wish.mapNotNull { Catalog.get(it) }
    val grid = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = Dimens.SafeH, top = 74.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("想看", 18.sp, weight = FontWeight.Bold)
            T("${titles.size} 部 · 只存在这台电视上，不关联豆瓣账号 · 按 ☰ 移出", 10.sp, Palette.T3)
        }
        PosterGridWithInfo(titles, mem, grid, top = 112.dp, emptyText = "还没有想看的。在任何海报上按菜单键，选「加入想看」")
    }
}

/** 6 列海报网格 + 右侧焦点信息栏，找片和想看共用 */
@Composable
private fun PosterGridWithInfo(titles: List<Title>, mem: FocusMemory, grid: LazyGridState, top: androidx.compose.ui.unit.Dp, emptyText: String, onMore: (() -> Unit)? = null) {
    val nav = LocalNav.current
    if (onMore != null) {
        val size by rememberUpdatedState(titles.size)
        val more by rememberUpdatedState(onMore)
        // 焦点走到倒数两行就加载下一页
        LaunchedEffect(grid) {
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
                if (size > 0 && last >= size - 12) more()
            }
        }
    }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val info = titles.firstOrNull { it.id == focusedId } ?: titles.firstOrNull()

    Box(Modifier.fillMaxSize().padding(top = top)) {
        if (titles.isEmpty()) {
            T(emptyText, 12.sp, Palette.T3, modifier = Modifier.padding(start = Dimens.SafeH, top = 40.dp))
        } else {
            TopAlignScroll(8.dp) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = grid,
                    modifier = Modifier.padding(start = Dimens.SafeH - 8.dp).width(640.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = Dimens.SafeV),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(titles, key = { it.id }) { t ->
                        PosterCell(
                            t,
                            focusModifier = Modifier.remembered(mem, "p:${t.id}", isDefault = t == titles.first()),
                            onClick = { nav.openDetail(t.id) },
                            onMenu = { nav.posterMenu(t) },
                            onFocus = { focusedId = t.id },
                        )
                    }
                }
            }
        }
        if (info != null) InfoPanel(info, Modifier.padding(start = 692.dp, end = Dimens.SafeH, top = 8.dp, bottom = Dimens.SafeV))
    }
}

@Composable
private fun InfoPanel(t: Title, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().background(Palette.Ghost6, RoundedCornerShape(11.dp)).padding(horizontal = 16.dp, vertical = 17.dp),
    ) {
        DisplayTitle(t.name, if (t.name.length > 4) 19.sp else 23.sp)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DoubanTag()
            if (t.score != null) {
                T(t.scoreText!!, 17.sp, Palette.Star, FontWeight.Bold)
                Stars(t.score, 10.sp)
            } else T("暂无评分", 11.sp, Palette.T3)
        }
        T(t.summary, 10.sp, Palette.T2, modifier = Modifier.padding(top = 7.dp))
        T(
            t.genres.joinToString(" / ") + if (t.director.isNotEmpty()) " · 导演 ${t.director}" else "",
            10.sp, Palette.T2, modifier = Modifier.padding(top = 2.dp),
        )
        val about = t.oneLiner.ifEmpty { if (t.cast.isNotEmpty()) "主演 " + t.cast.joinToString(" / ") else "" }
        T(about, 10.5.sp, Palette.T1.copy(alpha = 0.85f), lines = 4, lineHeight = 17.sp, modifier = Modifier.padding(top = 9.dp))
        Box(Modifier.padding(top = 11.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).background(when { t.lines > 0 -> Palette.Douban; t.lines == 0 -> Palette.Bad; else -> Palette.T3 }, CircleShape))
            val wish = if (t.id in Store.wish) " · 已在想看" else ""
            T(
                when {
                    t.lines > 0 -> "${t.lines} 条线路$wish"
                    t.lines == 0 -> "暂无线路$wish"
                    else -> "打开详情后找线路$wish"
                },
                10.sp, Palette.T2,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Hint("OK", "打开详情", 9.5.sp)
            Hint("☰", "找类似 · ${if (t.id in Store.wish) "移出想看" else "加想看"} · 不感兴趣", 9.5.sp)
        }
    }
}
