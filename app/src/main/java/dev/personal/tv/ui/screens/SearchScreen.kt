package dev.personal.tv.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import dev.personal.tv.data.SearchIndex
import dev.personal.tv.source.SourceSearch
import dev.personal.tv.source.Sources
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.platform.LocalContext
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.PosterCell
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.TopAlignScroll
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

private val KEYS = ('A'..'Z').map { it.toString() } + listOf("1", "2", "3", "4")

/**
 * 搜索：遥控器拼音首字母键盘为主（在本地片库索引里找，一万多部）；
 * 用系统输入法打中文 / 英文时再查豆瓣联想，覆盖全部豆瓣。
 */
@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val mem = nav.mem(Route.Search.key)
    var q by rememberSaveable { mutableStateOf("") }
    /** 输入法模式：顶上的输入框变成真的文本框，弹系统输入法 */
    var ime by rememberSaveable { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<dev.personal.tv.data.Title>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val remote = ime || q.any { it.code > 0x7F }
    // 片源里搜（豆瓣上没有的）：srcQ 不为空就开始搜；豆瓣一部都没搜到时自动搜，有结果时在最后放按钮
    var srcQ by remember { mutableStateOf<String?>(null) }
    var src by remember { mutableStateOf<List<dev.personal.tv.data.Title>>(emptyList()) }
    var srcProgress by remember { mutableStateOf(0 to 0) }
    var srcDone by remember { mutableStateOf(false) }
    // 索引边建边搜：每多两千部重新搜一次
    LaunchedEffect(q, remote, SearchIndex.size / 2000) {
        if (srcQ != q.trim()) { srcQ = null; src = emptyList() }
        if (q.isBlank()) { found = emptyList(); return@LaunchedEffect }
        searching = true
        delay(if (remote) 350 else 120)
        found = Catalog.search(q.trim(), remote)
        searching = false
        if (remote && found.isEmpty() && !Sources.empty && srcQ == null) srcQ = q.trim()
    }
    val ctx = LocalContext.current
    LaunchedEffect(srcQ) {
        val sq = srcQ ?: return@LaunchedEffect
        srcDone = false; src = emptyList(); srcProgress = 0 to 0
        SourceSearch.search(ctx, sq, found.filter { !it.fromSource }) { list, n, total -> src = list; srcProgress = n to total }
        srcDone = true
    }
    val results = if (q.isEmpty()) Catalog.hot().take(10) else found
    val field = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    BackHandler(enabled = ime) { ime = false; kb?.hide() }
    LaunchedEffect(ime) { if (ime) { delay(80); runCatching { field.requestFocus() }; kb?.show() } }

    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(Color(0x5936545E), Color.Transparent), Offset(size.width * 0.8f, 0f), size.width * 0.55f))
        },
    ) {
        // ------------- 左：输入 -------------
        Column(Modifier.padding(start = Dimens.SafeH, top = 36.dp).width(300.dp)) {
            Row(
                Modifier.fillMaxWidth().height(50.dp).background(Palette.Ghost8, RoundedCornerShape(11.dp)).padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(17.dp), tint = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(9.dp))
                if (ime) {
                    BasicTextField(
                        value = q, onValueChange = { q = it.take(30) },
                        modifier = Modifier.weight(1f).focusRequester(field),
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                        decorationBox = { inner ->
                            Box { if (q.isEmpty()) T("中文、英文片名都行", 13.sp, Palette.T3); inner() }
                        },
                    )
                } else {
                    if (q.isEmpty()) T("片名首字母", 13.sp, Palette.T3) else T(q, 22.sp, weight = FontWeight.Bold, spacing = 0.12.em)
                    Cursor()
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                KEYS.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { k ->
                            Key(k, Modifier.weight(1f).remembered(mem, "k:$k", isDefault = k == "A")) { if (ime) { ime = false; q = "" }; if (q.length < 8) q += k }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Key("空格", Modifier.weight(1f).remembered(mem, "k:space"), 11.sp) { if (q.isNotEmpty() && q.length < 8) q += " " }
                    Key("删除", Modifier.weight(1f).remembered(mem, "k:del"), 11.sp) { q = q.dropLast(1) }
                    Key("清空", Modifier.weight(1f).remembered(mem, "k:clear"), 11.sp) { q = ""; ime = false }
                }
            }
            Focusable(
                { if (!ime) { q = ""; ime = true } else { runCatching { field.requestFocus() }; kb?.show() } },
                Modifier.padding(top = 13.dp).fillMaxWidth().remembered(mem, "k:ime"),
                corner = 9.dp, scale = 1.04f, ring = false,
            ) { f ->
                Column(
                    Modifier.fillMaxWidth().background(if (f) Color.White else Color.White.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    T("用输入法打字", 11.5.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold)
                    T("中文、英文片名，查全部豆瓣", 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
                }
            }
        }

        // ------------- 右：结果 -------------
        Column(Modifier.padding(start = 388.dp, end = Dimens.SafeH, top = 36.dp)) {
            if (q.isEmpty() && Prefs.searched.isNotEmpty()) History(mem)
            Row(Modifier.height(50.dp).padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                T(if (q.isEmpty()) "大家最近在搜" else "「${q.trim()}」的结果", 17.sp, weight = FontWeight.Bold)
                val building = SearchIndex.progress?.let { " · 片库索引中 $it" }.orEmpty()
                when {
                    q.isEmpty() -> T("豆瓣最近热门 · 片库 ${SearchIndex.size} 部$building", 10.5.sp, Palette.T3)
                    searching && results.isEmpty() -> T("搜索中…", 10.5.sp, Palette.T3)
                    results.isEmpty() && remote && srcQ != null -> T("豆瓣上没找到" + if (srcDone) "" else "，正在片源里搜…", 10.5.sp, Palette.T3)
                    results.isEmpty() && remote -> T("豆瓣上也没找到，换个说法试试", 10.5.sp, Palette.T3)
                    results.isEmpty() -> T("片库 ${SearchIndex.size} 部里没找到$building。试试「用输入法打字」", 10.5.sp, Palette.T3)
                    else -> T("${results.size} 部" + if (remote) " · 含豆瓣联想" else " · 在 ${SearchIndex.size} 部片库里找$building", 10.5.sp, Palette.T3)
                }
            }
            TopAlignScroll(8.dp) {
                LazyVerticalGrid(
                    GridCells.Fixed(Prefs.cols(5)),
                    Modifier.padding(top = 3.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = Dimens.SafeV, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(17.dp),
                ) {
                    items(results, key = { it.id }) { t ->
                        PosterCell(
                            t, focusModifier = Modifier.remembered(mem, "r:${t.id}"),
                            onClick = { if (q.isNotBlank()) Prefs.addSearched(t.id, t.name); nav.openDetail(t.id) }, onMenu = { nav.posterMenu(t) },
                            sub = listOf(if (t.year > 0) "${t.year}" else "", t.kind.label, t.region.takeIf { it != "中国大陆" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
                            starScore = true,
                            badge = if (t.lines > 0) "${t.lines} 线路" else null,
                        )
                    }
                    if (searching && results.size < 5) items(3) { GhostCell() }
                    // ---- 片源里的（豆瓣上没有的）----
                    if (remote && q.isNotBlank() && !searching && !Sources.empty) {
                        val started = srcQ != null
                        val ids = results.map { it.id }.toSet()
                        val more = src.filter { it.id !in ids }
                        // 按钮和搜的进度是同一行：按下去以后焦点还在这一行上，不会掉回输入框
                        item(key = "src:go", span = { GridItemSpan(maxLineSpan) }) {
                            val (n, total) = srcProgress
                            SrcButton(
                                if (!started) "在片源里搜「${q.trim()}」" else "片源里的「${q.trim()}」",
                                when {
                                    !started -> "找豆瓣上没有、站点里有的片子（短剧、冷门的），要等十几秒"
                                    !srcDone -> "豆瓣上没有、站点里有的 · 搜了 $n / $total 个站点…"
                                    more.isEmpty() -> if (results.isEmpty()) "搜了 $n 个站点，片源里也没有" else "搜了 $n 个站点，没有豆瓣以外的"
                                    else -> "豆瓣上没有、站点里有的 ${more.size} 部 · 没有豆瓣评分"
                                },
                                Modifier.remembered(mem, "src:go"),
                            ) { if (!started) srcQ = q.trim() }
                        }
                        if (started) {
                            items(more, key = { it.id }) { t ->
                                PosterCell(
                                    t, focusModifier = Modifier.remembered(mem, "r:${t.id}"),
                                    onClick = { Catalog.putSource(listOf(t)); Prefs.addSearched(t.id, t.name); nav.openDetail(t.id) },
                                    sub = listOf(if (t.year > 0) "${t.year}" else "", t.genres.firstOrNull() ?: t.kind.label).filter { it.isNotEmpty() }.joinToString(" · "),
                                    badge = "片源",
                                )
                            }
                            if (!srcDone && more.size < 5) items(3) { GhostCell() }
                        }
                    }
                }
            }
        }
    }
}

/** 最近搜过：OK 打开，≡ 删掉这一条 */
@Composable
private fun History(mem: dev.personal.tv.ui.components.FocusMemory) {
    val nav = LocalNav.current
    Row(Modifier.height(50.dp).padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        T("最近搜过", 17.sp, weight = FontWeight.Bold)
        T("OK 打开 · ≡ 删掉这条", 10.5.sp, Palette.T3)
    }
    LazyRow(
        Modifier.padding(top = 4.dp, bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(Prefs.searched, key = { it.id }) { h ->
            Focusable(
                onClick = { Prefs.addSearched(h.id, h.name); nav.openDetail(h.id) },
                modifier = Modifier.remembered(mem, "h:${h.id}"),
                corner = 18.dp, scale = 1.06f, ring = false,
                onMenu = { Prefs.removeSearched(h.id) },
            ) { f ->
                T(
                    h.name, 12.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold,
                    Modifier.background(if (f) Color.White else Palette.Ghost8, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item(key = "clear") {
            Focusable(
                onClick = { Prefs.clearSearched(); nav.toast("搜索记录清空了") },
                modifier = Modifier.remembered(mem, "h:clear"),
                corner = 18.dp, scale = 1.06f, ring = false,
            ) { f ->
                T(
                    "清空", 12.sp, if (f) Palette.Bg else Palette.T3, FontWeight.SemiBold,
                    Modifier.background(if (f) Color.White else Color.Transparent, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 「在片源里搜」：豆瓣有结果时放在最后，按了才搜（几十个站点一起搜要十几秒） */
@Composable
private fun SrcButton(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 6.dp).fillMaxWidth(), corner = 9.dp, scale = 1.02f, ring = false) { f ->
        Column(
            Modifier.fillMaxWidth().background(if (f) Color.White else Color.White.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            T(title, 12.5.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold)
            T(sub, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, size: androidx.compose.ui.unit.TextUnit = 14.sp, onClick: () -> Unit) {
    Focusable(onClick, modifier, corner = 7.dp, scale = 1.1f, ring = false) { f ->
        Box(Modifier.fillMaxWidth().height(35.dp).background(if (f) Color.White else Palette.Ghost6), contentAlignment = Alignment.Center) {
            T(label, size, if (f) Palette.Bg else Palette.T2, FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Cursor() {
    val a by rememberInfiniteTransition(label = "cursor").animateFloat(
        1f, 0f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "blink",
    )
    Box(Modifier.padding(start = 3.dp).size(1.5.dp, 21.dp).graphicsLayer { alpha = a }.background(Color.White))
}

@Composable
private fun GhostCell() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Palette.Ghost6, RoundedCornerShape(7.dp)))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(9.dp).background(Palette.Ghost6, RoundedCornerShape(3.dp)))
        Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.5f).height(7.dp).background(Palette.Ghost6, RoundedCornerShape(3.dp)))
    }
}
