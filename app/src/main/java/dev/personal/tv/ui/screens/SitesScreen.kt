package dev.personal.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.items
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

/** 一个站点一个分类的列表，存在单例里：切走再回来不重拉 */
private class SitePage {
    val items = mutableStateListOf<Title>()
    var classes by mutableStateOf<List<VodClass>?>(null)
    var page = 0
    var loading by mutableStateOf(false)
    var end by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
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
 * 站点：像影视仓那样直接逛每个站点自己的首页和分类。短剧、B站、动漫、网盘分享这类按豆瓣片名配不上的内容在这里看；
 * 点开以后线路用这个站点自己的分集（见 [dev.personal.tv.source.Lines]），不按片名去别的站配。
 */
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
    var siteKey by rememberSaveable { mutableStateOf<String?>(null) }
    /** 左栏焦点停在哪个站点上：停 0.6 秒才切过去，免得上下翻的时候每个站都去拉一遍 */
    var hovered by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hovered) { val h = hovered ?: return@LaunchedEffect; delay(600); siteKey = h }
    val list = sites
    val site = list?.firstOrNull { it.key == siteKey } ?: list?.firstOrNull()
    var cls by rememberSaveable(site?.key) { mutableStateOf(HOME) }

    val cfgUrl = Sources.url.orEmpty()
    val page = remember(cfgUrl, site?.key, cls) { SitePages.get("$cfgUrl|${site?.key}|$cls") }
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
                android.util.Log.d("Sites", "${s.key} $c p${p.page + 1} · ${got.list.size} 条 · pic=${got.list.firstOrNull()?.pic?.take(160)}")
                val have = p.items.map { it.id }.toHashSet()
                val fresh = got.list.filter { it.id.isNotBlank() && it.name.isNotBlank() }.map { SourceSearch.siteTitle(s, it) }.filter { have.add(it.id) }
                p.items.addAll(fresh)
                p.page++
                if (c == HOME || fresh.isEmpty()) p.end = true
                p.error = null
                // 首页没列表（不少插件只给分类）：直接进第一个分类
                if (c == HOME && p.items.isEmpty() && got.classes.isNotEmpty() && cls == HOME && site?.key == s.key) cls = got.classes.first().id
            } catch (e: Throwable) {
                p.error = "这个站点打不开：${e.message?.take(70) ?: e.javaClass.simpleName}"
                if (c == HOME) p.classes = emptyList()
                p.end = true
            } finally { p.loading = false }
        }
    }
    // 分类表在首页的结果里：先拉首页，再拉选中的分类
    LaunchedEffect(head) { if (site != null && head.classes == null) load(head, HOME) }
    LaunchedEffect(page) { if (site != null && page !== head && page.items.isEmpty() && !page.end) load(page, cls) }

    val grid = rememberLazyGridState()
    var lastKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(site?.key, cls) {
        val k = "${site?.key}|$cls"
        if (k != lastKey) { if (lastKey.isNotEmpty()) runCatching { grid.scrollToItem(0) }; lastKey = k }
    }
    val size by rememberUpdatedState(page.items.size)
    val moreNow by rememberUpdatedState({ load(page, cls) })
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
        Column(Modifier.width(158.dp).fillMaxHeight()) {
            T(if (list == null) "站点" else "站点 · ${list.size}", 10.sp, Palette.T3, FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            when {
                list == null -> T(err ?: "正在加载「${Sources.name}」…", 11.sp, Palette.T3, lines = 4, modifier = Modifier.padding(start = 10.dp))
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = Dimens.SafeV)) {
                    items(list, key = { it.key }) { s ->
                        SiteItem(
                            s, s.key == site?.key,
                            Modifier.remembered(mem, "site:${s.key}", isDefault = s == site),
                            onFocus = { hovered = s.key },
                        ) { hovered = null; siteKey = s.key }
                    }
                }
            }
        }
        // ---------------- 右：分类 + 海报 ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (site != null) {
                val classes = head.classes.orEmpty()
                LazyRow(
                    Modifier.fillMaxWidth().height(30.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    item(key = HOME) {
                        Chip("首页", cls == HOME, { cls = HOME }, Modifier.remembered(mem, "cls:$HOME"), size = 11.sp, height = 22.dp, idleBg = Color.White.copy(alpha = 0.08f))
                    }
                    items(classes, key = { "c:" + it.id }) { c ->
                        Chip(c.name, cls == c.id, { cls = c.id }, Modifier.remembered(mem, "cls:${c.id}"), size = 11.sp, height = 22.dp, idleBg = Color.White.copy(alpha = 0.08f))
                    }
                }
                val status = when {
                    page.error != null && page.items.isEmpty() -> page.error!!
                    page.loading && page.items.isEmpty() -> "正在打开「${SourceSearch.siteLabel(site.name)}」…"
                    page.items.isEmpty() && page.end -> if (cls == HOME && classes.isNotEmpty()) "首页没有推荐，选一个分类看看" else "这一栏是空的"
                    else -> ""
                }
                if (status.isNotEmpty()) T(status, 11.sp, if (page.error != null) Palette.Bad else Palette.T3, lines = 3, modifier = Modifier.padding(start = 6.dp, top = 24.dp))
                else TopAlignScroll(8.dp) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(Prefs.cols(6)),
                        state = grid,
                        modifier = Modifier.padding(top = 4.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = Dimens.SafeV),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(page.items, key = { it.id }) { t ->
                            PosterCell(
                                t,
                                focusModifier = Modifier.remembered(mem, "p:${t.id}"),
                                onClick = { Catalog.putSource(listOf(t)); nav.openDetail(t.id) },
                                onMenu = { Catalog.putSource(listOf(t)); nav.posterMenu(t) },
                                sub = t.oneLiner.substringAfter(" · ", "").ifEmpty { t.genres.firstOrNull().orEmpty() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteItem(s: SiteCfg, on: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(onClick, modifier.fillMaxWidth(), corner = 8.dp, scale = 1.02f, ring = false, onFocus = onFocus) { f ->
        val fg = when { f -> Palette.Bg; on -> Palette.Douban; else -> Palette.T1 }
        Column(
            Modifier.fillMaxWidth().height(36.dp)
                .background(if (f) Color.White else if (on) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            T(SourceSearch.siteLabel(s.name), 11.5.sp, fg, if (on || f) FontWeight.SemiBold else FontWeight.Normal)
            val sub = s.notVideo ?: if (!s.searchable) "只能逛，不能搜" else ""
            if (sub.isNotEmpty()) T(sub, 8.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
        }
    }
}
