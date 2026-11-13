package dev.personal.tv.ui.screens

import dev.personal.tv.data.Resume
import dev.personal.tv.data.Target
import dev.personal.tv.source.Lines
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
import androidx.compose.ui.geometry.Offset
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
import dev.personal.tv.ui.components.Landscape
import dev.personal.tv.source.Eps
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
    /** 热门海报宽度，跟设置里的「海报大小」 */
    val hotW = listOf(64.dp, 75.dp, 90.dp)[dev.personal.tv.data.Prefs.posterSize]

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
            // 有横版剧照铺满；没有就把完整的竖海报摆在右上（继续观看那一行上面），不裁
            if (a != null) Landscape(
                a, Modifier.fillMaxSize().padding(start = 260.dp, bottom = 130.dp),
                posterInset = PaddingValues(top = 56.dp, end = Dimens.SafeH, bottom = 84.dp),
            )
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
                                    Modifier.size(hotW, hotW * 1.5f).remembered(mem, "hot:${t.id}", isDefault = cw.isEmpty() && t == hot.first()),
                                    onClick = { nav.openDetail(t.id) },
                                    onMenu = { nav.posterMenu(t) },
                                    onFocus = { focusedId = t.id },
                                )
                            }
                            item(key = "more") {
                                Focusable(
                                    onClick = { nav.selectTab(Tab.FIND, focusContent = true) },
                                    modifier = Modifier.size(150.dp, hotW * 1.5f).remembered(mem, "more"),
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
            // 背景图从左边 260dp 开始：到那儿之前必须全遮住，否则亮的图会露出一条竖边
            0f to Bg, 0.28f to Bg, 0.6f to Bg.copy(alpha = 0.35f), 0.8f to Color.Transparent,
        ),
    )
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.32f to Color.Transparent, 0.52f to Bg.copy(alpha = 0.6f), 0.7f to Bg, 1f to Bg,
        ),
    )
    drawRect(Brush.verticalGradient(0f to Bg.copy(alpha = 0.8f), 0.2f to Color.Transparent))
    // 右上角是时钟和设置；剧照常把片名题字放在这个角，压暗一点免得糊在一起
    drawRect(Brush.radialGradient(0f to Bg.copy(alpha = 0.92f), 0.45f to Bg.copy(alpha = 0.7f), 1f to Color.Transparent, center = Offset(size.width, 0f), radius = size.width * 0.3f))
}

@Composable
private fun HeroInfo(t: Title, p: Progress?, hotRank: Int) {
    Column(Modifier.width(450.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(Palette.Star, CircleShape))
            Spacer(Modifier.width(6.dp))
            val eyebrow = when {
                p != null && Store.newEpisodes(t, p) > 0 -> "有更新 · 你看完了第 ${p.ep} 集，已更新至 ${t.airedEps} 集"
                p != null && t.isEpisodic -> "继续观看 · ${p.epText(t.name)} · 剩余 ${p.remainingMin} 分钟" + (restText(t, p)?.let { " · $it" } ?: "")
                p != null -> "继续观看 · 电影 · 剩余 ${p.remainingMin} 分钟"
                hotRank >= 0 -> "豆瓣 · 最近热门" + if (t.ratingCount > 0) " · ${fmtWan(t.ratingCount)}人评价" else ""
                else -> t.kind.label
            }
            T(eyebrow, 11.sp, Palette.T2, spacing = 0.04.sp)
        }
        DisplayTitle(t.name, 52.sp, Modifier.padding(top = 8.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.fromSource) {
                // 片源里来的（站点浏览、片源搜索），没有豆瓣评分
                T("片源", 10.sp, Color.White, FontWeight.Bold, Modifier.background(Palette.T3, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            } else {
                DoubanTag()
                if (t.score != null) {
                    T(t.scoreText!!, 15.sp, Palette.Star, FontWeight.Bold, Modifier.padding(start = 7.dp, end = 5.dp))
                    Stars(t.score, 10.sp)
                } else T("暂无评分", 11.sp, Palette.T3, modifier = Modifier.padding(start = 7.dp))
            }
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
                val tg = resumeTarget(t, p)
                Hint("OK", when {
                    tg == null -> "看完了 · 打开详情看更新"
                    tg.resume -> "从 ${fmtTime(p.positionSec)} 继续"
                    else -> "播放 " + tg.text(t)
                })
                Hint("☰", "详情 · 找类似 · 移出列表")
            } else {
                Hint("OK", "打开详情")
                Hint("☰", "找类似 · 加想看 · 不感兴趣")
            }
        }
    }
}

/**
 * 追剧进度的第二行：「后面还有 21 集」「追到最新 · 共 30 集」「最后一集」。
 * 按序号的看豆瓣的已播集数；按名字分期的综艺看线路（线路还没搜过就不显示）。算不出来返回 null。
 */
internal fun restText(t: Title, p: Progress): String? {
    if (!t.isEpisodic) return null
    val cur = p.episodeNo(t.name)
    if (p.isNamed(t.name) || cur == null) {
        val eps = Lines.ref(Lines.get(t.id), p.line, t.name)?.episodes ?: return null
        val i = p.epName?.let { Eps.indexOf(eps, it, t.name) } ?: -1
        if (i < 0) return null
        val rest = Eps.mainCount(eps.drop(i + 1), t.name)
        return if (rest > 0) "后面还有 $rest 期" else "追到最新一期"
    }
    val aired = t.airedEps
    if (aired <= 1 || cur > aired) return null
    val rest = aired - cur
    val updating = t.totalEps > aired
    return when {
        rest > 0 -> "后面还有 $rest 集" + if (updating) " · 更新中" else ""
        updating -> "追到最新 · 共 ${t.totalEps} 集"
        else -> "最后一集"
    }
}

/** 看完了这一集就播下一集，否则接着这一集；线路搜过就按线路里的名字找下一集 */
internal fun resumeTarget(t: Title, p: Progress): Target? =
    Resume.target(t, p, Lines.ref(Lines.get(t.id), p.line, t.name)?.episodes)

@Composable
private fun ContinueCard(t: Title, p: Progress, modifier: Modifier, onFocus: () -> Unit) {
    val nav = LocalNav.current
    val newEps = Store.newEpisodes(t, p)
    Focusable(
        onClick = { resumeTarget(t, p)?.let { nav.play(t.id, it.ep, it.name) } ?: nav.openDetail(t.id) },
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
        Landscape(t, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                // 浅色剧照（白天外景）上字也要看得清
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.9f)))
                .padding(start = 11.dp, end = 11.dp, top = 36.dp, bottom = 12.dp),
        ) {
            T(t.name, 12.5.sp, weight = FontWeight.SemiBold)
            val sub = when {
                newEps > 0 -> "看完第 ${p.ep} 集 · 更新到第 ${t.airedEps} 集"
                t.isEpisodic && p.finishedEp -> "看完${p.epText(t.name)}"
                t.isEpisodic -> "${p.epText(t.name)} · 剩 ${p.remainingMin} 分钟"
                p.finishedEp -> "看完了"
                else -> "电影 · 剩 ${p.remainingMin} 分钟"
            }
            T(sub, 9.5.sp, Palette.T2)
            // 第二行：还剩几集 / 下一集是哪集，看完一集回来一眼就知道进度
            val more = if (newEps > 0) null else restText(t, p)
            if (more != null) T(more, 8.5.sp, Palette.T3, modifier = Modifier.padding(top = 1.dp))
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
