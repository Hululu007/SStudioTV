package dev.personal.tv.ui.screens

import dev.personal.tv.data.Resume
import dev.personal.tv.data.Target
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Progress
import dev.personal.tv.data.SimilarSource
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.data.Heat
import dev.personal.tv.source.Eps
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PlayLine
import androidx.compose.ui.platform.LocalContext
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.Chip
import dev.personal.tv.ui.components.DisplayTitle
import dev.personal.tv.ui.components.Dot
import dev.personal.tv.ui.components.DoubanTag
import dev.personal.tv.ui.components.EdgeScroll
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.NoScroll
import dev.personal.tv.ui.components.PillButton
import dev.personal.tv.ui.components.PosterArt
import dev.personal.tv.ui.components.PosterCard
import dev.personal.tv.ui.components.Score
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
import kotlinx.coroutines.launch

private val Bg = Palette.Bg

/**
 * 详情页分两屏：
 * 1. 片名、豆瓣评分、按钮、选集，右边竖版海报；
 * 2. 值不值得看（热度数字 + 不剧透的 B 站视频）和找类似。
 * 焦点进入哪一屏就整屏滚过去，不跟着焦点一点点挪。
 */
@Composable
fun DetailScreen(r: Route.Detail) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val t = Catalog.get(r.id) ?: return
    // 进详情就补全豆瓣信息、开始找线路；热度和找类似不急，后面慢慢来
    // 换了片源回来也要重新找
    LaunchedEffect(r.id, dev.personal.tv.source.Sources.url) {
        if (!dev.personal.tv.source.Sources.empty) Lines.find(ctx, Catalog.ensureFull(r.id) ?: t)
    }
    LaunchedEffect(r.id) {
        val full = Catalog.ensureFull(r.id) ?: t
        Catalog.loadHeat(full)
        Catalog.loadSimilar(full)
    }
    val mem = nav.mem(r.key)
    val p = Store.progress[t.id]
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val playReq = remember { FocusRequester() }
    val epReq = remember { FocusRequester() }
    val simReq = remember { FocusRequester() }
    var inSec2 by remember { mutableStateOf(false) }
    // 选集按哪条线路显示：线路是陆续搜到的，焦点在选集行里时先不换，免得按钮在手底下变了
    val live = Lines.ref(Lines.get(t.id), p?.line, t.name)
    var frozen by remember { mutableStateOf<PlayLine?>(null) }
    var epFocus by remember { mutableStateOf(false) }
    // 手动换了一套名字（包括换回自动选）马上换
    val chosen = Lines.chosen(t.id)
    var lastChosen by remember { mutableStateOf(chosen) }
    LaunchedEffect(live, epFocus, chosen) {
        if (!epFocus || frozen == null || chosen != lastChosen) { frozen = live; lastChosen = chosen }
    }
    val ref = if (epFocus) frozen ?: live else live

    BackHandler(enabled = inSec2) { runCatching { playReq.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageH = maxHeight
        val pagePx = with(LocalDensity.current) { pageH.toPx() }

        // 背景：海报模糊放大，所有片子效果一致
        Backdrop(
            t,
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f; alpha = 0.55f }
                .blur(40.dp),
            silhouette = false, alignment = Alignment.Center,
        )

        NoScroll {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                // ---------------- 第一屏 ----------------
                Box(
                    Modifier.fillMaxWidth().height(pageH)
                        .onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } },
                ) {
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            drawRect(Brush.horizontalGradient(0f to Bg, 0.4f to Bg.copy(alpha = 0.85f), 0.75f to Bg.copy(alpha = 0.3f), 1f to Bg.copy(alpha = 0.1f)))
                            drawRect(Brush.verticalGradient(0.58f to Color.Transparent, 0.82f to Bg, 1f to Bg))
                        },
                    )
                    PosterArt(
                        t, if (t.name.length > 4) 20.sp else 26.sp,
                        Modifier.offset(x = 702.dp, y = 48.dp).size(210.dp, 315.dp)
                            .shadow(24.dp, RoundedCornerShape(10.dp)),
                        bottomPadding = 24.dp,
                    )
                    Column(Modifier.padding(start = Dimens.SafeH, top = 46.dp).width(610.dp)) {
                        DisplayTitle(t.name, 48.sp, spacing = 0.08f)
                        T(
                            listOf(t.origName, if (t.year > 0) "${t.year}" else "", t.region).filter { it.isNotEmpty() }.joinToString(" · "),
                            12.sp, Palette.T3, modifier = Modifier.padding(top = 4.dp),
                        )
                        DoubanBox(t, Modifier.padding(top = 14.dp))
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(t.genres.joinToString(" / ").ifEmpty { t.kind.label }, 11.5.sp, Palette.T2)
                            if (t.epsText.isNotEmpty()) { Dot(); T(t.epsText, 11.5.sp, Palette.T2) }
                            t.schedule?.let { Dot(); T(it, 11.5.sp, Palette.T2) }
                        }
                        if (t.director.isNotEmpty() || t.cast.isNotEmpty()) {
                            Row(Modifier.padding(top = 4.dp)) {
                                if (t.director.isNotEmpty()) T("导演 ${t.director}", 10.5.sp, Palette.T2, modifier = Modifier.padding(end = 14.dp))
                                if (t.cast.isNotEmpty()) T("主演 " + t.cast.joinToString(" / "), 10.5.sp, Palette.T3)
                            }
                        }
                        T(t.desc.ifEmpty { if (t.full) "" else "正在从豆瓣取简介…" }, 11.5.sp, Palette.T2, lines = 2, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp).width(500.dp))
                        Buttons(t, p, ref, mem, r, playReq, epReq, simReq, Modifier.padding(top = 14.dp))
                    }
                    if (hasEpisodes(t, ref)) {
                        val ranking = Lines.get(t.id)?.ranking
                        val versions = remember(ranking, t.id) { Lines.versions(Lines.get(t.id), t.name) }
                        EpisodeRow(t, p, ref, epReq, mem, Modifier.padding(top = 395.dp).onFocusChanged { epFocus = it.hasFocus }, versions = versions) { ep, name -> nav.play(t.id, ep, name) }
                    }
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(start = Dimens.SafeH, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        T("▼ 值不值得看", 12.sp, Palette.T2, FontWeight.SemiBold)
                        T("找类似", 12.sp, Palette.T3)
                    }
                }

                // ---------------- 第二屏 ----------------
                Box(
                    Modifier.fillMaxWidth().height(pageH)
                        .onFocusChanged {
                            inSec2 = it.hasFocus
                            if (it.hasFocus) scope.launch { scroll.animateScrollTo(pagePx.toInt()) }
                        }
                        .drawBehind { drawRect(Brush.verticalGradient(0f to Bg.copy(alpha = 0.7f), 0.6f to Bg, 1f to Bg)) },
                ) {
                    MiniHead(t, p, Modifier.padding(start = Dimens.SafeH, top = 24.dp))
                    Column(Modifier.padding(top = 66.dp)) {
                        WorthIt(t, mem)
                        Similar(t, mem, r.similar, simReq)
                    }
                }
            }
        }
    }
}

@Composable
private fun DoubanBox(t: Title, modifier: Modifier) {
    Row(
        modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (t.fromSource) {
            T("片源", 11.sp, Color.White, FontWeight.Bold, Modifier.background(Palette.T3, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            T("豆瓣上没有这部", 16.sp, Palette.T2, FontWeight.Bold)
            T(t.oneLiner, 10.sp, Palette.T3)
            return@Row
        }
        DoubanTag()
        val s = t.score
        if (s == null) {
            T("暂无评分", 16.sp, Palette.T2, FontWeight.Bold)
            T("评价人数不足", 10.sp, Palette.T3)
            return@Row
        }
        T(t.scoreText!!, 34.sp, Palette.Star, FontWeight.ExtraBold)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Stars(s, 10.sp)
            T("${fmtWan(t.ratingCount)}人评价", 10.sp, Palette.T2)
        }
        val h = Catalog.heat(t.id)
        val st = h?.stars.orEmpty()
        if (st.size == 5) Column(
            Modifier.drawBehind { drawRect(Color.White.copy(alpha = 0.12f), size = androidx.compose.ui.geometry.Size(1f, size.height)) }
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf("5星" to st[4], "4星" to st[3], "3星" to st[2]).forEach { (l, f) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    T(l, 7.5.sp, Palette.T3)
                    Box(Modifier.size(75.dp, 3.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.size(75.dp * f, 3.dp).background(Palette.Star, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        h?.betterThan?.let { b ->
            val (pct, what) = b.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            Column(
                Modifier.drawBehind { drawRect(Color.White.copy(alpha = 0.12f), size = androidx.compose.ui.geometry.Size(1f, size.height)) }
                    .padding(start = 14.dp),
            ) {
                T("好于", 10.sp, Palette.T2)
                Row(verticalAlignment = Alignment.Bottom) {
                    T(pct, 13.sp, Palette.T1, FontWeight.Bold)
                    T(" $what", 10.sp, Palette.T2)
                }
            }
        }
    }
}

@Composable
private fun Buttons(
    t: Title, p: Progress?, ref: PlayLine?, mem: FocusMemory, r: Route.Detail,
    playReq: FocusRequester, epReq: FocusRequester, simReq: FocusRequester, modifier: Modifier,
) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val set = Lines.get(t.id)
    val n = set?.lines?.size ?: 0
    val searching = set == null || !set.done
    // 首页「继续观看」和这里用同一套算法（Resume.target）；没看过的从第一集（按名字的线路是第一个）开始
    val firstName = ref?.episodes?.takeIf { it.isNotEmpty() && !Eps.numeric(it, t.name, t.isVariety) }?.first()?.name
    val tg = if (p == null) Target(1, firstName, false) else Resume.target(t, p, ref?.episodes)
    val ep = tg?.ep ?: p!!.ep
    val epName = if (tg != null) tg.name else p!!.epName
    val epLabel = tg?.text(t) ?: p!!.epText(t.name)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        val (label, small) = when {
            dev.personal.tv.source.Sources.empty -> "先添加片源" to null
            n == 0 && searching -> "正在找线路" to set?.let { if (it.total > 0) "${it.searched}/${it.total}" else null }
            n == 0 -> "暂无线路" to null
            p == null -> (if (t.isEpisodic) "播放 $epLabel" else "播放") to null
            tg == null -> (if (t.isEpisodic) "重看 $epLabel" else "重看") to null
            !tg.resume -> "播放 $epLabel" to "新"
            t.isEpisodic -> "继续播放 $epLabel" to fmtTime(p.positionSec)
            else -> "继续播放" to fmtTime(p.positionSec)
        }
        PillButton(
            onClick = {
                when {
                    n > 0 -> nav.play(t.id, ep, epName)
                    dev.personal.tv.source.Sources.empty -> nav.openSources()
                    searching -> nav.toast("还在各个站点里找，找到第一条就能播")
                    else -> {
                        nav.toast(set?.error ?: "这个片源配置里没找到《${t.name}》，重新找一遍")
                        Lines.find(ctx, t, force = true)
                    }
                }
            },
            modifier = Modifier.focusRequester(playReq).remembered(mem, "play", isDefault = !r.similar),
            primary = n > 0,
        ) { fg ->
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(15.dp), tint = fg)
            T(label, 13.sp, fg, FontWeight.SemiBold)
            small?.let { T(it, 10.sp, fg.copy(alpha = 0.6f)) }
        }
        if (n == 0 && !searching && !dev.personal.tv.source.Sources.empty) {
            PillButton({ nav.openSources() }, Modifier.remembered(mem, "sources")) { fg ->
                T("换片源", 13.sp, fg, FontWeight.SemiBold)
                T(dev.personal.tv.source.Sources.name, 10.sp, fg.copy(alpha = 0.6f))
            }
        }
        if (p != null && hasEpisodes(t, ref)) {
            PillButton({ runCatching { epReq.requestFocus() } }, Modifier.remembered(mem, "eps")) { fg ->
                Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(14.dp), tint = fg)
                T("选集", 13.sp, fg, FontWeight.SemiBold)
            }
        } else {
            val wished = t.id in Store.wish
            PillButton({ Store.toggleWish(t.id); nav.toast(if (wished) "已移出想看" else "已加入想看 · 只存在这台电视上") }, Modifier.remembered(mem, "wish")) { fg ->
                Icon(if (wished) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(14.dp), tint = if (wished) Palette.Star else fg)
                T(if (wished) "已想看" else "想看", 13.sp, fg, FontWeight.SemiBold)
            }
        }
        PillButton({ runCatching { simReq.requestFocus() } }, Modifier.remembered(mem, "sim")) { fg ->
            Icon(Icons.Filled.Search, null, Modifier.size(14.dp), tint = fg)
            T("找类似", 13.sp, fg, FontWeight.SemiBold)
        }
        if (n > 0) {
            PillButton({ nav.play(t.id, ep, epName, openLines = true) }, Modifier.remembered(mem, "lines")) { fg ->
                T("线路 · $n 条", 13.sp, fg, FontWeight.SemiBold)
                T(if (searching) "还在找…" else "自动选", 10.sp, fg.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * 选集：看过的变暗，当前集带进度，新出的标「新」，没播出的虚线框。
 * 线路里的分集名字不是序号（综艺的「11.01 上」「加更版」）时，直接按线路里的名字排。
 * 集数多时标题旁边有分段（「1–30」「31–60」），焦点移上去列表就跳到那一段，按 OK 进去。
 * [versions]：选集可以换成别的线路的名字显示（详情页用），不止一种时标题旁边有「换一套名字」。
 * onPlay(序号, 名字)：名字为 null 表示按序号。
 */
@Composable
fun EpisodeRow(
    t: Title, p: Progress?, line: PlayLine?, curReq: FocusRequester, mem: FocusMemory?,
    modifier: Modifier = Modifier, versions: List<PlayLine> = emptyList(), onPlay: (Int, String?) -> Unit,
) {
    // 「换一套名字」的焦点放在这里：换完可能从按名字的行变成按序号的行，按钮要接着有焦点
    val vf = remember { VersionFocus() }
    val numericLine = line == null || line.episodes.isEmpty() || Eps.numeric(line.episodes, t.name, t.isVariety)
    val vs = versionChoices(t, line, numericLine, versions)
    if (!numericLine) return NamedEpisodeRow(t, p, line!!, curReq, mem, modifier, vs, vf, onPlay)
    // 线路里的集数比豆瓣多（第二季接着第一季编号、豆瓣还没更新）：按线路的来
    val lineMax = line?.episodes?.maxOfOrNull { Eps.number(it.name, t.name) ?: 0 } ?: 0
    val aired = maxOf(t.airedEps, lineMax)
    val total = maxOf(t.totalEps, aired)
    // 上次看到第几集（名字里的数字；按名字分期的记录没有）
    val no = p?.episodeNo(t.name)
    val cur = p?.let { pr ->
        val tg = Resume.target(t, pr, line?.episodes)
        tg?.name?.let { Eps.number(it, t.name) } ?: tg?.ep?.takeIf { tg.name == null } ?: no
    } ?: 1
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (cur - 5).coerceAtLeast(0))
    val spans = remember(total) { spans(total, 30) { a, b -> "${a + 1}–$b" } }
    val jump = rememberJump(state)
    val headReq = remember { FocusRequester() }
    Column(modifier) {
        EpHeader(headReq.takeIf { spans.isNotEmpty() || vs.isNotEmpty() }) {
            T("选集", 13.sp, weight = FontWeight.SemiBold)
            T("1–$total", 9.5.sp, Palette.T1, modifier = Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
            SpanChips(spans, state, headReq) { jump.go(minOf(it, aired - 1).coerceAtLeast(0)) }
            VersionChip(t, line, vs, vf, curReq, if (spans.isEmpty()) headReq else null)
            t.schedule?.let { T("$it · 已更新至 $aired 集", 9.5.sp, Palette.T3) }
        }
        EdgeScroll {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = Dimens.SafeH, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items((1..total).toList(), key = { it }) { ep ->
                    val locked = ep > aired
                    val seen = no != null && (ep < no || (ep == no && p!!.finishedEp))
                    val isCur = no != null && ep == no && !p!!.finishedEp
                    val isNew = ep == aired && aired < total && (no == null || ep > no)
                    if (locked) {
                        Box(Modifier.size(54.dp, 34.dp).dashedBorder(Color.White.copy(alpha = 0.12f), 6.dp), contentAlignment = Alignment.Center) {
                            T("$ep", 10.sp, Palette.T3)
                        }
                    } else {
                        var m = Modifier.size(54.dp, 34.dp)
                        if (ep == cur) m = m.focusRequester(curReq)
                        if (ep - 1 == jump.at) m = m.focusRequester(jump.req)
                        if (mem != null) m = m.remembered(mem, "ep:$ep")
                        Focusable({ onPlay(ep, null) }, m, corner = 6.dp) { f ->
                            EpChip(f, seen, isCur, isNew, p, Modifier.fillMaxSize()) {
                                T("$ep", 13.sp, if (seen && !f) Palette.T3 else Palette.T1, FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 按线路里的分集名字排（综艺）：按钮宽度跟着名字走，聚焦时标题旁边显示完整名字。
 * 正片、加更（抢鲜版、沉浸版、纯享）、花絮（预告、直播回放、路透）分开放，标题旁边切换；默认在正片（上次看的是加更就在加更）。
 */
@Composable
private fun NamedEpisodeRow(
    t: Title, p: Progress?, line: PlayLine, curReq: FocusRequester, mem: FocusMemory?,
    modifier: Modifier, versions: List<PlayLine>, vf: VersionFocus, onPlay: (Int, String?) -> Unit,
) {
    val eps = line.episodes
    val w = namedWatched(t, p, line)
    val watched = w?.plus(1)
    val cur = if (w == null) 1 else if (p!!.finishedEp) (Eps.nextAfter(eps, w, t.name).takeIf { it >= 0 } ?: w) + 1 else w + 1
    // 同名的分集（每周都叫「加更版」）：焦点记忆按「名字 + 第几个同名的」记
    val occ = remember(eps) { val seen = HashMap<String, Int>(); eps.map { e -> seen.merge(e.name, 1, Int::plus)!! } }
    val groups = remember(eps) { eps.indices.groupBy { Eps.group(eps[it].name, t.name) }.toSortedMap() }
    var g by remember(line.key) { mutableIntStateOf(Eps.group(eps.getOrNull(cur - 1)?.name ?: "", t.name).takeIf { it in groups } ?: groups.firstKey()) }
    val shown = groups[g] ?: eps.indices.toList()
    var focusedName by remember { mutableStateOf<String?>(null) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (shown.indexOf(cur - 1) - 3).coerceAtLeast(0))
    val spans = remember(shown, eps) {
        spans(shown.size, 20) { a, b ->
            val x = spanLabel(eps[shown[a]].name, t.name)
            val y = spanLabel(eps[shown[b - 1]].name, t.name)
            if (x != null && y != null) "$x–$y" else "${a + 1}–$b"
        }
    }
    val jump = rememberJump(state)
    // 换了分组：列表回到开头（当前这一集在这组里就停在它前面一点）
    var lastG by remember { mutableIntStateOf(g) }
    LaunchedEffect(g) {
        if (g != lastG) { lastG = g; state.scrollToItem((shown.indexOf(cur - 1) - 3).coerceAtLeast(0)) }
    }
    // 当前这一集不在显示的这组里（换了组）：「选集」按钮把焦点给这组的第一个
    val curK = shown.indexOf(cur - 1).let { if (it < 0) 0 else it }
    val headReq = remember { FocusRequester() }
    Column(modifier) {
        // 从上面按钮按下来先落到选中的那个分组 / 分段上；分组按 OK 才换（焦点路过不换，免得列表在手底下变）
        EpHeader(headReq.takeIf { groups.size > 1 || spans.isNotEmpty() || versions.isNotEmpty() }) {
            T("选集", 13.sp, weight = FontWeight.SemiBold)
            if (groups.size > 1) {
                groups.forEach { (k, v) ->
                    Chip(
                        "${Eps.GROUPS[k]} ${v.size}", k == g, { if (g != k) { g = k; jump.reset() } else jump.go(0) },
                        if (k == g) Modifier.focusRequester(headReq) else Modifier,
                    )
                }
            } else {
                T("共 ${eps.size} 个", 9.5.sp, Palette.T1, modifier = Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
            }
            SpanChips(spans, state, headReq.takeIf { groups.size <= 1 }) { jump.go(it) }
            VersionChip(t, line, versions, vf, curReq, headReq.takeIf { groups.size <= 1 && spans.isEmpty() })
            T(focusedName ?: t.schedule.orEmpty(), 9.5.sp, Palette.T3, lines = 1, modifier = Modifier.widthIn(max = 420.dp))
        }
        EdgeScroll {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = Dimens.SafeH, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                itemsIndexed(shown, key = { _, i -> "$i:${eps[i].name}" }) { k, i ->
                    val e = eps[i]
                    val ep = i + 1
                    val seen = watched != null && (ep < watched || (ep == watched && p!!.finishedEp))
                    val isCur = watched != null && ep == watched && !p!!.finishedEp
                    var m: Modifier = Modifier
                    if (k == curK) m = m.focusRequester(curReq)
                    if (k == jump.at) m = m.focusRequester(jump.req)
                    if (mem != null) m = m.remembered(mem, "ep:${e.name}#${occ[i]}")
                    Focusable({ onPlay(ep, e.name) }, m, corner = 6.dp, onFocus = { focusedName = Eps.fullLabel(e.name, t.name) }) { f ->
                        EpChip(
                            f, seen, isCur, false, p,
                            Modifier.height(34.dp).widthIn(min = 54.dp, max = 190.dp),
                        ) {
                            T(Eps.label(e.name, t.name), 12.sp, if (seen && !f) Palette.T3 else Palette.T1, FontWeight.SemiBold, lines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 选集标题那一行：「选集」、分组、分段、换名字，一行排下。焦点从外面进来时落在 [enter]（选中的那个）上 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EpHeader(enter: FocusRequester?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    var m: Modifier = Modifier.padding(start = Dimens.SafeH, bottom = 2.dp)
    if (enter != null) m = m.focusProperties { this.enter = { enter } }.focusGroup()
    Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** 列表里的一段 [from, to)，按钮上显示 [label] */
private class Span(val from: Int, val to: Int, val label: String)

/** 超过 1.5 段才分段；最后一段太短的并进前一段 */
private fun spans(n: Int, size: Int, label: (Int, Int) -> String): List<Span> {
    if (n <= size * 3 / 2) return emptyList()
    val starts = (0 until n step size).toMutableList()
    if (n - starts.last() < size / 3) starts.removeAt(starts.lastIndex)
    return starts.mapIndexed { k, a -> val b = starts.getOrNull(k + 1) ?: n; Span(a, b, label(a, b)) }
}

/** 分段按钮上的名字：带日期的用日期（「10.01」），第 N 期用「第N期」，认不出的 null（改用序号） */
private fun spanLabel(name: String, title: String): String? {
    val id = Eps.parse(name, title)
    return when {
        id.date != null -> id.date.takeLast(4).let { "${it.take(2)}.${it.takeLast(2)}" }
        id.num != null && id.unit == '期' -> "${id.num}期"
        else -> null
    }
}

/**
 * 分段按钮：焦点移到别的段上列表滚到那一段（列表已经在这一段里就不动），按 OK 焦点进到那一段的第一个。
 * [req] 挂在当前所在的那一段上（焦点进标题行时落在这里）
 */
@Composable
private fun SpanChips(spans: List<Span>, state: androidx.compose.foundation.lazy.LazyListState, req: FocusRequester?, onEnter: (Int) -> Unit) {
    if (spans.isEmpty()) return
    val scope = rememberCoroutineScope()
    val at by remember(spans) { androidx.compose.runtime.derivedStateOf { spans.indexOfLast { it.from <= state.firstVisibleItemIndex + 2 }.coerceAtLeast(0) } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        spans.forEachIndexed { k, s ->
            Chip(
                s.label, k == at, { onEnter(s.from) },
                if (k == at && req != null) Modifier.focusRequester(req) else Modifier,
                onFocus = { if (k != at) scope.launch { state.animateScrollToItem(s.from) } },
            )
        }
    }
}

/**
 * 能换的几套名字。综艺：按名字分期的线路（按序号的名字用不上），加上现在这条；
 * 电视剧：一般不给换，除非现在显示的是带副标题的名字、或者手动换过（换到按序号的那套之后还要能换回来）
 */
private fun versionChoices(t: Title, line: PlayLine?, numericLine: Boolean, versions: List<PlayLine>): List<PlayLine> {
    if (line == null || versions.size < 2) return emptyList()
    val vs = when {
        t.isVariety -> versions.filter { v -> v.key == line.key || !Eps.numeric(v.episodes, t.name, true) }
        !numericLine || Lines.chosen(t.id) != null -> versions
        else -> emptyList()
    }
    return if (vs.size > 1) vs else emptyList()
}

/** 「换一套名字」按钮的焦点：换完以后按钮可能重画（行的样式变了），要把焦点还给它 */
private class VersionFocus {
    val req = FocusRequester()
    var want by mutableStateOf(false)
}

/** 换一套名字显示选集（别的线路的分集名字），只在详情页有。换回第一套 = 回到自动选 */
@Composable
private fun VersionChip(t: Title, line: PlayLine?, versions: List<PlayLine>, vf: VersionFocus, curReq: FocusRequester, enterReq: FocusRequester?) {
    LaunchedEffect(line?.key, versions.isEmpty()) {
        if (!vf.want) return@LaunchedEffect
        vf.want = false
        withFrameNanos { }
        // 按钮没了（换回自动以后电视剧不再显示它）：焦点给选集
        runCatching { vf.req.requestFocus() }.onFailure { runCatching { curReq.requestFocus() } }
    }
    if (versions.size < 2 || line == null) return
    val nav = LocalNav.current
    val i = versions.indexOfFirst { it.key == line.key }
    var m = Modifier.focusRequester(vf.req)
    if (enterReq != null) m = m.focusRequester(enterReq)
    Chip("换一套名字 ${if (i >= 0) i + 1 else 1}/${versions.size}", false, {
        val k = (i + 1).mod(versions.size)
        val next = versions[k]
        vf.want = true
        Lines.choose(t.id, if (k == 0) null else next.key)
        nav.toast("选集按「${next.siteName} · ${next.flag}」的名字显示 · ${next.episodes.size} 个")
    }, m)
}

/** 分段跳转：滚到 [at] 那一个，等它画出来再把焦点给它 */
private class Jump(val req: FocusRequester) {
    var at by mutableIntStateOf(-1)
    var tick by mutableIntStateOf(0)
    fun go(i: Int) { at = i; tick++ }
    fun reset() { at = -1 }
}

@Composable
private fun rememberJump(state: androidx.compose.foundation.lazy.LazyListState): Jump {
    val j = remember { Jump(FocusRequester()) }
    LaunchedEffect(j.tick) {
        if (j.tick == 0 || j.at < 0) return@LaunchedEffect
        state.scrollToItem(j.at)
        withFrameNanos { }
        runCatching { j.req.requestFocus() }
    }
    return j
}

/**
 * 上次看的那一期在这条线路里是第几个（0 开始），认不出 = null。
 * 先看上次那个位置上是不是正好这个名字（同名的加更版不能都认成第一个），再按名字找；
 * 按位置凑只在同一条线路、而且记录的就是位置时用。
 */
private fun namedWatched(t: Title, p: Progress?, line: PlayLine): Int? {
    val pr = p ?: return null
    val eps = line.episodes
    val sameLine = pr.line == line.key && pr.isNamed(t.name)
    if (sameLine && pr.epName != null && eps.getOrNull(pr.ep - 1)?.name == pr.epName) return pr.ep - 1
    pr.epName?.let { Eps.indexOf(eps, it, t.name) }?.takeIf { it >= 0 }?.let { return it }
    return if (sameLine && pr.ep in 1..eps.size) pr.ep - 1 else null
}

/** 有没有选集行：豆瓣说不止一集，或者线路里不止一集（豆瓣没写集数的综艺） */
private fun hasEpisodes(t: Title, ref: PlayLine?) =
    t.isEpisodic && (maxOf(t.totalEps, t.airedEps) > 1 || (ref?.episodes?.size ?: 0) > 1)

@Composable
private fun EpChip(f: Boolean, seen: Boolean, isCur: Boolean, isNew: Boolean, p: Progress?, modifier: Modifier, label: @Composable () -> Unit) {
    Box(
        Modifier.background(
            when {
                f -> Color.White.copy(alpha = 0.22f)
                isCur -> Color.White.copy(alpha = 0.16f)
                seen -> Palette.Ghost4
                else -> Palette.Ghost8
            },
        ).then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        label()
        if (seen) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)))
        if (isNew) T("新", 6.5.sp, Palette.Star, FontWeight.Bold, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 3.dp))
        if (isCur) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p!!.fraction).height(2.5.dp).background(Color.White))
    }
}

@Composable
private fun MiniHead(t: Title, p: Progress?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DisplayTitle(t.name, 17.sp, Modifier.padding(end = 9.dp))
        DoubanTag(13.dp, 7.5.sp)
        Spacer(Modifier.width(6.dp))
        Score(t, 12.sp)
        Dot()
        T(t.genres.take(2).joinToString(" / "), 11.sp, Palette.T2)
        if (p != null) {
            Dot()
            T(if (t.isEpisodic) "看到 ${p.epText(t.name)}" else "看到 ${fmtTime(p.positionSec)}", 11.sp, Palette.T2)
        }
    }
}

// ---------------------------------------------------------------------------
// 值不值得看：只有豆瓣的人数，不放任何帖子和评论内容
// ---------------------------------------------------------------------------

@Composable
private fun WorthIt(t: Title, @Suppress("UNUSED_PARAMETER") mem: FocusMemory) {
    val h = Catalog.heat(t.id)
    SectionTitle("值不值得看", "豆瓣的人数统计，不显示任何帖子内容", 15.sp, Modifier.padding(start = Dimens.SafeH))
    if (t.fromSource) {
        T("这部是在片源里搜到的，豆瓣上没有，没有评分和人数。", 11.sp, Palette.T3, modifier = Modifier.padding(start = Dimens.SafeH, top = 6.dp, bottom = 24.dp))
        return
    }
    if (h == null) {
        T("正在从豆瓣取…", 11.sp, Palette.T3, modifier = Modifier.padding(start = Dimens.SafeH, top = 6.dp, bottom = 24.dp))
        return
    }
    Row(Modifier.padding(start = Dimens.SafeH, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        HeatTile(Palette.Douban, "豆瓣 · 想看", h.wish, null)
        HeatTile(Palette.Douban, "豆瓣 · 在看", h.doing, null)
        HeatTile(Palette.Douban, "豆瓣 · 看过", h.done, null)
        h.betterThan?.let { HeatTile(Palette.Star, "同类排名", -1, "好于 $it") }
        if (h.stars.size == 5 && t.ratingCount > 0) {
            val good = ((h.stars[3] + h.stars[4]) * 100).toInt()
            HeatTile(Palette.Star, "打 4–5 星的", -1, "$good%")
        }
    }
}

@Composable
private fun HeatTile(color: Color, src: String, num: Int, text: String?) {
    Column(Modifier.width(150.dp).background(Palette.Ghost6, RoundedCornerShape(9.dp)).padding(horizontal = 11.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(5.dp).background(color, RoundedCornerShape(1.5.dp)))
            T(src, 9.sp, Palette.T3)
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Bottom) {
            if (text != null) {
                T(text, 15.sp, weight = FontWeight.Bold, lines = 1)
            } else {
                val wan = num >= 10_000
                T(if (wan) "%.1f".format(num / 10_000f) else "$num", 17.sp, weight = FontWeight.Bold)
                T(if (wan) "万人" else "人", 10.sp, Palette.T2, modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 找类似：四个来源可切换
// ---------------------------------------------------------------------------

@Composable
private fun Similar(t: Title, mem: FocusMemory, focusFirst: Boolean, simReq: FocusRequester) {
    val nav = LocalNav.current
    var src by rememberSaveable { mutableStateOf(SimilarSource.ALSO_LIKED) }
    val list = Catalog.similar(t, src)
    SectionTitle("找类似", size = 15.sp, modifier = Modifier.padding(start = Dimens.SafeH, top = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SimilarSource.entries.forEach { s ->
                Chip(s.label, src == s, { src = s }, Modifier.remembered(mem, "src:${s.name}"), size = 9.5.sp, height = 19.dp, onFocus = { src = s })
            }
        }
    }
    if (list.isEmpty()) {
        T("没有找到「${src.label}」的其它片子", 11.sp, Palette.T3, modifier = Modifier.padding(start = Dimens.SafeH, top = 30.dp))
        return
    }
    EdgeScroll {
        LazyRow(
            Modifier.padding(top = 0.dp),
            contentPadding = PaddingValues(horizontal = Dimens.SafeH, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(list, key = { "${src.name}:${it.id}" }) { o ->
                val first = o == list.first()
                Column(Modifier.width(70.dp)) {
                    var m: Modifier = Modifier.fillMaxWidth()
                    if (first) m = m.focusRequester(simReq)
                    m = m.remembered(mem, "sim:${o.id}", isDefault = focusFirst && first)
                    PosterCard(
                        o, if (o.name.length > 4) 8.sp else 9.sp, 6.dp, m,
                        onClick = { nav.openDetail(o.id) }, onMenu = { nav.posterMenu(o) },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).graphicsLayer { alpha = if (o.lines == 0) 0.45f else 1f },
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom,
                    ) {
                        T(o.name, 9.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Score(o, 8.5.sp)
                    }
                }
            }
        }
    }
}
