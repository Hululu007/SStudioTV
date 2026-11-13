package dev.personal.tv.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Title
import dev.personal.tv.source.SourceSearch
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.PosterArt
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * 搜索：输入法打字，豆瓣联想 + 本机片库一起搜；豆瓣上没有的（短剧、B站、网盘分享）再到片源的各个站点里搜。
 * 没输字时显示搜索记录和豆瓣热门。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneSearch() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Title>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var srcQ by remember { mutableStateOf<String?>(null) }
    var src by remember { mutableStateOf<List<Title>>(emptyList()) }
    var srcProgress by remember { mutableStateOf(0 to 0) }
    var srcDone by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (srcQ != q.trim()) { srcQ = null; src = emptyList() }
        if (q.isBlank()) { found = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(350)
        found = runCatching { Catalog.search(q.trim(), remote = true) }.getOrDefault(emptyList())
        searching = false
        if (found.isEmpty() && !Sources.empty && srcQ == null) srcQ = q.trim()
    }
    LaunchedEffect(srcQ) {
        val sq = srcQ ?: return@LaunchedEffect
        srcDone = false; src = emptyList(); srcProgress = 0 to 0
        SourceSearch.search(ctx, sq, found.filter { !it.fromSource }) { list, n, total -> src = list; srcProgress = n to total }
        srcDone = true
    }
    val field = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(120); runCatching { field.requestFocus() }; kb?.show() }

    fun open(t: Title) {
        kb?.hide()
        if (t.fromSource) Catalog.putSource(listOf(t))
        Prefs.addSearched(t.id, t.name)
        nav.openDetail(t.id)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(end = PC.Edge), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable { kb?.hide(); nav.back() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Palette.T1)
            }
            Row(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = Palette.T3)
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (q.isEmpty()) Tx("片名、演员、拼音首字母", 15.sp, Palette.T3)
                    BasicTextField(
                        q, { q = it }, Modifier.fillMaxWidth().focusRequester(field),
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.T1, fontSize = 15.sp),
                        cursorBrush = SolidColor(Palette.Douban),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                    )
                }
                if (q.isNotEmpty()) {
                    Icon(Icons.Filled.Close, "清空", Modifier.size(20.dp).clip(RoundedCornerShape(50)).clickable { q = "" }, tint = Palette.T2)
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (q.isBlank()) {
                if (Prefs.searched.isNotEmpty()) {
                    item("hh") { Section("搜索记录", modifier = Modifier.padding(top = 4.dp), action = "清空", onAction = { Prefs.clearSearched() }) }
                    item("h") {
                        FlowRow(Modifier.padding(horizontal = PC.Edge, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Prefs.searched.take(20).forEach { h -> Pill(h.name, false) { Prefs.addSearched(h.id, h.name); nav.openDetail(h.id) } }
                        }
                    }
                }
                val hot = Catalog.hot().take(12)
                if (hot.isNotEmpty()) {
                    item("th") { Section("大家在看", "豆瓣最近热门", Modifier.padding(top = 12.dp)) }
                    itemsIndexed(hot, key = { _, t -> "t:" + t.id }) { i, t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open(t) }.padding(horizontal = PC.Edge, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tx("${i + 1}", 15.sp, if (i < 3) Palette.Star else Palette.T3, FontWeight.Bold, Modifier.width(28.dp))
                            Tx(t.name, 15.sp, modifier = Modifier.weight(1f))
                            t.scoreText?.let { Tx(it, 13.sp, Palette.Star, FontWeight.SemiBold) }
                        }
                    }
                }
            } else {
                item("st") {
                    Tx(
                        when {
                            searching && found.isEmpty() -> "正在搜…"
                            found.isEmpty() -> "豆瓣和片库里都没找到"
                            else -> "豆瓣 · ${found.size} 部"
                        },
                        12.sp, Palette.T3, modifier = Modifier.padding(horizontal = PC.Edge, vertical = 6.dp),
                    )
                }
                itemsIndexed(found, key = { _, t -> "f:" + t.id }) { _, t -> ResultRow(t, { nav.posterMenu(t) }) { open(t) } }
                if (!Sources.empty) {
                    item("sh") {
                        Column(Modifier.padding(top = 14.dp)) {
                            val (n, total) = srcProgress
                            Section(
                                "片源里的", when {
                                    srcQ == null -> "豆瓣上没有的短剧、B站、网盘分享在这里搜"
                                    !srcDone -> "已搜 $n/$total 个站点"
                                    src.isEmpty() -> "$total 个站点里都没有"
                                    else -> "${src.size} 部 · $total 个站点"
                                },
                            )
                            if (srcQ == null) {
                                BigButton(Modifier.padding(horizontal = PC.Edge, vertical = 6.dp), height = 40.dp, onClick = { srcQ = q.trim() }) {
                                    Tx("到「${Sources.name}」的站点里搜", 14.sp, it, FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    itemsIndexed(src, key = { _, t -> "s:" + t.id }) { _, t ->
                        ResultRow(t, null, sub = t.oneLiner.ifEmpty { null }) { open(t) }
                    }
                }
            }
        }
    }
}

/** 搜索结果一行：小海报 + 片名、概要、评分 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(t: Title, onLong: (() -> Unit)?, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = PC.Edge, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterArt(t, 9.sp, Modifier.size(60.dp, 86.dp).clip(RoundedCornerShape(6.dp)), bottomPadding = 4.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Tx(t.name, 15.5.sp, weight = FontWeight.SemiBold)
            Tx(t.summary, 12.sp, Palette.T2, modifier = Modifier.padding(top = 3.dp))
            val extra = sub ?: listOf(t.region, t.genres.take(3).joinToString(" / "), t.director.takeIf { it.isNotEmpty() }?.let { "导演 $it" }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" · ")
            if (extra.isNotEmpty()) Tx(extra, 11.5.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        t.scoreText?.let { Tx(it, 15.sp, Palette.Star, FontWeight.Bold) }
    }
}
