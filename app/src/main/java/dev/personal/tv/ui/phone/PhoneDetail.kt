package dev.personal.tv.ui.phone

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Progress
import dev.personal.tv.data.Resume
import dev.personal.tv.data.SimilarSource
import dev.personal.tv.data.Store
import dev.personal.tv.data.Target
import dev.personal.tv.data.Title
import dev.personal.tv.source.Eps
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PlayLine
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.Backdrop
import dev.personal.tv.ui.components.PosterArt
import dev.personal.tv.ui.components.dashedBorder
import dev.personal.tv.ui.components.fmtTime
import dev.personal.tv.ui.components.fmtWan
import dev.personal.tv.ui.screens.hasEpisodes
import dev.personal.tv.ui.screens.namedWatched
import dev.personal.tv.ui.screens.spanLabel
import dev.personal.tv.ui.screens.spans
import dev.personal.tv.ui.screens.versionChoices
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * 详情页：上面海报和豆瓣信息，接着是播放按钮（和电视、首页「继续观看」同一套算法决定放哪一集）、
 * 选集、简介、热度、找类似。进来就开始找线路。
 */
@Composable
fun PhoneDetail(r: Route.Detail) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val t = Catalog.get(r.id) ?: run { CenterNote("找不到这部片"); return }
    LaunchedEffect(r.id, Sources.url) { if (!Sources.empty) Lines.find(ctx, Catalog.ensureFull(r.id) ?: t) }
    LaunchedEffect(r.id) {
        val full = Catalog.ensureFull(r.id) ?: t
        Catalog.loadHeat(full)
        Catalog.loadSimilar(full)
    }
    val p = Store.progress[t.id]
    val ref = Lines.ref(Lines.get(t.id), p?.line, t.name)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val eps = hasEpisodes(t, ref)
    val simIndex = if (eps) 4 else 3
    LaunchedEffect(r.similar) { if (r.similar) { kotlinx.coroutines.delay(300); list.animateScrollToItem(simIndex) } }
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 400 } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item("head") { Header(t) }
            item("play") { PlayButtons(t, p, ref) { scope.launch { list.animateScrollToItem(simIndex) } } }
            if (eps) item("eps") { Episodes(t, p, ref) }
            item("about") { About(t) }
            item("similar") { Similar(t) }
            item("end") { Spacer(Modifier.height(24.dp)) }
        }
        // 顶栏：滑下去以后加底色和片名
        Row(
            Modifier.fillMaxWidth().background(if (scrolled) PC.Bg.copy(alpha = 0.96f) else Color.Transparent)
                .statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(if (scrolled) Color.Transparent else Color.Black.copy(alpha = 0.35f))
                    .clickable { nav.back() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Palette.T1) }
            if (scrolled) Tx(t.name, 17.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun Header(t: Title) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth()) {
        // 背景：海报放大模糊（Android 12 以下没有模糊，调暗一些）
        Backdrop(
            t,
            Modifier.matchParentSize().graphicsLayer { alpha = if (Build.VERSION.SDK_INT >= 31) 0.6f else 0.3f }
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
            silhouette = false, alignment = Alignment.Center,
        )
        Box(Modifier.matchParentSize().drawBehind { drawRect(Brush.verticalGradient(0f to PC.Bg.copy(alpha = 0.2f), 0.7f to PC.Bg.copy(alpha = 0.75f), 1f to PC.Bg)) })
        Row(Modifier.padding(start = PC.Edge, end = PC.Edge, top = top + 60.dp, bottom = 8.dp)) {
            PosterArt(
                t, if (t.name.length > 4) 12.sp else 15.sp,
                Modifier.size(118.dp, 177.dp).shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)),
                bottomPadding = 12.dp,
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Tx(t.name, if (t.name.length > 8) 19.sp else 23.sp, weight = FontWeight.ExtraBold, lines = 3, lineHeight = 28.sp)
                val meta = listOf(t.origName.takeIf { it != t.name }.orEmpty(), if (t.year > 0) "${t.year}" else "", t.region).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) Tx(meta, 12.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.score != null) {
                        Tx("豆瓣", 10.sp, Color.White, FontWeight.Bold, Modifier.background(Palette.Douban, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                        Tx(t.scoreText!!, 22.sp, Palette.Star, FontWeight.ExtraBold, Modifier.padding(start = 6.dp))
                        Column(Modifier.padding(start = 6.dp)) {
                            StarBar(t.score, 10.sp)
                            if (t.ratingCount > 0) Tx("${fmtWan(t.ratingCount)}人评分", 10.sp, Palette.T3)
                        }
                    } else if (!t.fromSource) Tx("豆瓣暂无评分", 12.sp, Palette.T3)
                }
                Tx(
                    listOfNotNull(t.genres.joinToString(" / ").ifEmpty { t.kind.label }, t.epsText.ifEmpty { null }).joinToString(" · "),
                    12.sp, Palette.T2, lines = 2, modifier = Modifier.padding(top = 8.dp),
                )
                t.schedule?.let { Tx(it, 12.sp, Palette.T2, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
    }
}

/** 播放、想看、线路、找类似 */
@Composable
private fun PlayButtons(t: Title, p: Progress?, ref: PlayLine?, toSimilar: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val set = Lines.get(t.id)
    val n = set?.lines?.size ?: 0
    val searching = set == null || !set.done
    val firstName = ref?.episodes?.takeIf { it.isNotEmpty() && !Eps.numeric(it, t.name, t.isVariety) }?.first()?.name
    val tg = if (p == null) Target(1, firstName, false) else Resume.target(t, p, ref?.episodes)
    val ep = tg?.ep ?: p!!.ep
    val epName = if (tg != null) tg.name else p!!.epName
    val epLabel = tg?.text(t) ?: p!!.epText(t.name)
    val (label, small) = when {
        Sources.empty -> "先添加片源" to null
        n == 0 && searching -> "正在找线路" to set?.let { if (it.total > 0) "${it.searched}/${it.total}" else null }
        n == 0 -> "暂无线路" to null
        p == null -> (if (t.isEpisodic) "播放 $epLabel" else "播放") to null
        tg == null -> (if (t.isEpisodic) "重看 $epLabel" else "重看") to null
        !tg.resume -> "播放 $epLabel" to "新"
        t.isEpisodic -> "继续播放 $epLabel" to fmtTime(p.positionSec)
        else -> "继续播放" to fmtTime(p.positionSec)
    }
    Column(Modifier.padding(horizontal = PC.Edge, vertical = 8.dp)) {
        BigButton(
            Modifier.fillMaxWidth(), primary = n > 0 || Sources.empty, height = 48.dp,
            onClick = {
                when {
                    n > 0 -> nav.play(t.id, ep, epName)
                    Sources.empty -> nav.openSources()
                    searching -> nav.toast("还在各个站点里找，找到第一条就能播")
                    else -> {
                        nav.toast(set?.error ?: "这个片源里没找到《${t.name}》，重新找一遍")
                        Lines.find(ctx, t, force = true)
                    }
                }
            },
        ) { fg ->
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp), tint = fg)
            Tx(label, 16.sp, fg, FontWeight.Bold)
            small?.let { Tx(it, 12.sp, fg.copy(alpha = 0.6f)) }
        }
        if (p != null && p.fraction > 0f && !p.finishedEp) {
            ThinProgress(p.fraction, Modifier.padding(horizontal = 6.dp, vertical = 6.dp).clip(RoundedCornerShape(2.dp)), Palette.Douban)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val wished = t.id in Store.wish
            BigButton(Modifier.weight(1f), height = 42.dp, onClick = { Store.toggleWish(t.id); nav.toast(if (wished) "已移出想看" else "已加入想看") }) { fg ->
                Icon(if (wished) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(17.dp), tint = if (wished) Palette.Star else fg)
                Tx(if (wished) "已想看" else "想看", 14.sp, fg, FontWeight.SemiBold)
            }
            when {
                n > 0 -> BigButton(Modifier.weight(1f), height = 42.dp, onClick = { nav.play(t.id, ep, epName, openLines = true) }) { fg ->
                    Tx("线路 $n", 14.sp, fg, FontWeight.SemiBold)
                    if (searching) Tx("找…", 11.sp, fg.copy(alpha = 0.55f))
                }
                !searching && !Sources.empty -> BigButton(Modifier.weight(1f), height = 42.dp, onClick = { nav.openSources() }) { fg ->
                    Tx("换片源", 14.sp, fg, FontWeight.SemiBold)
                }
                else -> {}
            }
            BigButton(Modifier.weight(1f), height = 42.dp, onClick = toSimilar) { fg -> Tx("找类似", 14.sp, fg, FontWeight.SemiBold) }
        }
        if (n == 0 && !searching && !Sources.empty) {
            Tx(set?.error ?: "「${Sources.name}」的 ${set?.total ?: 0} 个站点里都没找到。可以换个片源，或者在搜索里搜片源", 12.sp, Palette.T3, lines = 3, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** 选集：按序号的是数字格子（集数多的分段），按名字的（综艺）是名字列表，正片、加更、花絮分开 */
@Composable
private fun Episodes(t: Title, p: Progress?, line: PlayLine?) {
    val nav = LocalNav.current
    val versions = remember(Lines.get(t.id)?.ranking, t.id) { Lines.versions(Lines.get(t.id), t.name) }
    val numericLine = line == null || line.episodes.isEmpty() || Eps.numeric(line.episodes, t.name, t.isVariety)
    val vs = versionChoices(t, line, numericLine, versions)
    Column(Modifier.padding(top = 14.dp)) {
        if (numericLine) NumberedEpisodes(t, p, line, vs) { ep -> nav.play(t.id, ep, null) }
        else NamedEpisodes(t, p, line!!, vs) { i, name -> nav.play(t.id, i, name) }
    }
}

/** 「换一套名字」：选集按别的线路的分集名字显示 */
@Composable
private fun VersionPill(t: Title, line: PlayLine?, vs: List<PlayLine>) {
    if (vs.size < 2 || line == null) return
    val i = vs.indexOfFirst { it.key == line.key }
    Pill("换一套名字 ${if (i >= 0) i + 1 else 1}/${vs.size}", false) {
        val k = (i + 1).mod(vs.size)
        Lines.choose(t.id, if (k == 0) null else vs[k].key)
    }
}

@Composable
private fun NumberedEpisodes(t: Title, p: Progress?, line: PlayLine?, vs: List<PlayLine>, play: (Int) -> Unit) {
    val lineMax = line?.episodes?.maxOfOrNull { Eps.number(it.name, t.name) ?: 0 } ?: 0
    val aired = maxOf(t.airedEps, lineMax)
    val total = maxOf(t.totalEps, aired)
    val no = p?.episodeNo(t.name)
    val cur = p?.let { pr ->
        val tg = Resume.target(t, pr, line?.episodes)
        tg?.name?.let { Eps.number(it, t.name) } ?: tg?.ep?.takeIf { tg.name == null } ?: no
    } ?: 1
    val sp = remember(total) { spans(total, 30) { a, b -> "${a + 1}–$b" } }
    var si by rememberSaveable(total) { mutableIntStateOf(sp.indexOfFirst { cur - 1 in it.from until it.to }.coerceAtLeast(0)) }
    Section("选集", "共 $total 集" + if (aired < total) " · 更新至 $aired" else "")
    if (vs.isNotEmpty()) Row(Modifier.padding(horizontal = PC.Edge, vertical = 4.dp)) { VersionPill(t, line, vs) }
    if (sp.isNotEmpty()) PillRow(sp.map { it.label }, si, Modifier.padding(vertical = 6.dp)) { si = it }
    val range = if (sp.isEmpty()) 1..total else (sp[si].from + 1)..sp[si].to
    Grid(range.toList(), 6, Modifier.padding(horizontal = PC.Edge, vertical = 6.dp)) { ep ->
        val locked = ep > aired
        val seen = no != null && (ep < no || (ep == no && p!!.finishedEp))
        val isCur = ep == cur && p != null
        val isNew = ep == aired && aired < total && (no == null || ep > no)
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.fillMaxWidth().height(42.dp).clip(shape)
                .then(
                    when {
                        locked -> Modifier.dashedBorder(Color.White.copy(alpha = 0.15f), 8.dp)
                        isCur -> Modifier.background(Palette.T1)
                        else -> Modifier.background(Color.White.copy(alpha = 0.08f))
                    },
                )
                .clickable(enabled = !locked) { play(ep) },
            contentAlignment = Alignment.Center,
        ) {
            Tx("$ep", 14.sp, when { isCur -> PC.Bg; locked || seen -> Palette.T3; else -> Palette.T1 }, FontWeight.SemiBold)
            if (isNew) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(6.dp).background(Palette.Bad, RoundedCornerShape(50)))
            if (isCur && p != null && !p.finishedEp && p.fraction > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.fraction).height(3.dp).background(Palette.Douban))
            }
        }
    }
}

@Composable
private fun NamedEpisodes(t: Title, p: Progress?, line: PlayLine, vs: List<PlayLine>, play: (Int, String) -> Unit) {
    val eps = line.episodes
    val w = namedWatched(t, p, line)
    val cur = if (w == null) 1 else if (p!!.finishedEp) (Eps.nextAfter(eps, w, t.name).takeIf { it >= 0 } ?: w) + 1 else w + 1
    val groups = remember(eps) { eps.indices.groupBy { Eps.group(eps[it].name, t.name) }.toSortedMap() }
    var g by rememberSaveable(line.key) { mutableIntStateOf(Eps.group(eps.getOrNull(cur - 1)?.name ?: "", t.name).takeIf { it in groups } ?: groups.firstKey()) }
    val shown = groups[g] ?: eps.indices.toList()
    val sp = remember(shown, eps) {
        spans(shown.size, 20) { a, b ->
            val x = spanLabel(eps[shown[a]].name, t.name)
            val y = spanLabel(eps[shown[b - 1]].name, t.name)
            if (x != null && y != null) "$x–$y" else "${a + 1}–$b"
        }
    }
    var si by rememberSaveable(line.key, g) { mutableIntStateOf(sp.indexOfFirst { shown.indexOf(cur - 1) in it.from until it.to }.coerceAtLeast(0)) }
    Section("选集", "${eps.size} 个 · ${line.siteName}")
    Row(Modifier.padding(horizontal = PC.Edge, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (groups.size > 1) groups.forEach { (k, v) -> Pill("${Eps.GROUPS[k]} ${v.size}", k == g) { g = k } }
        VersionPill(t, line, vs)
    }
    if (sp.isNotEmpty()) PillRow(sp.map { it.label }, si.coerceAtMost(sp.lastIndex), Modifier.padding(vertical = 6.dp)) { si = it }
    val part = if (sp.isEmpty()) shown else shown.subList(sp[si.coerceAtMost(sp.lastIndex)].from, sp[si.coerceAtMost(sp.lastIndex)].to)
    Grid(part, 2, Modifier.padding(horizontal = PC.Edge, vertical = 6.dp)) { i ->
        val isCur = i == cur - 1 && p != null
        val seen = w != null && i < w
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                .background(if (isCur) Palette.T1 else Color.White.copy(alpha = 0.08f))
                .clickable { play(i + 1, eps[i].name) }.padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Tx(Eps.fullLabel(eps[i].name, t.name), 13.sp, when { isCur -> PC.Bg; seen -> Palette.T3; else -> Palette.T1 }, FontWeight.Medium, lines = 2, lineHeight = 16.sp)
        }
    }
}

/** 等宽格子：[cols] 列，不用懒加载（选集一段最多几十个） */
@Composable
private fun <T> Grid(items: List<T>, cols: Int, modifier: Modifier = Modifier, cell: @Composable (T) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 简介（点开看全）、导演主演、豆瓣热度 */
@Composable
private fun About(t: Title) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 14.dp)) {
        Section("简介")
        val desc = t.desc.ifEmpty { if (t.full) "暂无简介" else "正在从豆瓣取简介…" }
        Tx(
            desc, 13.5.sp, Palette.T2, lines = if (open) Int.MAX_VALUE else 4, lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = PC.Edge).animateContentSize().clickable { open = !open },
        )
        if (!open && desc.length > 80) Tx("展开 ▾", 12.sp, Palette.T3, modifier = Modifier.padding(horizontal = PC.Edge, vertical = 4.dp).clickable { open = true })
        if (t.director.isNotEmpty()) Tx("导演  ${t.director}", 12.5.sp, Palette.T2, lines = 2, modifier = Modifier.padding(start = PC.Edge, end = PC.Edge, top = 8.dp))
        if (t.cast.isNotEmpty()) Tx("主演  " + t.cast.joinToString(" / "), 12.5.sp, Palette.T3, lines = 3, lineHeight = 18.sp, modifier = Modifier.padding(start = PC.Edge, end = PC.Edge, top = 4.dp))
        Catalog.heat(t.id)?.let { h ->
            Row(
                Modifier.padding(horizontal = PC.Edge, vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PC.Card).padding(vertical = 12.dp),
            ) {
                listOf("想看" to h.wish, "在看" to h.doing, "看过" to h.done).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Tx(if (v > 0) fmtWan(v) else "—", 16.sp, weight = FontWeight.Bold)
                        Tx("人$k", 11.sp, Palette.T3)
                    }
                }
            }
            h.betterThan?.let { Tx(it, 12.sp, Palette.Douban, modifier = Modifier.padding(horizontal = PC.Edge).padding(bottom = 4.dp)) }
        }
    }
}

/** 找类似：喜欢这部的也喜欢 / 同导演 / 同主演 / 同标签 */
@Composable
private fun Similar(t: Title) {
    val nav = LocalNav.current
    var src by rememberSaveable { mutableStateOf(SimilarSource.ALSO_LIKED) }
    val list = Catalog.similar(t, src)
    Column(Modifier.padding(top = 10.dp)) {
        Section("找类似")
        PillRow(SimilarSource.entries.map { it.label }, src.ordinal) { src = SimilarSource.entries[it] }
        if (list.isEmpty()) {
            Tx(if (t.full) "没找到" else "正在找…", 13.sp, Palette.T3, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
        } else {
            LazyRow(
                Modifier.padding(top = 10.dp),
                contentPadding = PaddingValues(horizontal = PC.Edge),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { s -> PosterTile(s, Modifier.width(104.dp), onLong = { nav.posterMenu(s) }) { nav.openDetail(s.id) } }
            }
        }
    }
}
