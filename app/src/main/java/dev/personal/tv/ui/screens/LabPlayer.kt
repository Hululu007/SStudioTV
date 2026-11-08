package dev.personal.tv.ui.screens

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import dev.personal.tv.source.Net
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.components.OnAppHidden
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.fmtTime
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/** 真实播放（调试版）：Media3 + 片源要求的请求头。OK 暂停，左右 ±10 秒 */
@OptIn(UnstableApi::class)
@Composable
fun LabPlayer(r: Route.LabPlay) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    var state by remember { mutableStateOf("缓冲中…") }
    var error by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var showBar by remember { mutableStateOf(true) }
    var poke by remember { mutableLongStateOf(0L) }
    var agc by remember { mutableStateOf(0.0) }

    val player = remember {
        val ua = r.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val ds = OkHttpDataSource.Factory(Net.client)
            .setUserAgent(ua ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .setDefaultRequestProperties(r.headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(r.url).apply {
            when {
                r.format == "dash" -> setMimeType(MimeTypes.APPLICATION_MPD)
                r.format == "hls" || r.url.contains("m3u8", true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }.build()
        ExoPlayer.Builder(ctx, AudioFx.renderers(ctx)).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build().apply {
            setMediaItem(item)
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                state = when (s) {
                    Player.STATE_BUFFERING -> "缓冲中…"
                    Player.STATE_READY -> ""
                    Player.STATE_ENDED -> "播完了"
                    else -> state
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                error = "${e.errorCodeName}\n${root.javaClass.simpleName}: ${root.message?.take(160)}"
                android.util.Log.w("LabPlayer", "play ${r.url}", e)
            }
            override fun onIsPlayingChanged(p: Boolean) { if (!p) showBar = true }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(Unit) {
        delay(50); runCatching { focus.requestFocus() }
        while (true) {
            pos = player.currentPosition; dur = player.duration.coerceAtLeast(0); agc = AudioFx.agcDb
            delay(500)
        }
    }
    OnAppHidden(onHide = { player.pause() })
    LaunchedEffect(poke) { delay(3500); if (player.isPlaying) showBar = false }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter -> { if (player.isPlaying) player.pause() else player.play() }
                    Key.DirectionLeft -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    Key.DirectionRight -> player.seekTo(player.currentPosition + 10_000)
                    Key.DirectionUp -> AudioFx.night = !AudioFx.night
                    Key.DirectionDown -> AudioFx.level = !AudioFx.level
                    else -> return@onKeyEvent false
                }
                showBar = true; poke++
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
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.isNotBlank() && error == null) {
            T(state, 13.sp, Palette.T2, modifier = Modifier.align(Alignment.Center))
        }
        error?.let {
            Column(
                Modifier.align(Alignment.Center).background(Palette.Panel, RoundedCornerShape(12.dp)).padding(18.dp),
            ) {
                T("播放失败", 15.sp, Palette.Bad, FontWeight.Bold)
                T(it, 10.sp, Palette.T2, lines = 4, modifier = Modifier.padding(top = 6.dp))
                T(r.url.take(120), 9.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (showBar) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 48.dp, vertical = 18.dp),
            ) {
                T(r.title, 15.sp, weight = FontWeight.Bold)
                T(
                    "${fmtTime((pos / 1000).toInt())} / ${if (dur > 0) fmtTime((dur / 1000).toInt()) else "--"}" +
                        (r.format?.let { "  ·  $it" } ?: "") + (if (r.parse) "  ·  片源标记为需要解析，先直接试播" else "") + "  ·  OK 暂停  ←→ 10 秒",
                    10.sp, Palette.T2, modifier = Modifier.padding(top = 4.dp),
                )
                T(
                    "↑ 夜间音量 ${if (AudioFx.night) "开" else "关"}  ·  ↓ 音量拉平 ${if (AudioFx.level) "开（%+.1f dB）".format(agc) else "关"}",
                    10.sp, if (AudioFx.night || AudioFx.level) Palette.Star else Palette.T2, modifier = Modifier.padding(top = 3.dp),
                )
                T(r.url.take(110), 8.5.sp, Palette.T3, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
