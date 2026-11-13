package dev.personal.tv.ui.phone

import android.view.ViewGroup
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import dev.personal.tv.audio.AudioFx
import dev.personal.tv.source.Channel
import dev.personal.tv.source.Live
import dev.personal.tv.source.Net
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.OnAppHidden
import dev.personal.tv.ui.screens.why
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 直播：上面切直播源和分组，下面是频道列表，点了横屏全屏放 */
@Composable
fun PhoneLive() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Sources.url) { Live.load() }
    var gi by rememberSaveable { mutableIntStateOf(0) }
    val groups = Live.groups
    if (gi >= groups.size) gi = 0

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = PC.Edge, end = PC.Edge, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Tx("直播", 22.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            Tx(
                when {
                    Live.loading -> "正在加载频道…"
                    groups.isNotEmpty() -> "${groups.size} 组 · ${groups.sumOf { it.channels.size }} 个频道"
                    else -> ""
                },
                11.5.sp, Palette.T3, modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        if (Live.sources.size > 1) {
            PillRow(Live.sources.map { it.name }, Live.sourceIdx, Modifier.padding(top = 4.dp)) { i -> scope.launch { Live.selectSource(i) } }
        }
        when {
            Sources.empty -> Empty("还没有片源", "直播频道来自片源配置里的 lives，先加一个片源", button = "添加片源") { nav.openSources() }
            Live.loading && groups.isEmpty() -> CenterNote("正在加载频道…")
            Live.sources.isEmpty() -> Empty("「${Sources.name}」没有直播源", "这个片源配置里没有 lives，可以换一个带直播的片源", button = "管理片源") { nav.openSources() }
            groups.isEmpty() -> Empty("频道没加载出来", Live.error.orEmpty(), button = "重试") { scope.launch { Live.load(force = true) } }
            else -> {
                PillRow(groups.map { it.name }, gi, Modifier.padding(top = 8.dp), strong = true) { gi = it }
                val g = groups[gi]
                val list = rememberLazyListState()
                LaunchedEffect(gi) { runCatching { list.scrollToItem(0) } }
                LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(g.channels, key = { i, c -> "$gi:$i:${c.name}" }) { i, c ->
                        ChannelRow(c, c.name == Live.lastChannel) { nav.playLive(gi, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(c: Channel, last: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (last) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .padding(horizontal = PC.Edge, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(58.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(PC.Card2), contentAlignment = Alignment.Center) {
            var failed by remember(c.logo) { mutableStateOf(false) }
            var shown by remember(c.logo) { mutableStateOf(false) }
            if (!shown) Tx(c.name.take(6), 10.sp, Palette.T3, FontWeight.Bold)
            if (c.logo != null && !failed) {
                AsyncImage(
                    c.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit,
                    onSuccess = { shown = true }, onError = { failed = true },
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Tx(c.name, 15.sp, weight = FontWeight.Medium)
            val sub = listOfNotNull("上次看的".takeIf { last }, "${c.urls.size} 个信号".takeIf { c.urls.size > 1 }).joinToString(" · ")
            if (sub.isNotEmpty()) Tx(sub, 11.5.sp, if (last) Palette.Douban else Palette.T3)
        }
    }
}

/**
 * 直播全屏（手机）：横屏。单击显示 / 收起控制栏，控制栏上换台、换信号、频道列表。
 * 一个信号播不了自动试下一个。
 */
@OptIn(UnstableApi::class)
@Composable
fun PhoneLivePlayer(r: Route.LivePlay) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    FullScreenLandscape(currentActivity())
    val groups = Live.groups
    var gi by remember { mutableIntStateOf(r.group.coerceIn(0, (groups.size - 1).coerceAtLeast(0))) }
    var ci by remember { mutableIntStateOf(r.channel) }
    var ui by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>("正在连接…") }
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableLongStateOf(0L) }
    var list by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(false) }
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
        Live.lastChannel = c.name
        val item = MediaItem.Builder().setUri(u).apply {
            if (u.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (u.contains(".mpd", true)) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
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
        poke = System.nanoTime()
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
            override fun onPlayerError(e: PlaybackException) {
                val c = groups.getOrNull(gi)?.channels?.getOrNull(ci) ?: return
                if (ui + 1 < c.urls.size) { ui++; tune(); return }
                awake = false
                status = "「${c.name}」暂时看不了（${why(e)}），换个台试试"
                controls = true
            }
        }
        player.addListener(l)
        tune()
        onDispose { player.removeListener(l); player.release() }
    }
    OnAppHidden(onHide = { player.stop() }, onShow = { tune() })
    LaunchedEffect(controls, poke, list) { if (controls && !list) { delay(4500); controls = false } }
    BackHandler(enabled = list) { list = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    this.player = player
                }
            },
            update = { it.keepScreenOn = awake },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { if (list) list = false else { controls = !controls; poke = System.nanoTime() } } })
        status?.let { Tx(it, 14.sp, Palette.T2, modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp), lines = 3) }

        AnimatedVisibility(controls && !list, enter = fadeIn(tween(160)), exit = fadeOut(tween(260))) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
                }.windowInsetsPadding(WindowInsets.displayCutout),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { nav.back() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Tx(ch?.name.orEmpty(), 17.sp, Color.White, FontWeight.Bold)
                        Tx(
                            groups.getOrNull(gi)?.name.orEmpty() + if ((ch?.urls?.size ?: 0) > 1) " · 信号 ${ui + 1}/${ch!!.urls.size}" else "",
                            11.5.sp, Palette.T2,
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiveBtn("上一台") { move(-1) }
                    LiveBtn("下一台") { move(1) }
                    if ((ch?.urls?.size ?: 0) > 1) LiveBtn("换信号") {
                        val n = ch!!.urls.size
                        ui = (ui + 1) % n; tune(); poke = System.nanoTime()
                    }
                    Spacer(Modifier.weight(1f))
                    LiveBtn("频道列表") { list = true }
                }
            }
        }

        AnimatedVisibility(
            list, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(180)),
        ) {
            ChannelPanel(gi, ci) { g, c ->
                list = false
                if (g != gi || c != ci) { gi = g; ci = c; ui = 0; tune() }
            }
        }
    }
}

@Composable
private fun LiveBtn(text: String, onClick: () -> Unit) =
    Tx(text, 14.sp, Color.White, FontWeight.SemiBold, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp))

/** 播放中的频道列表：上面分组，下面频道 */
@Composable
private fun ChannelPanel(curG: Int, curC: Int, pick: (Int, Int) -> Unit) {
    val groups = Live.groups
    var g by remember { mutableIntStateOf(curG) }
    Column(
        Modifier.width(340.dp).fillMaxHeight().background(Color(0xF014171C))
            .windowInsetsPadding(WindowInsets.displayCutout).padding(top = 14.dp),
    ) {
        PillRow(groups.map { it.name }, g) { g = it }
        val st = rememberLazyListState(initialFirstVisibleItemIndex = if (g == curG) (curC - 3).coerceAtLeast(0) else 0)
        LaunchedEffect(g) { if (g != curG) runCatching { st.scrollToItem(0) } }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), state = st, contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(groups.getOrNull(g)?.channels.orEmpty(), key = { i, c -> "$g:$i:${c.name}" }) { i, c ->
                val playing = g == curG && i == curC
                Row(
                    Modifier.fillMaxWidth().height(46.dp).clickable { pick(g, i) }
                        .background(if (playing) Color.White.copy(alpha = 0.08f) else Color.Transparent).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tx(c.name, 14.sp, if (playing) Palette.Douban else Palette.T1, if (playing) FontWeight.SemiBold else FontWeight.Normal, Modifier.weight(1f))
                    if (playing) Tx("正在看", 11.sp, Palette.Douban)
                    else if (c.urls.size > 1) Tx("${c.urls.size} 个信号", 11.sp, Palette.T3)
                }
            }
        }
    }
}
