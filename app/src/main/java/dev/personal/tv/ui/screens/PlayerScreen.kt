package dev.personal.tv.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Progress
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.Chip
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.Toggle
import dev.personal.tv.ui.components.Backdrop
import dev.personal.tv.ui.components.OnAppHidden
import dev.personal.tv.source.Eps
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PlayLine
import dev.personal.tv.source.Sources
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.personal.tv.ui.components.fmtTime
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/** 播放设置，全局记住 */
object PlayerPrefs {
    // 声音处理是真的（audio/AudioFx），会记住
    var nightVolume: Boolean get() = dev.personal.tv.audio.AudioFx.night; set(v) { dev.personal.tv.audio.AudioFx.night = v }
    var levelLines: Boolean get() = dev.personal.tv.audio.AudioFx.level; set(v) { dev.personal.tv.audio.AudioFx.level = v }
    var dialogBoost: Boolean get() = dev.personal.tv.audio.AudioFx.dialog; set(v) { dev.personal.tv.audio.AudioFx.dialog = v }
    var autoLine: Boolean get() = dev.personal.tv.data.Prefs.autoLine; set(v) { dev.personal.tv.data.Prefs.autoLine = v }
}

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/**
 * 播放页：Media3 真播放，线路来自多源合并（[Lines]）。
 * - 无控制栏时：OK 暂停 / 继续（同时露出控制栏，还在加载时只露控制栏），上下 呼出控制栏，左 后退、右 快进（点一下 ±10 秒；按住预览目标位置、松手才跳一次；直播不能跳）；
 *   还没出画面（解析中、播放失败）时右键打开线路面板。有画面时换线路在控制栏的「线路」里
 * - 控制栏 5 秒无操作自动收起；返回键逐层收起，最后退出播放。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(r: Route.Player) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val t = Catalog.get(r.id) ?: return
    val scope = rememberCoroutineScope()
    val ps = remember { PlaySession(ctx, t, r.ep, r.epName, scope, selected = r.selected, resumeAtMs = r.resumeAtMs) }
    DisposableEffect(Unit) { onDispose { ps.release() } }

    var controls by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(r.openLines) }
    var strip by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(0L) }
    var focusBar by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    // 线路面板里上次停的那一行（关了再开还回到那儿；没有就是当前线路）
    var sheetFocus by remember { mutableStateOf<String?>(null) }
    // 快进快退手势：单击 ±10 秒；按住预览目标位置，松手才提交一次
    val gesture = remember { SeekGesture() }
    var preview by remember { mutableStateOf<Long?>(null) }
    var holding by remember { mutableStateOf(false) }
    fun cancelSeek() { gesture.cancel(); preview = null; holding = false }
    val idle = !controls && !sheet && !strip
    // 续播时 posMs 一开始就是上次的位置、playing 是 playWhenReady，都不能说明有画面；线路真起播了才算
    val hasPicture = ps.phase == PlaySession.Phase.PLAY
    val pos = (ps.posMs / 1000).toInt()
    val dur = (ps.durMs / 1000).toInt()
    val shownPos = preview?.let { (it / 1000).toInt() } ?: pos
    val seekOk = hasPicture && ps.fail == null && !ps.askContinue && !sheet && !strip

    val catcher = remember { FocusRequester() }
    val playReq = remember { FocusRequester() }
    val barReq = remember { FocusRequester() }
    val epReq = remember { FocusRequester() }

    // 按主页键、进屏保：暂停（不然在后台接着出声）；回来时停在暂停、把控制条亮出来
    OnAppHidden(onHide = { cancelSeek(); ps.onHidden() }, onShow = { ps.onShown(); controls = true })
    LaunchedEffect(ps.notice) { ps.notice?.let { nav.toast(it); ps.notice = null } }
    LaunchedEffect(controls, lastInput, sheet, strip) {
        if (controls && !sheet && !strip) { delay(5000); controls = false }
    }
    // 手势进行中画面没了、弹了面板、出错等：丢弃预览，不提交
    LaunchedEffect(seekOk) { if (!seekOk) cancelSeek() }
    // 松手事件丢了（焦点被抢走等）：不再收到按键就丢弃
    LaunchedEffect(holding) {
        while (holding) { delay(200); if (gesture.stale(System.nanoTime() / 1_000_000)) cancelSeek() }
    }
    LaunchedEffect(idle) { if (idle) { withFrameNanos { }; runCatching { catcher.requestFocus() } } }
    LaunchedEffect(controls, strip) {
        if (controls && !strip) {
            withFrameNanos { }
            runCatching { if (focusBar) barReq.requestFocus() else playReq.requestFocus() }
            focusBar = false
        }
    }
    LaunchedEffect(strip) { if (strip) { withFrameNanos { }; runCatching { epReq.requestFocus() } } }

    BackHandler(enabled = !idle) {
        when {
            gesture.active -> cancelSeek()
            sheet -> sheet = false
            strip -> strip = false
            else -> controls = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent {
            lastInput = System.nanoTime()
            ps.touch()
            // 遥控器上单独的播放、暂停、快进、快退、下一集键（语音「暂停」也是发这几个键）
            val up = it.type == KeyEventType.KeyUp
            val down = it.type == KeyEventType.KeyDown
            val seekDir = when (it.key) {
                Key.DirectionRight, Key.MediaFastForward -> 1
                Key.DirectionLeft, Key.MediaRewind -> -1
                else -> 0
            }
            val dirKey = it.key == Key.DirectionLeft || it.key == Key.DirectionRight
            // 方向键：空闲、或焦点在进度条上时才是快进快退（焦点在按钮上时左右是挪焦点）；手势进行中不管焦点在哪
            val seekKey = seekDir != 0 && seekOk && (!dirKey || idle || barFocused || gesture.active)
            if (seekKey) {
                val now = System.nanoTime() / 1_000_000
                val live = ps.player.isCurrentMediaItemLive
                val seekable = !live && (ps.durMs <= 0 || ps.player.isCurrentMediaItemSeekable)
                if (down) {
                    val wasActive = gesture.active
                    if (gesture.down(it.key, seekDir, now, ps.player.currentPosition, ps.durMs, seekable)) {
                        preview = if (gesture.previewing) gesture.targetMs else null
                        holding = true
                        if (!wasActive) { if (dirKey && idle) focusBar = true; controls = true }
                    }
                } else if (up && gesture.active) {
                    gesture.up(it.key, ps.player.currentPosition)?.let { target ->
                        val d = ((target - ps.player.currentPosition) / 1000.0).roundToInt()
                        if (d != 0) ps.seekBy(d)
                    }
                    // 别的键的松开不结束手势：还在按着原来的键，预览和超时检查都要保留
                    if (!gesture.active) { preview = null; holding = false }
                }
                true
            } else when (it.key) {
                Key.MediaPlayPause -> { if (up) ps.togglePlay(); true }
                Key.MediaPlay -> { if (up && !ps.playing) ps.togglePlay(); true }
                Key.MediaPause -> { if (up && ps.playing) ps.togglePlay(); true }
                Key.MediaFastForward, Key.MediaRewind -> true
                Key.MediaNext -> { if (up && t.isEpisodic) ps.playNext(); true }
                else -> false
            }
        },
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    player = ps.player
                    isFocusable = false
                    setKeepContentOnPlayerReset(false)
                }
            },
            // 真在播（含缓冲）时不让电视进屏保；暂停、播完、出错、问「还在看吗」时随它
            update = { it.keepScreenOn = ps.awake },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // 还没出画面时：模糊海报 + 提示
        if (ps.status != null && ps.posMs == 0L || ps.fail != null) {
            Backdrop(t, Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f }, silhouette = false, alignment = Alignment.Center)
        }
        ps.status?.let { st ->
            if (ps.fail == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                T(st, 13.sp, Palette.T1, FontWeight.SemiBold)
                ps.detail()?.let { T(it, 10.5.sp, Palette.T2, modifier = Modifier.padding(top = 6.dp)) }
                if (ps.set.lines.isNotEmpty() || !ps.discovered) {
                    T(if (hasPicture) "控制栏的「线路」里可以手动选" else "按右键可以手动选线路", 9.5.sp, Palette.T3, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        // 出画面以后右上角小字：暂停时看缓冲了多少、几点了；中途卡住时看是不是还在下数据
        ps.cornerText()?.let {
            T(it, 10.sp, Palette.T1, modifier = Modifier.align(Alignment.TopEnd).padding(top = Dimens.SafeV, end = Dimens.SafeH)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        ps.fail?.let {
            Column(
                Modifier.align(Alignment.Center).width(460.dp).background(Palette.Panel, RoundedCornerShape(12.dp)).padding(20.dp),
            ) {
                T("播放不了", 15.sp, Palette.Bad, FontWeight.Bold)
                T(it, 11.sp, Palette.T2, lines = 3, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                T("OK：重试  ·  右键：线路面板  ·  " + (if (ps.loginRequired) "下：去登录网盘  ·  " else "") + "返回：退出", 9.5.sp, Palette.T3, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (ps.askContinue && ps.fail == null) {
            Column(
                Modifier.align(Alignment.Center).width(460.dp).background(Palette.Panel, RoundedCornerShape(12.dp)).padding(20.dp),
            ) {
                T("还在看吗？", 15.sp, Palette.T1, FontWeight.Bold)
                T("已经连着自动放了好几集，先停在这里。", 11.sp, Palette.T2, modifier = Modifier.padding(top = 6.dp))
                T("OK：放下一集  ·  返回：退出", 9.5.sp, Palette.T3, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (!ps.playing && ps.status == null && ps.fail == null && ps.posMs > 0) {
            PauseGlyph(Modifier.align(Alignment.Center), 40.dp)
        }

        // 只在空闲时接收按键的隐形焦点
        var okDown by remember { mutableStateOf(false) }
        // 有画面（在播、或者已经播过一段）：OK 暂停 / 继续，左右快退快进；没画面时右键直接去选线路
        Box(
            Modifier.size(1.dp).focusRequester(catcher)
                .focusProperties { canFocus = idle }
                .onKeyEvent { e ->
                    if (!idle) return@onKeyEvent false
                    val down = e.type == KeyEventType.KeyDown
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (down) okDown = true else if (okDown) {
                                okDown = false
                                when {
                                    ps.fail != null -> ps.retry()
                                    ps.askContinue -> ps.keepWatching()
                                    // 有画面了：OK 一下就暂停 / 继续，同时露出控制栏（焦点在播放键，再按还是暂停 / 继续）
                                    hasPicture || ps.status == null && ps.line != null -> { ps.togglePlay(); controls = true }
                                    else -> controls = true
                                }
                            }
                            true
                        }
                        // 明确是要登录网盘才播不了：下键 = 用户自己决定去登录（不自动弹扫码），回来接着这一集这条线路这个位置
                        Key.DirectionDown -> {
                            if (down) {
                                if (ps.loginRequired) nav.openPansFromPlayer(ps.ep, ps.epName, ps.loginTarget(), ps.here()) else controls = true
                            }
                            true
                        }
                        Key.DirectionUp -> { if (down) controls = true; true }
                        // 有画面时左右由上面的快进快退手势处理；没画面时右键去线路面板
                        Key.DirectionRight -> {
                            if (down && !(hasPicture && ps.fail == null)) { ps.discover(); sheet = true }
                            true
                        }
                        Key.DirectionLeft -> true
                        else -> false
                    }
                }
                .focusable(),
        )

        AnimatedVisibility(!idle && !sheet, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)))
                })
                Column(Modifier.padding(start = Dimens.SafeH, top = Dimens.SafeV)) {
                    T(t.name, 17.sp, weight = FontWeight.Bold)
                    val where = ps.line?.let { "${it.siteName} · ${it.flag}" } ?: "还没有线路"
                    T((if (t.isEpisodic) ps.epText() + " · " else "") + where, 11.sp, Palette.T2, modifier = Modifier.padding(top = 2.dp))
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(start = Dimens.SafeH, end = Dimens.SafeH, bottom = Dimens.SafeV),
                ) {
                    if (strip) {
                        EpisodeRow(t, Progress(t.id, ps.epNumber, pos, dur, 0, ps.line?.key, epName = ps.epName, named = ps.numericLine == false), ps.line, epReq, null, Modifier.offset(x = -Dimens.SafeH).padding(bottom = 8.dp)) { e, name ->
                            ps.playEpisode(e, name); strip = false
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                        T(fmtTime(shownPos), 11.sp, if (preview != null) Palette.T1 else Palette.T2, if (preview != null) FontWeight.SemiBold else FontWeight.Normal)
                        Spacer(Modifier.weight(1f))
                        T(if (dur > 0) fmtTime(dur) else "--:--", 11.sp, Palette.T2)
                    }
                    SeekBar(shownPos, dur, barReq) { barFocused = it }
                    ControlButtons(
                        t, ps, playReq,
                        onEpisodes = { strip = true },
                        onLines = { controls = false; ps.discover(); sheet = true },
                        onNext = { ps.playNext() },
                    )
                }
            }
        }

        AnimatedVisibility(
            sheet, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            // 还没出画面（在找线路、解析）时播放器的位置是 0，按「看到哪儿」算
            val at = (ps.here() / 1000).toInt()
            LineSheet(ps, at, sheetFocus, onFocusRow = { sheetFocus = it }, onClose = { sheet = false }) { l ->
                sheet = false; sheetFocus = null
                ps.switchLine(l)
                nav.toast("切换到 ${l.siteName} · ${l.flag}" + if (at > 0) "，从 ${fmtTime(at)} 继续" else "")
            }
        }
    }
}

@Composable
private fun PauseGlyph(modifier: Modifier, size: Dp, color: Color = Color.White) {
    Row(modifier.size(size), horizontalArrangement = Arrangement.spacedBy(size * 0.2f, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size * 0.22f, size * 0.7f).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.size(size * 0.22f, size * 0.7f).background(color, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun SeekBar(pos: Int, dur: Int, req: FocusRequester, onFocus: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f = if (dur == 0) 0f else (pos.toFloat() / dur).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(16.dp).focusRequester(req)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (focused) 5.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(h).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(f).height(h).background(Color.White, RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(f).height(16.dp)) {
            val k = if (focused) 16.dp else 12.dp
            Box(Modifier.align(Alignment.CenterEnd).offset(x = k / 2).size(k).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun ControlButtons(
    t: Title, ps: PlaySession, playReq: FocusRequester,
    onEpisodes: () -> Unit, onLines: () -> Unit, onNext: () -> Unit,
) {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Focusable({ ps.togglePlay() }, Modifier.focusRequester(playReq), corner = 14.dp, scale = 1.1f, ring = false) { f ->
            Box(Modifier.size(28.dp).background(if (f) Color.White else Color.Transparent), contentAlignment = Alignment.Center) {
                if (ps.playing) PauseGlyph(Modifier, 16.dp, if (f) Palette.Bg else Color.White)
                else Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = if (f) Palette.Bg else Color.White)
            }
        }
        if (t.isEpisodic && ps.episodeCount > 1) CtlChip("选集") { onEpisodes() }
        if (!ps.discovered || ps.set.lines.isNotEmpty()) CtlChip(if (ps.discovered) "线路 ${ps.set.lines.size}" else "线路") { onLines() }
        CtlChip("倍速 ${"%.2f".format(ps.speed).trimEnd('0').trimEnd('.')}×", lit = ps.speed != 1f) {
            ps.setRate(SPEEDS[(SPEEDS.indexOf(ps.speed) + 1) % SPEEDS.size])
        }
        CtlChip("夜间音量 · ${if (PlayerPrefs.nightVolume) "开" else "关"}", lit = PlayerPrefs.nightVolume) {
            PlayerPrefs.nightVolume = !PlayerPrefs.nightVolume
            nav.toast(if (PlayerPrefs.nightVolume) "夜间音量已开：压低爆炸声，对白听得清" else "夜间音量已关")
        }
        Spacer(Modifier.weight(1f))
        ps.nextName?.let { n -> if (t.isEpisodic) CtlChip("下一集 · " + ps.nextText(n)) { onNext() } }
    }
}

@Composable
private fun CtlChip(text: String, lit: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick, corner = 12.dp, scale = 1.05f, ring = false) { f ->
        Row(
            Modifier.heightIn(min = 24.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (lit) Box(Modifier.size(5.dp).background(Palette.Star, CircleShape))
            T(text, 11.sp, when { f -> Palette.Bg; lit -> Palette.T1; else -> Palette.T2 }, if (f || lit) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

// ---------------------------------------------------------------------------
// 线路面板：线路 / 声音，视频不暂停
// ---------------------------------------------------------------------------

private val SHEET_TABS = listOf("线路", "声音")

@Composable
private fun LineSheet(ps: PlaySession, pos: Int, prior: String?, onFocusRow: (String) -> Unit, onClose: () -> Unit, onPick: (PlayLine) -> Unit) {
    val focus = LocalFocusManager.current
    var tab by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val set = ps.set
    val cur = ps.line
    val lines = Lines.ordered(set, cur?.key, emptyList())
    // 打开时落在上次停的那条线路，没有就是当前线路，再没有才是第一条
    val focusKey = listOf(prior, cur?.key).firstOrNull { k -> k != null && lines.any { it.key == k } } ?: lines.firstOrNull()?.key
    val listState = rememberLazyListState()
    // 打开线路面板就要开始找线路（没开过的话）
    LaunchedEffect(Unit) { ps.discover() }
    LaunchedEffect(Unit) {
        val i = lines.indexOfFirst { it.key == focusKey }
        if (i > 0) runCatching { listState.scrollToItem(i) }
        withFrameNanos { }; runCatching { first.requestFocus() }
    }
    Column(
        Modifier.width(340.dp).fillMaxHeight()
            .background(Color(0xF012151A))
            .onKeyEvent { e ->
                // 左键移不动了就收起面板
                if (e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown) {
                    if (!focus.moveFocus(FocusDirection.Left)) onClose()
                    true
                } else false
            }
            .padding(start = 28.dp, end = 28.dp, top = 32.dp, bottom = 27.dp),
    ) {
        T("播放设置", 19.sp, weight = FontWeight.Bold)
        T(
            cur?.let { "当前：${it.siteName} · ${it.flag} · 片源「${Sources.name}」" } ?: "片源「${Sources.name}」",
            10.5.sp, Palette.T3, modifier = Modifier.padding(top = 3.dp),
        )
        Row(Modifier.padding(top = 13.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SHEET_TABS.forEachIndexed { i, s ->
                Chip(s, tab == i, { tab = i }, if (i == 0 && lines.isEmpty()) Modifier.focusRequester(first) else Modifier, size = 10.5.sp, height = 22.dp, onFocus = { tab = i })
            }
        }
        when (tab) {
            0 -> {
                SheetToggle("自动换线路", "解析不出、播放出错或卡住 20 秒，自动换下一条", PlayerPrefs.autoLine) { PlayerPrefs.autoLine = !PlayerPrefs.autoLine }
                T(
                    if (lines.isEmpty() && !set.done) "正在找线路…" else "找到 ${lines.size} 条线路" + if (!set.done) " · 还在搜 ${set.searched}/${set.total}" else " · ${set.total} 个站点",
                    9.5.sp, Palette.T3, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(lines, key = { it.key }) { l ->
                        val isCur = l.key == cur?.key
                        val bad = set.failed[l.key]
                        val m = (if (l.key == focusKey) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.hasFocus) onFocusRow(l.key) }
                        val kind = if (l.direct) "直链" else "需解析"
                        SheetRow(
                            "${l.siteName} · ${l.flag}", bad ?: "${l.episodes.size} ${if (Eps.numeric(l.episodes, ps.t.name, ps.t.isVariety)) "集" else "个"} · $kind" + if (l.remarks.isNotBlank()) " · ${l.remarks.take(14)}" else "",
                            m, dim = bad != null && !isCur,
                            tag = when { isCur -> "当前"; dev.personal.tv.data.Prefs.rank(l.siteKey) != null -> "优先"; else -> null },
                            onClick = { onPick(l) },
                        ) { fg ->
                            Box(Modifier.size(6.dp).background(when { bad != null -> Palette.Bad; l.direct -> Palette.Douban; else -> Palette.Star }, CircleShape))
                        }
                    }
                }
            }
            else -> {
                SheetToggle("夜间音量", "压缩响度差：爆炸声不炸，对白不小", PlayerPrefs.nightVolume) { PlayerPrefs.nightVolume = !PlayerPrefs.nightVolume }
                SheetToggle("线路间音量统一", "换线路、换集时音量保持一致", PlayerPrefs.levelLines) { PlayerPrefs.levelLines = !PlayerPrefs.levelLines }
                SheetToggle("对白增强", "人声频段略微提亮", PlayerPrefs.dialogBoost) { PlayerPrefs.dialogBoost = !PlayerPrefs.dialogBoost }
                Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Hint("OK", if (pos > 0) "切换，从 ${fmtTime(pos)} 继续" else "切换", 9.5.sp)
            Hint("◀", "收起", 9.5.sp)
        }
    }
}

@Composable
private fun SheetRow(
    title: String, sub: String, modifier: Modifier, dim: Boolean = false, tag: String? = null,
    onClick: () -> Unit, trailing: @Composable (Color) -> Unit,
) {
    Focusable(onClick, modifier.fillMaxWidth().padding(vertical = 1.dp), corner = 9.dp, scale = 1.03f, ring = false) { f ->
        val fg = if (f) Palette.Bg else Palette.T1
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (f) Color.White else Color.Transparent)
                .padding(horizontal = 13.dp)
                .then(if (dim && !f) Modifier.drawBehind { } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(title, 12.5.sp, if (dim && !f) Palette.T3 else fg, FontWeight.SemiBold)
                    tag?.let {
                        T(
                            it, 8.sp, Palette.Douban, FontWeight.Bold,
                            Modifier.padding(start = 6.dp).background(Palette.Douban.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 0.5.dp),
                        )
                    }
                }
                T(sub, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3, lines = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { trailing(fg) }
        }
    }
}

@Composable
private fun SheetToggle(title: String, sub: String, on: Boolean, toggle: () -> Unit) {
    SheetRow(title, sub, Modifier, onClick = toggle) { fg -> Toggle(on, onLight = fg == Palette.Bg) }
}
