package dev.personal.tv.ui.screens

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.personal.tv.audio.AudioFx
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Progress
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.source.Episode
import dev.personal.tv.source.Eps
import dev.personal.tv.source.LineSet
import dev.personal.tv.source.Lines
import dev.personal.tv.source.Net
import dev.personal.tv.source.Parser
import dev.personal.tv.source.PlayInfo
import dev.personal.tv.source.PlayLine
import dev.personal.tv.source.Sites
import dev.personal.tv.source.Sources
import dev.personal.tv.source.TvConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * 一次播放：选线路 → 站点给地址 → 需要的话网页解析/嗅探 → Media3 播放。
 * 线路按「上次能播的 → 直链 → 普通网页 → VIP 网页」试；解析失败、播放出错、卡住 20 秒，自动换下一条（可关）。
 */
@OptIn(UnstableApi::class)
class PlaySession(private val ctx: Context, val t: Title, startEp: Int, startName: String?, private val scope: CoroutineScope) {
    private val TAG = "Play"
    val player: ExoPlayer = ExoPlayer.Builder(ctx, AudioFx.renderers(ctx)).build()
    val set: LineSet = Lines.find(ctx, t)

    /** 第几集：当前线路里的序号，从 1 开始 */
    var ep by mutableIntStateOf(startEp)
        private set
    /** 这一集在线路里的名字。换线路时按名字找同一集（综艺的上下期、加更，序号各站不一样） */
    var epName by mutableStateOf(startName)
        private set
    var line by mutableStateOf<PlayLine?>(null)
        private set
    /** 画面中间的提示（找线路、解析、缓冲），null = 正常播放 */
    var status by mutableStateOf<String?>("正在找线路…")
        private set
    /** 彻底播不了 */
    var fail by mutableStateOf<String?>(null)
        private set
    /** 用户想让它播（缓冲时也算在播），按钮和暂停图标看这个 */
    var playing by mutableStateOf(false)
        private set
    var posMs by mutableLongStateOf(0L)
        private set
    var durMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    /** 换线时给界面的提示 */
    var notice by mutableStateOf<String?>(null)

    private var cfg: TvConfig? = null
    private var job: Job? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val tried = HashSet<String>()
    private var bufferingSince = 0L
    /** 当前线路是用户手动选的 */
    private var pinned = false

    /** 当前线路的集数（没线路时按豆瓣） */
    val episodeCount get() = line?.episodes?.size ?: maxOf(t.airedEps, 1)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                when (s) {
                    Player.STATE_READY -> { ready?.complete(true); bufferingSince = 0; if (status == "缓冲中…") status = null }
                    Player.STATE_BUFFERING -> { if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis() }
                    Player.STATE_ENDED -> onEnded()
                }
            }
            override fun onPlayWhenReadyChanged(p: Boolean, reason: Int) { playing = p }
            override fun onPlayerError(e: PlaybackException) {
                Log.w(TAG, "error ${line?.key}", e)
                val r = ready
                if (r != null && r.isActive) r.complete(false) else failover("播放出错（${e.errorCodeName}）")
            }
        })
        val prev = Store.progress[t.id]
        val same = prev != null && if (startName != null && prev.epName != null) Eps.key(startName, t.name) == Eps.key(prev.epName, t.name) else prev.ep == startEp
        if (same && startName == null) epName = prev!!.epName
        val startMs = if (same && !prev!!.finishedEp) prev.positionSec * 1000L else 0L
        pinned = prev?.pinned == true
        start(startEp, startMs, prev?.line)
        scope.launch {
            var tick = 0
            while (true) {
                delay(500)
                posMs = player.currentPosition
                durMs = player.duration.coerceAtLeast(0)
                // 播放中途卡住太久也算这条线路不行
                if (status == null && bufferingSince > 0 && player.playbackState == Player.STATE_BUFFERING &&
                    System.currentTimeMillis() - bufferingSince > 20_000
                ) { bufferingSince = 0; failover("卡住超过 20 秒") }
                if (++tick % 20 == 0 && player.isPlaying) save()
            }
        }
    }

    /** 从头开始（换集、手动换线、自动换线都走这里） */
    private fun start(ep: Int, startMs: Long, prefer: String?, only: PlayLine? = null) {
        job?.cancel()
        this.ep = ep
        fail = null
        job = scope.launch { run(startMs, prefer, only) }
    }

    /**
     * 换集。[name] 不为 null：当前线路里的第 [n] 个、名字是 [name]（按名字选的）；
     * 为 null：第 [n] 集（按豆瓣集数选的，到线路里按名字里的数字找）。
     */
    fun playEpisode(n: Int, name: String?) {
        if (fail == null && (if (name != null) name == epName else n == epNumber)) return
        save()
        tried.clear()
        epName = name
        start(n, 0, line?.key)
    }

    fun playNext() {
        val l = line ?: return
        val e = nextEpisode ?: return
        playEpisode(l.episodes.indexOf(e) + 1, e.name)
    }

    /** 当前线路的分集按序号显示（电视剧）；false = 按名字（综艺）；还没线路 = null */
    val numericLine: Boolean? get() = line?.let { Eps.numeric(it.episodes, t.name, t.isVariety) }

    /** 这一集是第几集（名字里的数字）；名字不是序号的就是线路里的位置 */
    val epNumber: Int get() = epName?.takeIf { numericLine != false && Eps.isNumber(it, t.name) }
        ?.let { Eps.key(it, t.name).toIntOrNull() } ?: ep

    /** 「第 3 集」或者「11.01 上」 */
    fun epText(name: String? = epName, idx: Int = epNumber) = Eps.text(name, idx, t.name, numericLine)

    /** 当前线路的下一集，没有就是 null。按序号的线路找「第 n+1 集」，跳过夹在中间的花絮 */
    val nextEpisode: Episode? get() {
        val l = line ?: return null
        val n = epName
        if (numericLine == true && n != null && Eps.isNumber(n, t.name)) {
            val next = (Eps.key(n, t.name).toIntOrNull() ?: return null) + 1
            return l.episodes.firstOrNull { Eps.key(it.name, t.name) == next.toString() }
        }
        return l.episodes.getOrNull(ep)
    }

    /**
     * 这一集在某条线路里是第几个（1 开始），找不到 = null。
     * 有名字先按名字找；名字是序号的按数字找（线路里夹着「陪看」「彩蛋」时位置会错开）；
     * 按日期 / 期数分的线路找不到同名的就是没有，不按位置凑。
     */
    private fun indexIn(l: PlayLine): Int? {
        val n = epName ?: return Eps.indexOfNumber(l.episodes, ep, t.name).takeIf { it >= 0 }?.plus(1)
        val i = Eps.indexOf(l.episodes, n, t.name)
        if (i >= 0) return i + 1
        if (!Eps.isNumber(n, t.name) || Eps.dated(l.episodes)) return null
        val num = Eps.key(n, t.name).toIntOrNull() ?: return null
        return Eps.indexOfNumber(l.episodes, num, t.name).takeIf { it >= 0 }?.plus(1)
    }

    fun switchLine(l: PlayLine) {
        if (l.key == line?.key && fail == null) return
        tried.clear()
        set.failed.remove(l.key)
        pinned = true
        start(ep, player.currentPosition.coerceAtLeast(0), l.key, only = l)
    }

    /** App 在后台（主页键、屏保）：正在找线路、解析的，加载好了也先别播 */
    private var hidden = false

    /** 退到后台就暂停，不在后台出声 */
    fun onHidden() { hidden = true; player.pause(); save() }
    /** 回来了：停在暂停，按 OK 接着播 */
    fun onShown() { hidden = false }

    fun togglePlay() { if (player.playWhenReady) player.pause() else player.play() }
    fun seekBy(sec: Int) { player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0, durMs.takeIf { it > 0 } ?: Long.MAX_VALUE)); posMs = player.currentPosition }
    fun setRate(r: Float) { speed = r; player.setPlaybackSpeed(r) }

    fun save() {
        if (line == null || durMs <= 0) return
        Store.progress[t.id] = Progress(t.id, epNumber, (posMs / 1000).toInt(), (durMs / 1000).toInt(), System.currentTimeMillis(), line?.key, pinned, epName)
    }

    fun release() {
        save()
        job?.cancel()
        player.release()
    }

    private fun onEnded() {
        save()
        val next = nextEpisode
        if (t.isEpisodic && next != null) {
            notice = "自动播放" + epText(next.name, epNumber + 1).let { if (it.startsWith("第")) it else " $it" }
            playNext()
        }
    }

    private fun failover(why: String) {
        val l = line ?: return
        set.failed[l.key] = why
        tried += l.key
        pinned = false
        if (!Prefs.autoLine) {
            player.stop()
            fail = "${l.siteName} · ${l.flag} $why。按右键换一条线路"
            return
        }
        notice = "${l.siteName} $why，换下一条线路"
        start(ep, player.currentPosition.coerceAtLeast(0), null)
    }

    private suspend fun run(startMs: Long, prefer: String?, only: PlayLine?) {
        val c = cfg ?: try {
            Sources.config().also { cfg = it }
        } catch (e: Throwable) {
            fail = "片源配置加载失败：${e.message?.take(60)}"
            return
        }
        val t0 = System.currentTimeMillis()
        while (true) {
            // 线路是陆续搜到的：先等一会儿上次能播的那条（12 秒）、优先站点（15 秒，单站超时就是 15 秒）和直链（6 秒），
            // 别抓到第一条需要解析的就播
            if (only == null && !set.done) {
                val age = System.currentTimeMillis() - t0
                val waitPrev = prefer != null && set.lines.none { it.key == prefer } && age < 12_000
                val waitPreferred = Prefs.preferred.isNotEmpty() && age < 15_000 &&
                    set.lines.none { Prefs.rank(it.siteKey) != null && it.key !in tried } &&
                    Prefs.preferred.any { k -> k !in set.doneSites && c.sites.any { it.key == k } }
                val waitDirect = set.lines.none { it.direct && it.key !in tried } && age < 6_000
                if (waitPrev || waitPreferred || waitDirect) {
                    status = "正在找线路… 已搜 ${set.searched}/${set.total} 个站点"
                    delay(300)
                    continue
                }
            }
            val cand = only?.takeIf { it.key !in tried }
                ?: if (only != null) null
                else Lines.ordered(set, prefer, c.flags, pinned).firstOrNull { it.key !in tried && indexIn(it) != null }
            if (cand == null) {
                if (set.done || only != null) {
                    player.stop()
                    status = null
                    fail = when {
                        set.lines.isEmpty() -> set.error ?: "「${Sources.name}」的 ${set.total} 个站点里都没找到《${t.name}》"
                        set.lines.none { indexIn(it) != null } -> "所有线路都还没有「${epText()}」"
                        else -> "${tried.size} 条线路都试过了，都播不了"
                    }
                    return
                }
                status = if (set.total > 0) "正在找线路… 已搜 ${set.searched}/${set.total} 个站点" else "正在找线路…"
                delay(400)
                continue
            }
            tried += cand.key
            val i = indexIn(cand) ?: run {
                // 手动选的线路里没有这一集（别的线路按名字认出来的综艺分期）
                player.stop()
                status = null
                fail = "${cand.siteName} · ${cand.flag} 里没有「${epText()}」"
                return
            }
            ep = i
            epName = cand.episodes[i - 1].name
            if (tryLine(c, cand, cand.episodes[i - 1], startMs)) return
            if (!Prefs.autoLine && only == null && tried.size >= 1) {
                fail = "${cand.siteName} · ${cand.flag} 播不了（${set.failed[cand.key]}）。按右键换一条线路"
                status = null
                return
            }
            if (only != null) {
                fail = "${cand.siteName} · ${cand.flag} 播不了（${set.failed[cand.key]}）"
                status = null
                return
            }
        }
    }

    private suspend fun tryLine(c: TvConfig, l: PlayLine, e: Episode, startMs: Long): Boolean {
        line = l
        status = "正在解析 · ${l.siteName} · ${l.flag}"
        player.stop()
        val t0 = System.currentTimeMillis()
        val info = try {
            withTimeoutOrNull(45_000) { Lines.yieldTo { resolve(c, l, e) } }
        } catch (x: Throwable) {
            Log.w(TAG, "resolve ${l.key}", x); null
        }
        if (info == null) {
            set.failed[l.key] = "解析不出视频地址"
            Log.i(TAG, "✗ ${l.key} 解析失败 ${System.currentTimeMillis() - t0}ms")
            return false
        }
        status = "缓冲中…"
        val fmt = withContext(Dispatchers.IO) { format(info) }
        val ua = info.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val ds = OkHttpDataSource.Factory(Net.client)
            .setUserAgent(ua ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .setDefaultRequestProperties(info.headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(info.url).apply {
            when (fmt) {
                "dash" -> setMimeType(MimeTypes.APPLICATION_MPD)
                "hls" -> setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }.build()
        val r = CompletableDeferred<Boolean>()
        ready = r
        bufferingSince = 0
        player.setMediaSource(DefaultMediaSourceFactory(ds).createMediaSource(item), startMs)
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = !hidden
        val ok = withTimeoutOrNull(25_000) { r.await() } ?: false
        ready = null
        if (!ok) {
            set.failed.putIfAbsent(l.key, "播放失败")
            Log.i(TAG, "✗ ${l.key} 播放失败 ${info.url.take(80)}")
            player.stop()
            return false
        }
        status = null
        set.failed.remove(l.key)
        Log.i(TAG, "✓ ${l.key} 第 $ep 集「$epName」 ${System.currentTimeMillis() - t0}ms ${info.url.take(80)}")
        save()
        return true
    }

    private suspend fun resolve(c: TvConfig, l: PlayLine, e: Episode): PlayInfo? {
        val s = c.sites.firstOrNull { it.key == l.siteKey } ?: return null
        val api = withContext(Dispatchers.IO) { Sites.get(ctx, c, s) }
        val p = withContext(Dispatchers.IO) { api.play(l.flag, e.url) }
        if (!p.parse && !p.jx) return p
        status = "网页解析 · ${l.siteName} · ${l.flag}"
        return Parser.resolve(ctx, c, l.flag, p, withContext(Dispatchers.IO) { api.videoCheck() })?.info
    }

    /** 地址没扩展名时探一下开头几个字节，HLS/DASH 要告诉播放器 */
    private fun format(p: PlayInfo): String? {
        val u = p.url.lowercase()
        if (".m3u8" in u) return "hls"
        if (".mpd" in u) return "dash"
        if (Regex("\\.(mp4|mkv|flv|avi|mov)(\\?|$)").containsMatchIn(u)) return null
        return runCatching {
            val req = Request.Builder().url(p.url).header("Range", "bytes=0-2047")
            p.headers.forEach { (k, v) -> req.header(k, v) }
            if (p.headers.keys.none { it.equals("User-Agent", true) }) req.header("User-Agent", "Mozilla/5.0 (Linux; Android 12) Chrome/120")
            Net.client.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(req.build()).execute().use { r ->
                    val head = r.body?.byteStream()?.let { s -> ByteArray(2048).let { b -> String(b, 0, s.read(b).coerceAtLeast(0)) } }.orEmpty()
                    when {
                        head.startsWith("#EXTM3U") -> "hls"
                        "<MPD" in head -> "dash"
                        else -> null
                    }
                }
        }.getOrNull()
    }
}
