package dev.personal.tv.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.personal.tv.audio.AudioFx
import dev.personal.tv.source.Live
import dev.personal.tv.source.Net
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.OnAppHidden
import dev.personal.tv.ui.components.Chip
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.PillButton
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 直播标签：左边分组，右边频道。OK 全屏播放 */
@Composable
fun LiveScreen(mem: FocusMemory) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Sources.url) { Live.load() }
    var gi by rememberSaveable { mutableIntStateOf(0) }
    val groups = Live.groups
    if (gi >= groups.size) gi = 0

    Column(Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 70.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("直播", 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            if (Live.sources.size > 1) Live.sources.forEachIndexed { i, s ->
                Chip(
                    s.name, i == Live.sourceIdx, { scope.launch { Live.selectSource(i) } }, Modifier.remembered(mem, "lsrc:$i"),
                    idleBg = Color.White.copy(alpha = 0.1f), selectedBg = Color.White.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.weight(1f))
            T(
                when {
                    Live.loading -> "正在加载频道…"
                    groups.isNotEmpty() -> "${groups.size} 个分组 · ${groups.sumOf { it.channels.size }} 个频道 · 片源「${Sources.name}」"
                    else -> ""
                },
                10.sp, Palette.T3,
            )
        }
        when {
            Sources.empty -> Empty("还没有片源", "直播频道来自片源配置里的 lives。先加一个片源。", "添加片源") { nav.openSources() }
            Live.loading && groups.isEmpty() -> {}
            Live.sources.isEmpty() -> Empty("「${Sources.name}」没有直播源", "这个片源配置里没有 lives。可以在片源页换一个带直播的配置。", "管理片源") { nav.openSources() }
            groups.isEmpty() -> Empty("频道没加载出来", Live.error ?: "", "重试") { scope.launch { Live.load(force = true) } }
            else -> Row(Modifier.padding(top = 14.dp).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                LazyColumn(Modifier.width(170.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = Dimens.SafeV)) {
                    itemsIndexed(groups, key = { i, g -> "$i:${g.name}" }) { i, g ->
                        Focusable(
                            onClick = { gi = i },
                            modifier = Modifier.fillMaxWidth().remembered(mem, "g:$i", isDefault = i == gi),
                            corner = 8.dp, scale = 1.03f, ring = false,
                            onFocus = { gi = i },
                        ) { f ->
                            Row(
                                Modifier.fillMaxWidth().height(36.dp)
                                    .background(when { f -> Color.White; i == gi -> Palette.Ghost; else -> Color.Transparent })
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                T(g.name, 12.sp, if (f) Palette.Bg else Palette.T1, if (i == gi) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                T("${g.channels.size}", 9.5.sp, if (f) Palette.Bg.copy(alpha = 0.5f) else Palette.T3)
                            }
                        }
                    }
                }
                val g = groups[gi]
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = Dimens.SafeV, start = 4.dp, end = 4.dp),
                ) {
                    itemsIndexed(g.channels, key = { i, c -> "$gi:$i:${c.name}" }) { i, c ->
                        Focusable(
                            onClick = { nav.playLive(gi, i) },
                            modifier = Modifier.remembered(mem, "c:$gi:$i", isDefault = i == 0),
                            corner = 10.dp, scale = 1.05f,
                        ) { f ->
                            Column(
                                Modifier.fillMaxWidth().height(64.dp)
                                    .background(if (c.name == Live.lastChannel) Palette.Ghost else Palette.Ghost6)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                T(c.name, 13.sp, Palette.T1, FontWeight.SemiBold)
                                T(
                                    (if (c.name == Live.lastChannel) "上次看的 · " else "") + if (c.urls.size > 1) "${c.urls.size} 个信号" else "",
                                    9.sp, Palette.T3,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, sub: String, action: String, onClick: () -> Unit) {
    Column(Modifier.padding(top = 60.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(title, 18.sp, weight = FontWeight.Bold)
        T(sub, 11.sp, Palette.T2, lines = 3)
        PillButton(onClick, Modifier.padding(top = 10.dp), primary = true) { fg -> T(action, 12.sp, fg, FontWeight.SemiBold) }
    }
}

/**
 * 全屏直播。↑↓ 换台，←→ 换信号，OK 频道列表，返回退出。
 * 一个信号播不了自动试下一个。
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayer(r: Route.LivePlay) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    val groups = Live.groups
    var gi by remember { mutableIntStateOf(r.group.coerceIn(0, (groups.size - 1).coerceAtLeast(0))) }
    var ci by remember { mutableIntStateOf(r.channel) }
    var ui by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>("正在连接…") }
    var banner by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showBanner by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf(false) }
    /** 真在出画面（含缓冲）：只有这时候不让电视进屏保 */
    var awake by remember { mutableStateOf(false) }
    /** 最后一次按遥控器；太久没按（关了电视盒子还开着）就停播 */
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var asleep by remember { mutableStateOf(false) }
    val ch = groups.getOrNull(gi)?.channels?.getOrNull(ci)

    val player = remember {
        val h = Live.headers()
        val ua = h.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val ds = OkHttpDataSource.Factory(Net.client)
            .setUserAgent(ua ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .setDefaultRequestProperties(h.filterKeys { !it.equals("User-Agent", true) })
        ExoPlayer.Builder(ctx, AudioFx.renderers(ctx)).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
    }

    fun tune() {
        val c = groups.getOrNull(gi)?.channels?.getOrNull(ci) ?: return
        val u = c.urls.getOrNull(ui) ?: return
        status = "正在连接…"
        asleep = false
        Live.lastChannel = c.name
        val item = MediaItem.Builder().setUri(u).apply {
            if (u.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (u.contains(".mpd", true)) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        banner = System.currentTimeMillis()
    }

    fun move(d: Int) {
        if (groups.isEmpty()) return
        var g = gi; var c = ci + d
        while (c < 0 || c >= groups[g].channels.size) {
            if (c < 0) { g = (g - 1 + groups.size) % groups.size; c = groups[g].channels.size - 1 }
            else { g = (g + 1) % groups.size; c = 0 }
        }
        gi = g; ci = c; ui = 0
        tune()
    }

    fun signal(d: Int) {
        val n = ch?.urls?.size ?: return
        if (n <= 1) { banner = System.currentTimeMillis(); return }
        ui = (ui + d + n) % n
        tune()
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                when (s) {
                    Player.STATE_BUFFERING -> if (status == null) status = "缓冲中…"
                    Player.STATE_READY -> status = null
                }
                awake = s == Player.STATE_READY || s == Player.STATE_BUFFERING
            }
            override fun onPlayWhenReadyChanged(p: Boolean, reason: Int) {
                awake = p && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            }
            override fun onPlayerError(e: PlaybackException) {
                android.util.Log.w("LivePlayer", "error ${ch?.name} #$ui", e)
                val c = groups.getOrNull(gi)?.channels?.getOrNull(ci) ?: return
                if (ui + 1 < c.urls.size) { ui++; tune(); return }
                awake = false
                status = "「${c.name}」暂时看不了（${why(e)}）。↑↓ 换台" + if (c.urls.size > 1) "，←→ 再试别的信号" else ""
            }
        }
        player.addListener(l)
        tune()
        onDispose { player.removeListener(l); player.release() }
    }
    // 按主页键、进屏保：直播停掉（不在后台出声、不白跑流量），回来重新连当前台
    OnAppHidden(onHide = { player.stop() }, onShow = { tune() })
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
    // 4 个小时没按过遥控器：多半是电视关了、盒子没关，停掉别白跑流量、别一直亮屏
    LaunchedEffect(lastKey) {
        delay(4 * 3600_000L)
        player.stop()
        asleep = true
        status = "已经 4 个小时没操作，停播了。按 OK 继续看"
    }
    LaunchedEffect(banner) { showBanner = true; delay(4000); showBanner = false }
    BackHandler(enabled = overlay) { overlay = false; runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { lastKey = System.currentTimeMillis(); false }
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (overlay || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (asleep && e.key in listOf(Key.DirectionCenter, Key.Enter)) { tune(); return@onKeyEvent true }
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> move(-1)
                    Key.DirectionDown, Key.ChannelDown -> move(1)
                    Key.DirectionLeft -> signal(-1)
                    Key.DirectionRight -> signal(1)
                    Key.DirectionCenter, Key.Enter, Key.Menu -> overlay = true
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    this.player = player
                    isFocusable = false
                }
            },
            update = { it.keepScreenOn = awake },
            modifier = Modifier.fillMaxSize(),
        )
        if (!overlay) status?.let { T(it, 13.sp, Palette.T2, modifier = Modifier.align(Alignment.Center)) }
        AnimatedVisibility(showBanner && !overlay && ch != null, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 26.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    T(groups.getOrNull(gi)?.name.orEmpty(), 11.sp, Palette.T3)
                    T(ch?.name.orEmpty(), 24.sp, weight = FontWeight.Bold)
                    if ((ch?.urls?.size ?: 0) > 1) T("信号 ${ui + 1}/${ch!!.urls.size}", 10.sp, Palette.T2, modifier = Modifier.padding(top = 2.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Hint("↑↓", "换台", 9.5.sp)
                    Hint("←→", "换信号", 9.5.sp)
                    Hint("OK", "频道列表", 9.5.sp)
                }
            }
        }
        if (overlay) ChannelPicker(gi, ci) { g, c ->
            overlay = false
            if (g != gi || c != ci) { gi = g; ci = c; ui = 0; tune() }
            runCatching { focus.requestFocus() }
        }
    }
}

@OptIn(UnstableApi::class)
private fun why(e: PlaybackException): String {
    var c: Throwable? = e
    while (c != null) {
        if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return "信号源返回 ${c.responseCode}"
        c = c.cause
    }
    return when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连不上信号源"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "格式不认识"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "这台电视解不了这个编码"
        else -> e.errorCodeName.removePrefix("ERROR_CODE_").lowercase()
    }
}

/** 播放中的频道列表：左分组右频道，当前频道默认聚焦 */
@Composable
private fun ChannelPicker(curG: Int, curC: Int, onPick: (Int, Int) -> Unit) {
    val groups = Live.groups
    var g by remember { mutableIntStateOf(curG) }
    val cur = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (curC - 3).coerceAtLeast(0))
    Row(
        Modifier.fillMaxHeight().width(470.dp).background(Palette.Panel).padding(vertical = 30.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyColumn(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(groups) { i, grp ->
                Focusable(
                    onClick = { g = i }, modifier = Modifier.fillMaxWidth(), corner = 8.dp, scale = 1.03f, ring = false, onFocus = { g = i },
                ) { f ->
                    Box(
                        Modifier.fillMaxWidth().height(34.dp)
                            .background(when { f -> Color.White; i == g -> Palette.Ghost; else -> Color.Transparent })
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { T(grp.name, 11.5.sp, if (f) Palette.Bg else Palette.T1, if (i == g) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val chs = groups.getOrNull(g)?.channels.orEmpty()
            itemsIndexed(chs, key = { i, c -> "$g:$i:${c.name}" }) { i, c ->
                val playing = g == curG && i == curC
                Focusable(
                    onClick = { onPick(g, i) },
                    modifier = Modifier.fillMaxWidth().then(if (playing) Modifier.focusRequester(cur) else Modifier),
                    corner = 8.dp, scale = 1.03f, ring = false,
                ) { f ->
                    Row(
                        Modifier.fillMaxWidth().height(38.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(c.name, 12.5.sp, if (f) Palette.Bg else Palette.T1, if (playing) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (playing) T("正在看", 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.Douban)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { cur.requestFocus() } }
}
