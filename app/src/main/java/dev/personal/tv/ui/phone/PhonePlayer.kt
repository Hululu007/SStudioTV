package dev.personal.tv.ui.phone

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.source.Eps
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PlayLine
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.Backdrop
import dev.personal.tv.ui.components.OnAppHidden
import dev.personal.tv.ui.components.fmtTime
import dev.personal.tv.ui.screens.PlaySession
import dev.personal.tv.ui.screens.PlayerPrefs
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Panel { EPISODES, LINES, SPEED }
private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/**
 * 播放页（手机）：横屏全屏。线路、解析、换线和电视共用 [PlaySession]。
 * 手势：单击显示 / 收起控制栏；双击左边退 10 秒、右边进 10 秒、中间暂停；
 * 左右滑拖进度；左半边上下滑调亮度，右半边调音量。右上角锁住以后只认单击（显示解锁按钮）。
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePlayer(r: Route.Player) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val t = Catalog.get(r.id) ?: return
    val scope = rememberCoroutineScope()
    val ps = remember { PlaySession(ctx, t, r.ep, r.epName, scope) }
    DisposableEffect(Unit) { onDispose { ps.release() } }
    val activity = currentActivity()
    FullScreenLandscape(activity)

    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(if (r.openLines) Panel.LINES else null) }
    var locked by remember { mutableStateOf(false) }
    var poke by remember { mutableLongStateOf(0L) }
    /** 手势正在调的东西：拖进度、亮度、音量（显示在屏幕中间） */
    var gesture by remember { mutableStateOf<String?>(null) }
    /** 拖进度条时先不跳，松手再跳 */
    var dragPos by remember { mutableStateOf<Float?>(null) }

    OnAppHidden(onHide = { ps.onHidden() }, onShow = { ps.onShown(); controls = true })
    LaunchedEffect(ps.notice) { ps.notice?.let { nav.toast(it); ps.notice = null } }
    LaunchedEffect(controls, poke, panel, ps.playing, dragPos) {
        if (controls && panel == null && ps.playing && dragPos == null) { delay(4500); controls = false }
    }
    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = panel == null && locked) { controls = true; poke = System.nanoTime() }

    val pos = ((dragPos?.toLong() ?: ps.posMs) / 1000).toInt()
    val dur = (ps.durMs / 1000).toInt()
    fun touch() { ps.touch(); poke = System.nanoTime() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    player = ps.player
                    setKeepContentOnPlayerReset(false)
                }
            },
            update = { it.keepScreenOn = ps.awake },
            modifier = Modifier.fillMaxSize(),
        )
        if (ps.status != null && ps.posMs == 0L || ps.fail != null) {
            Backdrop(t, Modifier.fillMaxSize().graphicsLayer { alpha = 0.3f }, silhouette = false, alignment = Alignment.Center)
        }

        // ---------------- 手势层 ----------------
        GestureLayer(
            ps, locked, activity,
            onTap = { touch(); controls = !controls; if (panel != null) panel = null },
            onGesture = { gesture = it; touch() },
        )

        // ---------------- 中间的提示 ----------------
        ps.status?.let { st ->
            if (ps.fail == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Tx(st, 15.sp, Palette.T1, FontWeight.SemiBold)
                val n = ps.set.lines.size
                if (n > 0) Tx("已找到 $n 条线路 · 点「线路」可以手动选", 12.sp, Palette.T3, modifier = Modifier.padding(top = 6.dp))
            }
        }
        ps.fail?.let {
            Dialog(Modifier.align(Alignment.Center), "播放不了", it) {
                BigButton(height = 38.dp, primary = true, onClick = { ps.retry() }) { fg -> Tx("重试", 14.sp, fg, FontWeight.SemiBold) }
                BigButton(height = 38.dp, onClick = { panel = Panel.LINES }) { fg -> Tx("换线路", 14.sp, fg, FontWeight.SemiBold) }
                BigButton(height = 38.dp, onClick = { nav.back() }) { fg -> Tx("退出", 14.sp, fg, FontWeight.SemiBold) }
            }
        }
        if (ps.askContinue && ps.fail == null) {
            Dialog(Modifier.align(Alignment.Center), "还在看吗？", "已经连着自动放了好几集，先停在这里。") {
                BigButton(height = 38.dp, primary = true, onClick = { ps.keepWatching() }) { fg -> Tx("放下一集", 14.sp, fg, FontWeight.SemiBold) }
                BigButton(height = 38.dp, onClick = { nav.back() }) { fg -> Tx("退出", 14.sp, fg, FontWeight.SemiBold) }
            }
        }
        gesture?.let {
            Tx(it, 17.sp, Color.White, FontWeight.Bold, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp))
        }

        // ---------------- 控制栏 ----------------
        AnimatedVisibility(controls && panel == null, enter = fadeIn(tween(160)), exit = fadeOut(tween(260))) {
            if (locked) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                    RoundIcon(Modifier.align(Alignment.CenterStart).padding(start = 20.dp), lit = true, onClick = { locked = false; touch() }) {
                        Icon(Icons.Filled.Lock, "解锁", Modifier.size(20.dp), tint = PC.Bg)
                    }
                }
            } else Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
                }.windowInsetsPadding(WindowInsets.displayCutout),
            ) {
                // 顶栏
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { nav.back() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Tx(t.name + if (t.isEpisodic) "  " + ps.epText() else "", 16.sp, Color.White, FontWeight.Bold)
                        Tx(ps.line?.let { "${it.siteName} · ${it.flag}" } ?: "还没有线路", 11.5.sp, Palette.T2)
                    }
                }
                RoundIcon(Modifier.align(Alignment.CenterStart).padding(start = 20.dp), onClick = { locked = true; panel = null; touch() }) {
                    Icon(Icons.Filled.Lock, "锁定", Modifier.size(20.dp), tint = Color.White)
                }
                // 中间：播放 / 暂停
                if (ps.status == null && ps.fail == null) {
                    Box(
                        Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                            .clickable { ps.togglePlay(); touch() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (ps.playing) PauseBars(26.dp) else Icon(Icons.Filled.PlayArrow, "播放", Modifier.size(38.dp), tint = Color.White)
                    }
                }
                // 底栏：进度 + 按钮
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
                    val sliderColors = SliderDefaults.colors(
                        thumbColor = Color.White, activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        disabledThumbColor = Color.White.copy(alpha = 0.3f), disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tx(fmtTime(pos), 12.sp, Color.White, modifier = Modifier.width(56.dp))
                        Slider(
                            value = if (dur > 0) pos.toFloat() / dur else 0f,
                            onValueChange = { v -> if (dur > 0) { dragPos = v * ps.durMs; touch() } },
                            onValueChangeFinished = {
                                dragPos?.let { d -> ps.seekBy(((d.toLong() - ps.player.currentPosition) / 1000).toInt()) }
                                dragPos = null
                            },
                            enabled = dur > 0,
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = sliderColors,
                            thumb = { Box(Modifier.size(14.dp).background(if (dur > 0) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)) },
                            track = { st ->
                                SliderDefaults.Track(
                                    st, Modifier.height(3.dp), colors = sliderColors, enabled = dur > 0,
                                    drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                                )
                            },
                        )
                        Tx(if (dur > 0) fmtTime(dur) else "--:--", 12.sp, Color.White, modifier = Modifier.padding(start = 10.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).clickable { ps.togglePlay(); touch() }, contentAlignment = Alignment.Center) {
                            if (ps.playing) PauseBars(18.dp) else Icon(Icons.Filled.PlayArrow, "播放", Modifier.size(28.dp), tint = Color.White)
                        }
                        if (t.isEpisodic && ps.nextName != null) CtlText("下一集") { ps.playNext(); touch() }
                        Spacer(Modifier.weight(1f))
                        CtlText(if (ps.speed == 1f) "倍速" else "${fmtSpeed(ps.speed)}x") { panel = Panel.SPEED }
                        if (t.isEpisodic && ps.episodeCount > 1) CtlText("选集") { panel = Panel.EPISODES }
                        CtlText("线路 ${ps.set.lines.size}") { panel = Panel.LINES }
                    }
                }
            }
        }

        // ---------------- 右侧面板 ----------------
        AnimatedVisibility(
            panel != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(180)),
        ) {
            Column(
                Modifier.width(340.dp).fillMaxHeight().background(Color(0xF014171C))
                    .windowInsetsPadding(WindowInsets.displayCutout).padding(top = 18.dp),
            ) {
                when (panel) {
                    Panel.EPISODES -> EpisodePanel(ps) { i, name -> ps.playEpisode(i, name); panel = null }
                    Panel.LINES -> LinePanel(ps, pos) { l ->
                        panel = null
                        ps.switchLine(l)
                        nav.toast("切换到 ${l.siteName} · ${l.flag}" + if (pos > 0) "，从 ${fmtTime(pos)} 继续" else "")
                    }
                    Panel.SPEED -> SpeedPanel(ps)
                    null -> {}
                }
            }
        }
    }
}

/** 进来横屏全屏，走的时候恢复竖屏、状态栏和亮度 */
@Composable
internal fun FullScreenLandscape(activity: Activity?) {
    DisposableEffect(activity) {
        val a = activity ?: return@DisposableEffect onDispose { }
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = WindowCompat.getInsetsController(a.window, a.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            a.window.attributes = a.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            a.requestedOrientation = UiMode.browseOrientation(a)
        }
    }
}

/** 单击、双击、左右拖进度、上下调亮度音量 */
@Composable
private fun GestureLayer(ps: PlaySession, locked: Boolean, activity: Activity?, onTap: () -> Unit, onGesture: (String?) -> Unit) {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = if (locked) null else { o ->
                        val w = size.width
                        when {
                            o.x < w / 3f -> { ps.seekBy(-10); onGesture("后退 10 秒") }
                            o.x > w * 2 / 3f -> { ps.seekBy(10); onGesture("前进 10 秒") }
                            else -> ps.togglePlay()
                        }
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var mode = 0 // 0 还没定、1 进度、2 亮度、3 音量
                var startX = 0f
                var dx = 0f
                var dy = 0f
                var startMs = 0L
                var target = 0L
                var bright = 0.5f
                var vol = 0f
                val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                detectDragGestures(
                    onDragStart = { o ->
                        mode = 0; dx = 0f; dy = 0f; startX = o.x
                        startMs = ps.player.currentPosition.coerceAtLeast(0)
                        target = startMs
                        bright = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                            ?: runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
                        vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    },
                    onDragEnd = {
                        if (mode == 1 && ps.durMs > 0) ps.seekBy(((target - ps.player.currentPosition) / 1000).toInt())
                        onGesture(null)
                    },
                    onDragCancel = { onGesture(null) },
                ) { change, d ->
                    change.consume()
                    dx += d.x; dy += d.y
                    if (mode == 0) {
                        if (abs(dx) < 24f && abs(dy) < 24f) return@detectDragGestures
                        mode = if (abs(dx) > abs(dy)) 1 else if (startX < size.width / 2f) 2 else 3
                    }
                    when (mode) {
                        1 -> {
                            val dur = ps.durMs
                            if (dur <= 0) return@detectDragGestures
                            // 整屏宽 = 3 分钟（短片按片长算）
                            val span = minOf(dur, 180_000L)
                            target = (startMs + (dx / size.width * span).toLong()).coerceIn(0, dur)
                            val diff = (target - startMs) / 1000
                            onGesture("${if (diff >= 0) "+" else "-"}${fmtTime(abs(diff).toInt())}   ${fmtTime((target / 1000).toInt())} / ${fmtTime((dur / 1000).toInt())}")
                        }
                        2 -> {
                            val b = (bright - dy / size.height).coerceIn(0.02f, 1f)
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = b } }
                            onGesture("亮度 ${(b * 100).toInt()}%")
                        }
                        3 -> {
                            val v = (vol - dy / size.height * maxVol * 1.2f).coerceIn(0f, maxVol.toFloat())
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.toInt(), 0)
                            onGesture("音量 ${(v / maxVol * 100).toInt()}%")
                        }
                    }
                }
            },
    )
}

@Composable
private fun Dialog(modifier: Modifier, title: String, text: String, buttons: @Composable () -> Unit) {
    Column(modifier.width(380.dp).background(Color(0xF01A1D23), RoundedCornerShape(14.dp)).padding(20.dp)) {
        Tx(title, 17.sp, if (title == "播放不了") Palette.Bad else Palette.T1, FontWeight.Bold)
        Tx(text, 13.sp, Palette.T2, lines = 4, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

@Composable
private fun RoundIcon(modifier: Modifier, lit: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(if (lit) Color.White else Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CtlText(text: String, onClick: () -> Unit) =
    Tx(text, 14.sp, Color.White, FontWeight.SemiBold, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp))

@Composable
private fun PauseBars(h: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(h * 0.28f)) {
        repeat(2) { Box(Modifier.size(h * 0.28f, h).background(Color.White, RoundedCornerShape(2.dp))) }
    }
}

private fun fmtSpeed(s: Float) = if (s % 1f == 0f) "${s.toInt()}" else "$s".trimEnd('0')

@Composable
private fun PanelTitle(title: String, sub: String) {
    Tx(title, 17.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
    Tx(sub, 11.5.sp, Palette.T3, lines = 2, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp))
}

/** 选集：当前线路里的分集 */
@Composable
private fun EpisodePanel(ps: PlaySession, pick: (Int, String) -> Unit) {
    val l = ps.line
    if (l == null) { PanelTitle("选集", "还没有线路"); return }
    val numeric = ps.numericLine != false
    PanelTitle("选集", "${l.episodes.size} ${if (numeric) "集" else "个"} · ${l.siteName}")
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (ps.ep - 1 - if (numeric) 10 else 4).coerceAtLeast(0))
    LazyVerticalGrid(
        GridCells.Fixed(if (numeric) 5 else 2), state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(l.episodes) { i, e ->
            val cur = i + 1 == ps.ep
            Box(
                Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (cur) Palette.T1 else Color.White.copy(alpha = 0.08f))
                    .clickable { pick(i + 1, e.name) }.padding(horizontal = 8.dp),
                contentAlignment = if (numeric) Alignment.Center else Alignment.CenterStart,
            ) {
                Tx(
                    if (numeric) (Eps.number(e.name, ps.t.name)?.toString() ?: Eps.label(e.name, ps.t.name)) else Eps.fullLabel(e.name, ps.t.name),
                    13.sp, if (cur) PC.Bg else Palette.T1, FontWeight.Medium, lines = 2, lineHeight = 15.sp,
                )
            }
        }
    }
}

/** 线路：按自动选的顺序排，红点是试过不行的 */
@Composable
private fun LinePanel(ps: PlaySession, pos: Int, pick: (PlayLine) -> Unit) {
    val set = ps.set
    val cur = ps.line
    val lines = Lines.ordered(set, cur?.key, emptyList())
    PanelTitle("线路", "找到 ${lines.size} 条" + (if (!set.done) " · 还在搜 ${set.searched}/${set.total}" else " · ${set.total} 个站点") + " · 片源「${Sources.name}」")
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Tx("自动换线路", 14.sp)
            Tx("解析不出、出错或卡住 20 秒换下一条", 11.sp, Palette.T3)
        }
        var auto by remember { mutableStateOf(Prefs.autoLine) }
        PhoneSwitch(auto) { auto = it; PlayerPrefs.autoLine = it }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(lines, key = { it.key }) { l ->
            val isCur = l.key == cur?.key
            val bad = set.failed[l.key]
            Row(
                Modifier.fillMaxWidth().clickable { pick(l) }.background(if (isCur) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(when { bad != null -> Palette.Bad; l.direct -> Palette.Douban; else -> Palette.Star }, CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp).graphicsLayer { alpha = if (bad != null && !isCur) 0.55f else 1f }) {
                    Tx("${l.siteName} · ${l.flag}", 14.sp, if (isCur) Palette.Douban else Palette.T1, if (isCur) FontWeight.SemiBold else FontWeight.Normal)
                    Tx(
                        bad ?: ("${l.episodes.size} ${if (Eps.numeric(l.episodes, ps.t.name, ps.t.isVariety)) "集" else "个"} · ${if (l.direct) "直链" else "需解析"}" + if (l.remarks.isNotBlank()) " · ${l.remarks.take(14)}" else ""),
                        11.5.sp, Palette.T3,
                    )
                }
                if (isCur) Tx("当前", 11.sp, Palette.Douban)
                else if (Prefs.rank(l.siteKey) != null) Tx("优先", 11.sp, Palette.Star)
            }
        }
    }
}

/** 倍速和声音 */
@Composable
private fun SpeedPanel(ps: PlaySession) {
    PanelTitle("倍速", "当前 ${fmtSpeed(ps.speed)}x")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SPEEDS.forEach { s ->
            val on = s == ps.speed
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Palette.T1 else Color.White.copy(alpha = 0.08f))
                    .clickable { ps.setRate(s) },
                contentAlignment = Alignment.Center,
            ) { Tx("${fmtSpeed(s)}x", 13.sp, if (on) PC.Bg else Palette.T1, FontWeight.SemiBold) }
        }
    }
    Tx("声音", 17.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp))
    var night by remember { mutableStateOf(PlayerPrefs.nightVolume) }
    var level by remember { mutableStateOf(PlayerPrefs.levelLines) }
    var dialog by remember { mutableStateOf(PlayerPrefs.dialogBoost) }
    SoundRow("夜间音量", "压缩响度差：爆炸声不炸，对白不小", night) { night = it; PlayerPrefs.nightVolume = it }
    SoundRow("线路间音量统一", "换线路、换集时音量保持一致", level) { level = it; PlayerPrefs.levelLines = it }
    SoundRow("对白增强", "人声频段略微提亮", dialog) { dialog = it; PlayerPrefs.dialogBoost = it }
}

@Composable
private fun SoundRow(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Tx(title, 14.sp)
            Tx(sub, 11.sp, Palette.T3)
        }
        PhoneSwitch(on, set)
    }
}

@Composable
fun PhoneSwitch(on: Boolean, set: (Boolean) -> Unit) = Switch(
    on, set,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = Palette.Douban,
        uncheckedThumbColor = Palette.T2, uncheckedTrackColor = Color.White.copy(alpha = 0.12f), uncheckedBorderColor = Color.Transparent,
    ),
)
