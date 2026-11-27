package dev.personal.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.personal.tv.data.Title
import dev.personal.tv.source.Eps
import dev.personal.tv.source.LineSet
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PlayLine
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.theme.Palette
import dev.personal.tv.ui.theme.TvScale
import kotlinx.coroutines.delay

/**
 * 详情页「线路」浏览：左边是线路，焦点停在哪条，右边就列出这条线路里真实的分集名字，
 * 并标出当前目标（继续看的那一集）在这条线路里有没有。只是预览：不创建播放器、不改进度和「选集用哪套名字」。
 * 在某一集上按 OK = 就用这条线路播这一集。
 *
 * [target]：当前目标（序号, 名字）。[startKey] / [startEp]：上次在浏览器里停的线路和分集，没有就落在当前线路 / 目标集。
 * [onFocusLine] / [onFocusEp]：焦点变了通知详情页记下来，下次打开接着停在这里。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LineBrowser(
    t: Title,
    set: LineSet?,
    current: PlayLine?,
    target: Pair<Int, String?>,
    startKey: String?,
    startEp: String?,
    onFocusLine: (String) -> Unit,
    onFocusEp: (String) -> Unit,
    onClose: () -> Unit,
    onPlay: (line: PlayLine, ep: Int, epName: String?) -> Unit,
) {
    // 线路是陆续搜到的：已有的保持原来的顺序，新搜到的接在后面，焦点底下的行不会被挤走
    val keys = remember { mutableStateListOf<String>().also { ks -> set?.let { s -> ks += Lines.ordered(s, current?.key, emptyList()).map { it.key } } } }
    LaunchedEffect(set?.lines?.size) {
        if (set == null) return@LaunchedEffect
        Lines.ordered(set, current?.key, emptyList()).forEach { if (it.key !in keys) keys.add(it.key) }
    }
    val lines = keys.mapNotNull { k -> set?.lines?.firstOrNull { it.key == k } }
    val initial = listOf(startKey, current?.key).firstOrNull { k -> k != null && lines.any { it.key == k } } ?: lines.firstOrNull()?.key
    var shown by remember { mutableStateOf(initial) }
    val line = lines.firstOrNull { it.key == shown } ?: lines.firstOrNull()
    val rowReq = remember { FocusRequester() }
    val epReq = remember { FocusRequester() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { TvScale {
        Row(Modifier.fillMaxSize().background(Color(0xF5101318)).padding(start = 40.dp, top = 28.dp, bottom = 24.dp, end = 24.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                T(t.name, 17.sp, weight = FontWeight.Bold, serif = true, lines = 1)
                T("线路 · ${lines.size} 条" + if (set != null && !set.done) " · 还在找…" else "", 10.5.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                if (lines.isEmpty()) {
                    T("还没有线路", 11.sp, Palette.T3)
                } else {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (lines.indexOfFirst { it.key == initial } - 2).coerceAtLeast(0))
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(lines, key = { it.key }) { l ->
                            val idx = targetIndex(t, l, target)
                            val failed = set?.failed?.get(l.key)
                            Focusable(
                                { runCatching { epReq.requestFocus() } },
                                Modifier.fillMaxWidth().then(if (l.key == initial) Modifier.focusRequester(rowReq) else Modifier),
                                corner = 9.dp, scale = 1.02f, ring = false,
                                onFocus = { shown = l.key; onFocusLine(l.key) },
                            ) { f ->
                                Column(
                                    Modifier.fillMaxWidth().background(if (f) Color.White else if (l.key == shown) Palette.Ghost else Color.Transparent)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                ) {
                                    val fg = if (f) Palette.Bg else Palette.T1
                                    val sub = if (f) Palette.Bg.copy(alpha = 0.65f) else Palette.T3
                                    T(
                                        (if (l.key == current?.key) "● " else "") + "${l.siteName} · ${l.flag}", 12.sp, fg, FontWeight.SemiBold, lines = 1,
                                    )
                                    T("${l.episodes.size} 个 · " + availability(t, idx, target, failed), 9.5.sp, sub, lines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(start = 28.dp).fillMaxHeight().weight(1f)) {
                if (line == null) {
                    T("线路搜到后在这里看分集", 11.sp, Palette.T3)
                } else {
                    androidx.compose.runtime.key(line.key) { EpisodePane(t, line, set, target, startEp, epReq, onFocusEp, onPlay) }
                }
            }
        }
    } }
    LaunchedEffect(Unit) { delay(60); withFrameNanos { }; runCatching { rowReq.requestFocus() } }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EpisodePane(
    t: Title, line: PlayLine, set: LineSet?, target: Pair<Int, String?>, startEp: String?, entryReq: FocusRequester,
    onFocusEp: (String) -> Unit, onPlay: (PlayLine, Int, String?) -> Unit,
) {
    val eps = line.episodes
    val numeric = Eps.numeric(eps, t.name, t.isVariety)
    val tIdx = targetIndex(t, line, target)
    // 上次在这条线路里停的那一集（名字#第几个同名）> 目标集 > 第一个
    val occ = remember(eps) { val seen = HashMap<String, Int>(); eps.map { e -> seen.merge(e.name, 1, Int::plus)!! } }
    fun key(i: Int) = "${line.key}|${eps[i].name}#${occ[i]}"
    val entry = (startEp?.let { k -> eps.indices.firstOrNull { key(it) == k } } ?: tIdx).coerceAtLeast(0).coerceAtMost((eps.size - 1).coerceAtLeast(0))
    var focusedName by remember(line.key) { mutableStateOf<String?>(null) }

    T("${line.siteName} · ${line.flag}", 15.sp, weight = FontWeight.Bold, lines = 1)
    T(
        listOf(line.vodName.takeIf { it.isNotEmpty() && it != t.name }, line.remarks.takeIf { it.isNotEmpty() }, "共 ${eps.size} 个")
            .filterNotNull().joinToString(" · "),
        10.sp, Palette.T3, lines = 1, modifier = Modifier.padding(top = 2.dp),
    )
    val failed = set?.failed?.get(line.key)
    T(
        availability(t, tIdx, target, failed), 10.5.sp,
        if (tIdx >= 0 && failed == null) Palette.T2 else Palette.Star, FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    if (eps.isEmpty()) {
        T("这条线路里没有分集", 11.sp, Palette.T3)
        return
    }
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (entry - 6).coerceAtLeast(0).let { it - it % 4 })
    var entryAttached by remember(line.key, entry) { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (numeric) 64.dp else 130.dp),
        state = state,
        modifier = Modifier.fillMaxWidth().weight(1f)
            .focusProperties { this.enter = { if (entryAttached) entryReq else FocusRequester.Default } }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        itemsIndexed(eps, key = { i, _ -> key(i) }) { i, e ->
            if (i == entry) {
                androidx.compose.runtime.DisposableEffect(line.key, entry) {
                    entryAttached = true
                    onDispose { entryAttached = false }
                }
            }
            val isTarget = i == tIdx
            Focusable(
                {
                    // 同一套规则：按序号的线路传序号（名字 null），按名字的线路传位置 + 名字
                    onPlay(line, i + 1, e.name)
                },
                Modifier.fillMaxWidth().height(34.dp).then(if (i == entry) Modifier.focusRequester(entryReq) else Modifier),
                corner = 6.dp,
                onFocus = { onFocusEp(key(i)); focusedName = Eps.fullLabel(e.name, t.name) },
            ) { f ->
                Box(
                    Modifier.fillMaxSize().background(if (f) Color.White.copy(alpha = 0.22f) else if (isTarget) Color.White.copy(alpha = 0.16f) else Palette.Ghost8),
                    contentAlignment = Alignment.Center,
                ) {
                    T(
                        Eps.label(e.name, t.name), 12.sp, Palette.T1, FontWeight.SemiBold, lines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    if (isTarget) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(Palette.Star))
                }
            }
        }
    }
    T(
        focusedName?.let { "$it · OK 播放" } ?: "按 OK 用这条线路播放选中的一集", 10.sp, Palette.T3, lines = 1,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 目标集在这条线路里是第几个（0 开始），没有 = -1。和 PlaySession 换线路时找同一集的办法一样 */
internal fun targetIndex(t: Title, l: PlayLine, target: Pair<Int, String?>): Int {
    val (ep, name) = target
    val eps = l.episodes
    if (eps.isEmpty()) return -1
    return if (name != null) Eps.indexOf(eps, name, t.name) else Eps.indexOfNumber(eps, ep, t.name)
}

private fun availability(t: Title, idx: Int, target: Pair<Int, String?>, failed: String?): String {
    val text = Eps.text(target.second, target.second?.let { Eps.number(it, t.name) } ?: target.first, t.name)
    return when {
        failed != null -> "之前播放失败"
        !t.isEpisodic -> "已收录 · 未试播"
        idx >= 0 -> "有$text"
        else -> "没有$text"
    }
}
