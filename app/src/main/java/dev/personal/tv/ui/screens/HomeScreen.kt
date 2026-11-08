package dev.personal.tv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import dev.personal.tv.ui.theme.EaseOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Progress
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.MenuItem
import dev.personal.tv.ui.Tab
import dev.personal.tv.ui.components.DisplayTitle
import dev.personal.tv.ui.components.Dot
import dev.personal.tv.ui.components.DoubanTag
import dev.personal.tv.ui.components.EdgeScroll
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.NoScroll
import dev.personal.tv.ui.components.PosterCard
import dev.personal.tv.ui.components.ProgressLine
import dev.personal.tv.ui.components.SectionTitle
import dev.personal.tv.ui.components.Stars
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.Backdrop
import dev.personal.tv.ui.components.dashedBorder
import dev.personal.tv.ui.components.fmtTime
import dev.personal.tv.ui.components.fmtWan
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Palette.Bg

/** 首页：大背景是焦点那部，只有两行——继续观看、最近热门 */
@Composable
fun HomeScreen(mem: FocusMemory) {
    val nav = LocalNav.current
    val cw = Store.continueWatching()
    val hot = Catalog.hot().filter { it.id !in Store.progress }.take(14)

    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val focused = (focusedId ?: cw.firstOrNull()?.first?.id ?: hot.firstOrNull()?.id)?.let { Catalog.get(it) }
    // 背景跟随焦点：停留 300ms 才换，避免快速划过时一直闪
    var backdrop by remember { mutableStateOf(focused) }
    LaunchedEffect(focused?.id) { delay(300); backdrop = focused }

    // 焦点到「最近热门」时：大标题不动，继续观看这一行往上收起，热门顶到它的位置
    var hotFocused by remember { mutableStateOf(false) }
    var cwH by remember { mutableIntStateOf(0) }
    val shift by animateFloatAsState(if (hotFocused) -cwH.toFloat() else 0f, tween(280, easing = EaseOut), label = "rows")
    val cwAlpha by animateFloatAsState(if (hotFocused) 0f else 1f, tween(200), label = "cw")

    Box(Modifier.fillMaxSize()) {
        Crossfade(backdrop, animationSpec = tween(400), label = "backdrop") { a ->
            if (a != null) Backdrop(a, Modifier.offset(x = 260.dp).size(700.dp, 410.dp), alignment = Alignment.Center)
        }
        Box(Modifier.fillMaxSize().drawBehind { drawHomeScrims() })

        Box(Modifier.padding(start = Dimens.SafeH, top = 100.dp).height(200.dp)) {
            when {
                focused != null -> HeroInfo(focused, Store.progress[focused.id], hot.indexOf(focused))
                Catalog.loading -> T("正在从豆瓣拉最近热门…", 13.sp, Palette.T2)
                Catalog.error != null -> Column {
                    T("首页没有内容", 20.sp, weight = FontWeight.Bold)
                    T(Catalog.error!!, 11.sp, Palette.T3, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        NoScroll {
            Box(Modifier.fillMaxSize().padding(top = 300.dp).clipToBounds()) {
            Column(
                Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                    .graphicsLayer { translationY = shift }.padding(top = 10.dp),
            ) {
                if (dev.personal.tv.source.Sources.empty) Focusable(
                    onClick = { nav.openSources() },
                    modifier = Modifier.padding(start = Dimens.SafeH, bottom = 14.dp).remembered(mem, "onboard", isDefault = true),
                    corner = 10.dp, scale = 1.03f,
                ) { f ->
                    Row(
                        Modifier.background(if (f) Color.White else Palette.Douban.copy(alpha = 0.22f)).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            T("还没有片源 · 加一个才能播放", 13.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold)
                            T("SStudioTV 不自带片源。手机扫码或输入一个影视仓 / TVBox 配置地址", 9.5.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T2)
                        }
                        T("  ›", 16.sp, if (f) Palette.Bg else Palette.T2)
                    }
                }
                if (cw.isNotEmpty()) Column(Modifier.onSizeChanged { cwH = it.height }.graphicsLayer { alpha = cwAlpha }) {
                    SectionTitle("继续观看", "按最近观看排序", modifier = Modifier.padding(start = Dimens.SafeH))
                    EdgeScroll {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Dimens.SafeH, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(15.dp),
                        ) {
                            items(cw, key = { it.first.id }) { (t, p) ->
                                ContinueCard(
                                    t, p,
                                    Modifier.remembered(mem, "cw:${t.id}", isDefault = t.id == cw.first().first.id),
                                    onFocus = { focusedId = t.id },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Column(
                    Modifier
                        .graphicsLayer { alpha = if (hotFocused) 1f else 0.85f }
                        .onFocusChanged { hotFocused = it.hasFocus },
                ) {
                    SectionTitle("最近热门", "豆瓣", modifier = Modifier.padding(start = Dimens.SafeH))
                    EdgeScroll {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Dimens.SafeH, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(hot, key = { it.id }) { t ->
                                PosterCard(
                                    t, if (t.name.length > 4) 9.sp else 10.sp, 6.dp,
                                    Modifier.size(75.dp, 112.5.dp).remembered(mem, "hot:${t.id}", isDefault = cw.isEmpty() && t == hot.first()),
                                    onClick = { nav.openDetail(t.id) },
                                    onMenu = { nav.posterMenu(t) },
                                    onFocus = { focusedId = t.id },
                                )
                            }
                            item(key = "more") {
                                Focusable(
                                    onClick = { nav.selectTab(Tab.FIND, focusContent = true) },
                                    modifier = Modifier.size(150.dp, 112.5.dp).remembered(mem, "more"),
                                    corner = 6.dp,
                                ) { f ->
                                    Column(
                                        Modifier.fillMaxSize()
                                            .background(if (f) Palette.Ghost8 else Color.Transparent)
                                            .dashedBorder(Color.White.copy(alpha = 0.18f), 6.dp)
                                            .padding(horizontal = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                                    ) {
                                        T("更多去找片", 12.sp, Palette.T2)
                                        T("榜单 · 分类 · 筛选", 9.sp, Palette.T3)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHomeScrims() {
    drawRect(
        Brush.horizontalGradient(
            0f to Bg, 0.26f to Bg.copy(alpha = 0.92f), 0.58f to Bg.copy(alpha = 0.35f), 0.8f to Color.Transparent,
        ),
    )
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.32f to Color.Transparent, 0.52f to Bg.copy(alpha = 0.6f), 0.7f to Bg, 1f to Bg,
        ),
    )
    drawRect(Brush.verticalGradient(0f to Bg.copy(alpha = 0.7f), 0.18f to Color.Transparent))
}

@Composable
private fun HeroInfo(t: Title, p: Progress?, hotRank: Int) {
    Column(Modifier.width(450.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(Palette.Star, CircleShape))
            Spacer(Modifier.width(6.dp))
            val eyebrow = when {
                p != null && Store.newEpisodes(t, p) > 0 -> "有更新 · 你看完了第 ${p.ep} 集，已更新至 ${t.airedEps} 集"
                p != null && t.isEpisodic -> "继续观看 · ${p.epText(t.name)} · 剩余 ${p.remainingMin} 分钟"
                p != null -> "继续观看 · 电影 · 剩余 ${p.remainingMin} 分钟"
                hotRank >= 0 -> "豆瓣 · 最近热门" + if (t.ratingCount > 0) " · ${fmtWan(t.ratingCount)}人评价" else ""
                else -> t.kind.label
            }
            T(eyebrow, 11.sp, Palette.T2, spacing = 0.04.sp)
        }
        DisplayTitle(t.name, 52.sp, Modifier.padding(top = 8.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DoubanTag()
            if (t.score != null) {
                T(t.scoreText!!, 15.sp, Palette.Star, FontWeight.Bold, Modifier.padding(start = 7.dp, end = 5.dp))
                Stars(t.score, 10.sp)
            } else T("暂无评分", 11.sp, Palette.T3, modifier = Modifier.padding(start = 7.dp))
            if (t.year > 0) { Dot(); T("${t.year}", 12.sp, Palette.T2) }
            if (t.genres.isNotEmpty()) { Dot(); T(t.genres.take(2).joinToString(" / "), 12.sp, Palette.T2) }
            if (t.epsText.isNotEmpty()) { Dot(); T(t.epsText, 12.sp, Palette.T2) }
        }
        if (p == null) {
            val line = t.oneLiner.ifEmpty {
                listOfNotNull(t.director.takeIf { it.isNotEmpty() }?.let { "导演 $it" }, t.cast.takeIf { it.isNotEmpty() }?.let { "主演 " + it.joinToString(" / ") })
                    .joinToString("  ·  ")
            }
            if (line.isNotEmpty()) T(line, 11.sp, Palette.T2, lines = 2, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp).width(420.dp))
        }
        Row(Modifier.padding(top = 15.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            if (p != null) {
                val next = nextEpisode(t, p)
                Hint("OK", if (next != p.ep) (if (p.epText(t.name).startsWith("第")) "播放第 $next 集" else "播放下一集") else "从 ${fmtTime(p.positionSec)} 继续")
                Hint("☰", "详情 · 找类似 · 移出列表")
            } else {
                Hint("OK", "打开详情")
                Hint("☰", "找类似 · 加想看 · 不感兴趣")
            }
        }
    }
}

/** 看完了这一集就播下一集，否则接着这一集 */
internal fun nextEpisode(t: Title, p: Progress): Int {
    if (!t.isEpisodic || !p.finishedEp) return p.ep
    // 综艺按名字分期：序号是线路里的，跟豆瓣的集数对不上，看线路里还有没有下一个
    val named = p.epName != null && !dev.personal.tv.source.Eps.isNumber(p.epName, t.name)
    val last = if (named) dev.personal.tv.source.Lines.ref(dev.personal.tv.source.Lines.get(t.id), p.line)?.episodes?.size ?: p.ep else t.airedEps
    return if (p.ep < last) p.ep + 1 else p.ep
}

@Composable
private fun ContinueCard(t: Title, p: Progress, modifier: Modifier, onFocus: () -> Unit) {
    val nav = LocalNav.current
    val newEps = Store.newEpisodes(t, p)
    Focusable(
        onClick = { nextEpisode(t, p).let { n -> nav.play(t.id, n, p.epName.takeIf { n == p.ep }) } },
        modifier = modifier.size(220.dp, 124.dp),
        onFocus = onFocus,
        onMenu = {
            nav.showMenu(
                t,
                listOf(
                    MenuItem("打开详情") { nav.openDetail(t.id) },
                    MenuItem("找类似") { nav.openDetail(t.id, similar = true) },
                    MenuItem("移出继续观看") { Store.progress.remove(t.id); nav.toast("已移出，观看记录不会删除") },
                ),
            )
        },
    ) {
        Backdrop(t, Modifier.fillMaxSize(), alignment = Alignment.Center)
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                .padding(start = 11.dp, end = 11.dp, top = 28.dp, bottom = 12.dp),
        ) {
            T(t.name, 12.5.sp, weight = FontWeight.SemiBold)
            val sub = when {
                newEps > 0 -> "看完第 ${p.ep} 集 · 已更新至 ${t.airedEps} 集"
                t.isEpisodic -> "${p.epText(t.name)} · 剩余 ${p.remainingMin} 分钟"
                else -> "电影 · 剩余 ${p.remainingMin} 分钟"
            }
            T(sub, 9.5.sp, Palette.T2)
        }
        if (newEps > 0) {
            T(
                "更新 $newEps 集", 9.sp, Color(0xFF1A1204), FontWeight.Bold,
                modifier = Modifier.padding(7.dp).background(Palette.Star, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        ProgressLine(p.fraction, Modifier.align(Alignment.BottomStart))
    }
}
