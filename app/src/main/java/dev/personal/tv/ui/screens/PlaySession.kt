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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import dev.personal.tv.media.Downloads
import dev.personal.tv.media.MediaCache
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
    // 耳机、蓝牙音箱断开时暂停，不突然从电视喇叭外放
    // 和后台下载共用优先级：播放器加载时后台下载先让路
    val player: ExoPlayer = ExoPlayer.Builder(ctx, AudioFx.renderers(ctx)).setHandleAudioBecomingNoisy(true)
        .build()
    /** 这部片的线路；断网时一条没搜到，重试时换成重新搜的 */
    var set: LineSet by mutableStateOf(Lines.find(ctx, t))
        private set

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
    /** 现在在哪一步：找线路、解析、首次缓冲、在播。加载时给界面算「已等几秒」「几秒后换线」 */
    enum class Phase { FIND, RESOLVE, BUFFER, PLAY }
    var phase by mutableStateOf(Phase.FIND)
        private set
    /** 这一步开始的时间 */
    private var phaseAt by mutableLongStateOf(System.currentTimeMillis())
    /** 每 0.5 秒走一下，让界面上的秒数、速度跟着刷新 */
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set
    /** 下载速度（字节 / 秒），平滑过的 */
    var rate by mutableLongStateOf(0L)
        private set
    private val bytes = java.util.concurrent.atomic.AtomicLong()
    /** 数播放器从网上拿了多少字节，算下载速度 */
    private val meter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, n: Int) { if (isNetwork) bytes.addAndGet(n.toLong()) }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }
    /** 这次是用户在线路面板里点的那一条：播不了就停，不自动换 */
    private var manual = false
    private fun enter(p: Phase) { phase = p; phaseAt = System.currentTimeMillis() }

    /** 播放器状态（Player.STATE_*） */
    var state by mutableIntStateOf(Player.STATE_IDLE)
        private set
    /** 连着自动放了好几集、很久没人按遥控器：停下来问一句「还在看吗」 */
    var askContinue by mutableStateOf(false)
        private set

    /** 真的在出画面（含缓冲）：保持亮屏只看这个。播完、出错、停下来问的时候让电视自己进屏保 */
    val awake get() = playing && (state == Player.STATE_READY || state == Player.STATE_BUFFERING) && fail == null && !askContinue

    private var cfg: TvConfig? = null
    private var job: Job? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val tried = HashSet<String>()
    private var bufferingSince = 0L
    /** 当前线路是用户手动选的 */
    private var pinned = false
    /** 暂停的时间（0 = 没暂停）；暂停太久再播，播放地址可能过期了，重新解析 */
    private var pausedAt = 0L
    /** 退到后台时停掉播放器（放掉解码器），记下位置，回来按 OK 从这里重新解析 */
    private var resumeMs = -1L
    /** 连着自动放了几集、最后一次按遥控器的时间 */
    private var autoPlays = 0
    private var lastTouch = System.currentTimeMillis()

    /** 预缓存进度（「本集 40%」「下一集 12%」「下一集已缓存好」），没在预缓存 = null */
    var prefetchText by mutableStateOf<String?>(null)
        private set
    private var prefetchJob: Job? = null
    /** 这一集已经顺利播了多久：播够 15 秒再开始预缓存，别跟起播抢网速 */
    private var playedMs = 0L
    /** 预缓存时解析好的下一集地址：真放下一集时直接用（同一个地址才能用上缓存） */
    private class Pre(val lineKey: String, val epUrl: String, val info: PlayInfo, val at: Long)
    private var pre: Pre? = null
    /** 当前在播的地址的请求头（预缓存要带上） */
    private var curHeaders: Map<String, String> = emptyMap()
    /** 本地下载好的集，做成一条「线路」；没有下载 = null */
    private var local: PlayLine? = null
    private val localDl = HashMap<String, Pair<Downloads.Meta, Download>>()

    /** 当前线路的集数（没线路时按豆瓣） */
    val episodeCount get() = line?.episodes?.size ?: maxOf(t.airedEps, 1)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                state = s
                when (s) {
                    Player.STATE_READY -> { ready?.complete(true); bufferingSince = 0; if (status == "缓冲中…") status = null }
                    Player.STATE_BUFFERING -> { if (bufferingSince == 0L && player.playWhenReady) bufferingSince = System.currentTimeMillis() }
                    Player.STATE_ENDED -> onEnded()
                }
            }
            override fun onPlayWhenReadyChanged(p: Boolean, reason: Int) {
                playing = p
                pausedAt = if (p) 0 else System.currentTimeMillis()
                // 暂停时不算卡住；接着播还在缓冲的，从现在重新计时
                bufferingSince = if (p && player.playbackState == Player.STATE_BUFFERING) System.currentTimeMillis() else 0
            }
            override fun onPlayerError(e: PlaybackException) {
                Log.w(TAG, "error ${line?.key}", e)
                val r = ready
                if (r != null && r.isActive) { r.complete(false); return }
                // 断网不是线路的错：别把好线路一条条记成失败，等网络好了按 OK 重试
                if (!Lines.online(ctx)) { player.stop(); status = null; fail = "网络断了。网络好了按 OK 重试"; return }
                failover("播放出错（${e.errorCodeName}）")
            }
        })
        val prev = Store.progress[t.id]
        val same = prev != null && if (startName != null && prev.epName != null) Eps.same(startName, prev.epName, t.name) else prev.ep == startEp
        if (same && startName == null) epName = prev!!.epName
        val startMs = if (same && !prev!!.finishedEp) prev.positionSec * 1000L else 0L
        pinned = prev?.pinned == true
        start(startEp, startMs, prev?.line)
        scope.launch {
            var tick = 0
            while (true) {
                delay(500)
                val b = bytes.getAndSet(0) * 2
                rate = if (rate == 0L) b else (rate + b) / 2
                now = System.currentTimeMillis()
                // 停掉（退到后台）以后时长读不到了，留着停之前的
                if (player.playbackState != Player.STATE_IDLE) {
                    posMs = player.currentPosition
                    durMs = player.duration.coerceAtLeast(0)
                }
                // 播放中途卡住太久也算这条线路不行
                if (status == null && !hidden && player.playWhenReady && bufferingSince > 0 && player.playbackState == Player.STATE_BUFFERING &&
                    System.currentTimeMillis() - bufferingSince > STALL_S * 1000L
                ) { bufferingSince = 0; failover("卡住超过 20 秒") }
                if (++tick % 20 == 0 && player.isPlaying) save()
                if (player.isPlaying) playedMs += 500
                // 播放器缺缓冲（在找线路、起播、缓冲不到 15 秒）时，预缓存和下载先停，网速全给播放
                val ahead = player.bufferedPosition - player.currentPosition
                val starving = fail == null && !hidden && when (phase) {
                    Phase.FIND -> false
                    Phase.RESOLVE, Phase.BUFFER -> true
                    Phase.PLAY -> player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
                        ahead < (if (hungry) 25_000 else 15_000)
                }
                if (starving != hungry) { hungry = starving; MediaCache.playerHungry(this@PlaySession, starving) }
                if (prefetchJob == null && phase == Phase.PLAY && playedMs >= 15_000 && Prefs.precache && !hidden &&
                    line?.siteKey != LOCAL && !MediaCache.metered()
                ) prefetchJob = scope.launch { prefetch() }
            }
        }
    }

    /** 从头开始（换集、手动换线、自动换线都走这里）。[first]：先试这一条，不行再按平常的顺序换 */
    private fun start(ep: Int, startMs: Long, prefer: String?, only: PlayLine? = null, first: PlayLine? = null) {
        job?.cancel()
        stopPrefetch()
        this.ep = ep
        enter(Phase.FIND)
        manual = only != null
        fail = null
        askContinue = false
        resumeMs = -1
        job = scope.launch { run(startMs, prefer, only, first) }
    }

    /**
     * 换集。[name] 不为 null：当前线路里的第 [n] 个、名字是 [name]（按名字选的）；
     * 为 null：第 [n] 集（按豆瓣集数选的，到线路里按名字里的数字找）。
     */
    fun playEpisode(n: Int, name: String?) {
        if (fail == null && (if (name != null) name == epName && n == ep else n == epNumber)) return
        save()
        tried.clear()
        epName = name
        start(n, 0, line?.key)
    }

    fun playNext() {
        val n = nextName ?: return
        val i = line?.episodes?.indexOfFirst { it.name == n } ?: -1
        playEpisode(if (i >= 0) i + 1 else ep + 1, n)
    }

    /** 当前线路的分集按序号显示（电视剧）；false = 按名字（综艺）；还没线路 = null */
    val numericLine: Boolean? get() = line?.let { Eps.numeric(it.episodes, t.name, t.isVariety) }

    /** 名字里的集数（按序号的线路）；按名字的线路没有 */
    private val nameNumber: Int? get() = epName?.takeIf { numericLine != false }?.let { Eps.number(it, t.name) }

    /** 这一集是第几集（名字里的数字）；名字不是序号的就是线路里的位置 */
    val epNumber: Int get() = nameNumber ?: ep

    /** 「第 3 集」或者「11.01 上」 */
    fun epText(name: String? = epName, idx: Int = epNumber) = Eps.text(name, idx, t.name, numericLine)

    /**
     * 下一集的名字，没有就是 null。按序号的线路找「第 n+1 集」，按名字的是后面第一个正片（都跳过花絮、预告）。
     * 当前线路没有（这个站缺这一集、还没更新）就到别的线路里找，播的时候自动换过去。
     */
    val nextName: String? get() {
        val n = epName ?: return null
        val l = line ?: return null
        Eps.nextIndex(l.episodes, n, t.name, numericLine ?: true).takeIf { it >= 0 }?.let { return l.episodes[it].name }
        for (o in Lines.ordered(set, l.key, emptyList())) {
            if (o.key == l.key) continue
            val i = Eps.nextIndex(o.episodes, n, t.name, Eps.numeric(o.episodes, t.name, t.isVariety))
            if (i >= 0) return o.episodes[i].name
        }
        return null
    }

    /** 「下一集 · 第 4 集」「下一集 · 03.17 上」 */
    fun nextText(n: String) = Eps.text(n, Eps.number(n, t.name) ?: (epNumber + 1), t.name, numericLine)

    /**
     * 这一集在某条线路里是第几个（1 开始），找不到 = null。
     * 有名字先按名字找；名字是序号的按数字找（线路里夹着「陪看」「彩蛋」时位置会错开）；
     * 按日期 / 期数分的线路找不到同名的就是没有，不按位置凑。
     */
    private fun indexIn(l: PlayLine): Int? {
        val eps = l.episodes
        val n = epName ?: return Eps.indexOfNumber(eps, ep, t.name).takeIf { it >= 0 }?.plus(1)
        // 选集行按名字点的、这个位置上正好是这个名字：直接用（同一条线路里有好几个「加更版」时，不能都放第一个）
        if (eps.getOrNull(ep - 1)?.name == n) return ep
        val i = Eps.indexOf(eps, n, t.name)
        if (i >= 0) return i + 1
        val num = Eps.number(n, t.name) ?: return null
        if (Eps.dated(eps, t.name)) return null
        return Eps.indexOfNumber(eps, num, t.name).takeIf { it >= 0 }?.plus(1)
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
    /** 播放器缺缓冲，后台下载在让路 */
    private var hungry = false

    /**
     * 退到后台：已经在播的停掉播放器（不在后台出声，也把硬件解码器让给别的 App），记下位置；
     * 还在找线路、解析的，加载好了也先别播。
     */
    fun onHidden() {
        hidden = true
        save()
        if (status == null && fail == null && line != null && ready == null && durMs > 0) {
            resumeMs = player.currentPosition
            player.pause()
            player.stop()
        } else player.pause()
    }
    /** 回来了：停在暂停，按 OK 接着播 */
    fun onShown() { hidden = false }

    /** 按了遥控器：连播计数清零 */
    fun touch() { autoPlays = 0; lastTouch = System.currentTimeMillis() }

    /** 「还在看吗」按了 OK */
    fun keepWatching() { askContinue = false; touch(); playNext() }

    fun togglePlay() {
        if (player.playWhenReady) { player.pause(); return }
        val l = line
        // 退到后台停掉了、或者暂停超过 15 分钟（地址可能过期）：在这条线路、这个位置重新解析，不行再换线
        if (l != null && fail == null && status == null &&
            (player.playbackState == Player.STATE_IDLE || pausedAt > 0 && System.currentTimeMillis() - pausedAt > 15 * 60_000)
        ) {
            val pos = if (resumeMs >= 0) resumeMs else player.currentPosition.coerceAtLeast(0)
            tried.clear()
            start(ep, pos, l.key, first = l)
            return
        }
        player.play()
    }

    /** 播放不了的时候按 OK：从头再找一遍（断网恢复后、或者线路刚好在抽风） */
    fun retry() {
        if (fail == null) return
        if (set.done && set.lines.isEmpty()) set = Lines.find(ctx, t, force = true)
        tried.clear()
        start(ep, posMs.coerceAtLeast(0), line?.key)
    }
    fun seekBy(sec: Int) { player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0, durMs.takeIf { it > 0 } ?: Long.MAX_VALUE)); posMs = player.currentPosition }
    fun setRate(r: Float) { speed = r; player.setPlaybackSpeed(r) }

    fun save() {
        if (line == null || durMs <= 0) return
        val num = nameNumber
        Store.progress[t.id] = Progress(
            t.id, num ?: ep, (posMs / 1000).toInt(), (durMs / 1000).toInt(), System.currentTimeMillis(), line?.key, pinned, epName,
            named = num == null && epName != null, nextName = nextName,
        )
    }

    fun release() {
        save()
        hungry = false
        MediaCache.playerHungry(this, false)
        job?.cancel()
        stopPrefetch()
        player.release()
    }

    private fun onEnded() {
        save()
        val next = nextName
        if (!t.isEpisodic || next == null) return
        // 连着自动放了 3 集、或者 2 个半小时没按过遥控器（人睡着了、电视关了盒子没关）：停下来问一句
        if (autoPlays >= 3 || System.currentTimeMillis() - lastTouch > 150 * 60_000) { askContinue = true; return }
        autoPlays++
        notice = "自动播放" + nextText(next).let { if (it.startsWith("第")) it else " $it" }
        playNext()
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

    private suspend fun run(startMs: Long, prefer: String?, only: PlayLine?, first: PlayLine?) {
        // 下载好的集先放本地的：不用联网、不用找线路（飞机上）
        loadLocal()
        val lc = local?.takeIf { only == null || only.key == it.key }?.takeIf { it.key !in tried && indexIn(it) != null }
        if (lc != null) {
            tried += lc.key
            val i = indexIn(lc)!!
            ep = i
            epName = lc.episodes[i - 1].name
            if (tryLine(null, lc, lc.episodes[i - 1], startMs)) return
            set.failed.remove(lc.key)
        }
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
            if (only == null && first == null && !set.done) {
                val age = System.currentTimeMillis() - t0
                // 上次那个站已经搜完了（这次没搜到）、换了片源没有这个站、被屏蔽了：不用等
                val prevSite = prefer?.substringBefore('|')
                val waitPrev = prefer != null && set.lines.none { it.key == prefer } && age < 12_000 &&
                    prevSite !in set.doneSites && prevSite !in Prefs.blocked && c.sites.any { it.key == prevSite }
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
                else first?.takeIf { it.key !in tried && indexIn(it) != null }
                    ?: Lines.ordered(set, prefer, c.flags, pinned).firstOrNull { it.key !in tried && indexIn(it) != null }
            if (cand == null) {
                if (set.done || only != null) {
                    player.stop()
                    status = null
                    fail = when {
                        set.lines.isEmpty() && !Lines.online(ctx) -> "网络断了。网络好了按 OK 重试"
                        set.lines.isEmpty() -> set.error ?: "「${Sources.name}」的 ${set.total} 个站点里都没找到《${t.name}》"
                        set.lines.none { indexIn(it) != null } -> "所有线路都还没有「${epText()}」"
                        set.failed.values.any { dev.personal.tv.source.PluginPortal.wantsLogin(it) } ->
                            "${tried.size} 条线路都播不了；网盘线路要先在「设置 → 网盘登录」扫码登录"
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
            // 按名字 / 集数认出来的才改成这条线路里的名字；按位置凑的（线路里的名字认不出数字）只拿来播，不改这一集是谁
            val found = cand.episodes[i - 1].name
            if (epName.let { it == null || Eps.same(found, it, t.name) || Eps.number(found, t.name)?.let { n -> n == Eps.number(it, t.name) } == true }) epName = found
            if (tryLine(c, cand, cand.episodes[i - 1], startMs)) return
            // 断网了不是线路的错：不记失败，也别一条条换下去
            if (!Lines.online(ctx)) {
                set.failed.remove(cand.key)
                player.stop()
                status = null
                fail = "网络断了。网络好了按 OK 重试"
                return
            }
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

    private suspend fun tryLine(c: TvConfig?, l: PlayLine, e: Episode, startMs: Long): Boolean {
        line = l
        if (l.siteKey == LOCAL) return tryLocal(l, e, startMs)
        c ?: return false
        status = "正在解析 · ${l.siteName} · ${l.flag}"
        enter(Phase.RESOLVE)
        player.stop()
        val t0 = System.currentTimeMillis()
        var why = "解析不出视频地址"
        // 预缓存时已经解析好、下了一部分的：直接用同一个地址
        val reuse = pre?.takeIf { it.lineKey == l.key && it.epUrl == e.url && System.currentTimeMillis() - it.at < 3 * 3600_000L }
        pre = null
        val info = if (reuse != null) reuse.info else try {
            withTimeoutOrNull(RESOLVE_S * 1000L) { Lines.yieldTo { resolve(c, l, e) } }
        } catch (x: Throwable) {
            Log.w(TAG, "resolve ${l.key}", x)
            val root = generateSequence(x) { it.cause }.last()
            // 插件进程里抛的 PluginError 过 binder 后只剩文字
            (x as? dev.personal.tv.source.PluginError ?: root as? dev.personal.tv.source.PluginError)?.message?.let {
                why = it.take(48) + if (dev.personal.tv.source.PluginPortal.wantsLogin(it)) "（在「设置 → 网盘登录」登录）" else ""
            }
            null
        }
        if (info == null) {
            set.failed[l.key] = why
            Log.i(TAG, "✗ ${l.key} 解析失败 ${System.currentTimeMillis() - t0}ms")
            return false
        }
        status = "缓冲中…"
        enter(Phase.BUFFER)
        val fmt = info.format ?: withContext(Dispatchers.IO) { formatOf(info) }
        info.format = fmt
        curHeaders = info.headers
        // 边放边存进磁盘缓存：预缓存下好的部分直接从缓存读
        val ds = MediaCache.factory(info.headers, MediaCache.play, meter)
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
        val ok = withTimeoutOrNull(READY_S * 1000L) { r.await() } ?: false
        ready = null
        if (!ok && reuse != null) {
            // 预缓存时拿到的地址过期了：重新解析一次
            player.stop()
            return tryLine(c, l, e, startMs)
        }
        if (!ok) {
            set.failed.putIfAbsent(l.key, "播放失败")
            Log.i(TAG, "✗ ${l.key} 播放失败 ${info.url.take(80)}")
            player.stop()
            return false
        }
        status = null
        enter(Phase.PLAY)
        set.failed.remove(l.key)
        Log.i(TAG, "✓ ${l.key} 第 $ep 集「$epName」 ${System.currentTimeMillis() - t0}ms ${info.url.take(80)}")
        save()
        return true
    }

    /** 查这部片下载好的集，做成一条本地线路 */
    private suspend fun loadLocal() {
        val list = runCatching { Downloads.completed(t.id) }.getOrDefault(emptyList())
        localDl.clear()
        if (list.isEmpty()) { local = null; return }
        // 不同线路下的同一集只留一个
        val eps = ArrayList<Episode>()
        for ((m, d) in list.sortedBy { Eps.number(it.first.epName, t.name) ?: it.first.epIdx }) {
            if (eps.any { Eps.same(it.name, m.epName, t.name) }) continue
            eps += Episode(m.epName, d.request.id)
            localDl[d.request.id] = m to d
        }
        local = PlayLine(LOCAL, "已下载", "本地", t.name, "", eps)
    }

    private suspend fun tryLocal(l: PlayLine, e: Episode, startMs: Long): Boolean {
        val (m, d) = localDl[e.url] ?: return false
        status = "缓冲中…"
        enter(Phase.BUFFER)
        player.stop()
        curHeaders = m.headers
        val ds = MediaCache.factory(m.headers, MediaCache.downloads, meter)
        val r = CompletableDeferred<Boolean>()
        ready = r
        bufferingSince = 0
        player.setMediaSource(DefaultMediaSourceFactory(ds).createMediaSource(d.request.toMediaItem()), startMs)
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = !hidden
        val ok = withTimeoutOrNull(READY_S * 1000L) { r.await() } ?: false
        ready = null
        if (!ok) {
            Log.i(TAG, "✗ 本地 ${m.epName}")
            set.failed[l.key] = "本地文件放不了"
            player.stop()
            return false
        }
        status = null
        enter(Phase.PLAY)
        Log.i(TAG, "✓ 本地 ${m.epName}")
        save()
        return true
    }

    private fun stopPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        playedMs = 0
        prefetchText = null
    }

    /**
     * 预缓存：先把这一集当前位置往后的分片（HLS）存进缓存，再解析下一集、整集下到缓存里。
     * 都走后台优先级：播放器在加载时停下来等。换集、换线、退出时取消；已经存下的留在缓存里。
     */
    private suspend fun prefetch() {
        // PriorityTaskManager 只放行「登记过的最高优先级」：自己不登记的话一直等下去
        MediaCache.priority.add(C.PRIORITY_DOWNLOAD)
        try { prefetchBody() } finally { MediaCache.priority.remove(C.PRIORITY_DOWNLOAD) }
    }

    private suspend fun prefetchBody() {
        val l = line ?: return
        val c = cfg ?: return
        val f = MediaCache.factory(curHeaders, MediaCache.play, background = true)
        // 1. 这一集后面的部分
        val m = player.currentManifest as? HlsManifest
        val pl = m?.mediaPlaylist
        if (pl != null && pl.hasEndTag) {
            val posUs = player.currentPosition * 1000
            val segs = pl.segments.filter { it.relativeStartTimeUs + it.durationUs > posUs }
            Log.i(TAG, "prefetch: 本集还剩 ${segs.size} 个分片")
            var bad = 0
            for ((k, sg) in segs.withIndex()) {
                prefetchText = "本集 ${k * 100 / segs.size.coerceAtLeast(1)}%"
                val spec = DataSpec.Builder().setUri(UriUtil.resolveToUri(pl.baseUri, sg.url))
                    .setPosition(sg.byteRangeOffset).setLength(sg.byteRangeLength).build()
                val t0 = System.currentTimeMillis()
                val okSeg = cacheOne(f, spec)
                if (k % 50 == 0 || !okSeg) Log.d(TAG, "prefetch: 分片 $k/${segs.size} ${if (okSeg) "ok" else "失败"} ${System.currentTimeMillis() - t0}ms")
                bad = if (okSeg) 0 else bad + 1
                if (bad >= 3) { Log.i(TAG, "prefetch: 本集分片连着失败，停"); break }
            }
        }
        // 2. 下一集（同一条线路里有的才下）
        val n = nextName ?: run { prefetchText = null; return }
        val i = Eps.indexOf(l.episodes, n, t.name).takeIf { it >= 0 } ?: run { prefetchText = null; return }
        val e = l.episodes[i]
        prefetchText = "正在解析下一集"
        val info = runCatching { withTimeoutOrNull(RESOLVE_S * 1000L) { resolveInfo(ctx, c, l.siteKey, l.flag, e.url) } }.getOrNull()
        if (info == null) { prefetchText = null; return }
        val fmt = info.format ?: withContext(Dispatchers.IO) { formatOf(info) }
        info.format = fmt
        pre = Pre(l.key, e.url, info, System.currentTimeMillis())
        val nf = MediaCache.factory(info.headers, MediaCache.play, background = true)
        val item = MediaItem.Builder().setUri(info.url).apply {
            when (fmt) {
                "hls" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                "dash" -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        val d: Downloader = when (fmt) {
            "hls" -> HlsDownloader(item, nf)
            "dash" -> DashDownloader(item, nf)
            else -> ProgressiveDownloader(item, nf)
        }
        prefetchText = "下一集 0%"
        val job = kotlin.coroutines.coroutineContext[Job]
        val h = job?.invokeOnCompletion { d.cancel() }
        try {
            runInterruptible(Dispatchers.IO) {
                d.download { _, _, pct -> if (pct >= 0) prefetchText = "下一集 ${pct.toInt()}%" }
            }
            prefetchText = "下一集已缓存好"
            Log.i(TAG, "prefetch: 下一集「${e.name}」缓存好了")
        } catch (x: kotlinx.coroutines.CancellationException) {
            throw x
        } catch (x: Throwable) {
            Log.w(TAG, "prefetch next", x)
            prefetchText = null
        } finally {
            h?.dispose()
        }
    }

    /**
     * 存一个分片；播放器缺缓冲时先等它。30 秒一个字节都没进来就放弃这一片（别让预缓存卡死在一个分片上）。
     */
    private suspend fun cacheOne(f: androidx.media3.datasource.cache.CacheDataSource.Factory, spec: DataSpec): Boolean = coroutineScope {
        val last = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val got = java.util.concurrent.atomic.AtomicLong(0)
        val yielding = java.util.concurrent.atomic.AtomicBoolean(false)
        val work = async(Dispatchers.IO) {
            runInterruptible {
                val ds = f.createDataSourceForDownloading()
                while (true) {
                    val w = CacheWriter(ds, spec, null) { _, done, _ ->
                        if (done != got.get()) { got.set(done); last.set(System.currentTimeMillis()) }
                    }
                    try { w.cache(); break }
                    catch (_: PriorityTaskManager.PriorityTooLowException) {
                        yielding.set(true)
                        Log.d(TAG, "prefetch: 播放器缺缓冲，先等")
                        try { MediaCache.priority.proceed(C.PRIORITY_DOWNLOAD) } finally { yielding.set(false) }
                        last.set(System.currentTimeMillis())
                    }
                }
            }
        }
        while (work.isActive) {
            if (withTimeoutOrNull(1000) { work.join() } != null) break
            if (yielding.get()) last.set(System.currentTimeMillis())
            else if (System.currentTimeMillis() - last.get() > 30_000) {
                Log.d(TAG, "prefetch: 分片 30 秒没进展（已存 ${got.get() / 1024} KB），跳过")
                work.cancel()
            }
        }
        try { work.await(); true }
        catch (x: Throwable) {
            if (x is kotlinx.coroutines.CancellationException && !isActive) throw x
            Log.d(TAG, "prefetch: 分片失败 ${x.javaClass.simpleName} ${x.message?.take(80)}")
            false
        }
    }

    private suspend fun resolve(c: TvConfig, l: PlayLine, e: Episode): PlayInfo? =
        resolveInfo(ctx, c, l.siteKey, l.flag, e.url) { status = "网页解析 · ${l.siteName} · ${l.flag}" }

    /**
     * 加载时状态下面那行小字：第几条线路、等了几秒、下载速度、已缓冲多少、几秒后自动换线。
     * 卡了半天不出画面时，看这行能知道是在等插件解析、还是网速太慢。
     */
    fun detail(): String? {
        if (fail != null || status == null) return null
        val waited = ((now - phaseAt) / 1000).toInt()
        val parts = mutableListOf<String>()
        val n = set.lines.size
        when (phase) {
            Phase.FIND -> {
                if (n > 0) parts += "已找到 $n 条线路"
                if (waited >= 3) parts += "已等 $waited 秒"
            }
            Phase.RESOLVE, Phase.BUFFER -> {
                if (n > 1) parts += "第 ${tried.size.coerceIn(1, n)}/$n 条线路"
                if (phase == Phase.BUFFER) {
                    parts += speedText(rate)
                    val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000
                    if (ahead > 0) parts += "已缓冲 $ahead 秒"
                }
                if (waited >= 3) parts += "已等 $waited 秒"
                val limit = if (phase == Phase.RESOLVE) RESOLVE_S else READY_S
                if (waited >= 5 && waited < limit) parts += if (Prefs.autoLine && !manual) "${limit - waited} 秒后换下一条" else "最多再等 ${limit - waited} 秒"
            }
            Phase.PLAY -> return null
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /**
     * 画面出来以后右上角的小字：
     * 暂停时 = 往后缓冲了多少 · 下载速度（还在下的话）· 现在几点；
     * 播放中卡住 = 速度 + 卡了几秒。
     */
    fun cornerText(): String? {
        if (phase != Phase.PLAY || status != null || fail != null) return null
        if (!playing) {
            val ahead = ((player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000).toInt()
            val parts = mutableListOf<String>()
            parts += if (ahead > 0) "已缓冲 ${aheadText(ahead)}" else "还没缓冲"
            if (player.isLoading) parts += speedText(rate)
            prefetchText?.let { parts += "预缓存 $it" }
            parts += clock.format(java.util.Date(now))
            return parts.joinToString(" · ")
        }
        if (state != Player.STATE_BUFFERING) return null
        val since = bufferingSince.takeIf { it > 0 } ?: return "缓冲中 · ${speedText(rate)}"
        val s = ((now - since) / 1000).toInt()
        if (s < 1) return null
        return "缓冲中 · ${speedText(rate)} · 已卡 $s 秒" + if (Prefs.autoLine && s >= 5 && s < STALL_S) "，${STALL_S - s} 秒后换线" else ""
    }

    companion object {
        /** 解析最多等几秒、换好地址后等出画面几秒、播放中卡住几秒算这条线路不行 */
        const val RESOLVE_S = 45
        const val READY_S = 25
        const val STALL_S = 20

        fun speedText(bps: Long) = when {
            bps <= 0 -> "没收到数据"
            bps < 1024 * 1024 -> "${bps / 1024} KB/s"
            else -> "%.1f MB/s".format(bps / 1048576.0)
        }

        fun aheadText(s: Int) = if (s < 60) "$s 秒" else "${s / 60} 分 %02d 秒".format(s % 60)

        private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)

        /** 本地下载的「线路」 */
        const val LOCAL = "local"

        /** 站点 / 插件给出这一集的播放地址；要网页解析的走解析（[onParse] 给界面换提示） */
        suspend fun resolveInfo(ctx: Context, c: TvConfig, siteKey: String, flag: String, url: String, onParse: () -> Unit = {}): PlayInfo? {
            val s = c.sites.firstOrNull { it.key == siteKey } ?: return null
            val api = withContext(Dispatchers.IO) { Sites.get(ctx, c, s) }
            val p = withContext(Dispatchers.IO) { api.play(flag, url) }
            if (!p.parse && !p.jx) return p
            onParse()
            return Parser.resolve(ctx, c, flag, p, withContext(Dispatchers.IO) { api.videoCheck() })?.info
        }

        /** 地址没扩展名时探一下开头几个字节，HLS/DASH 要告诉播放器 */
        fun formatOf(p: PlayInfo): String? {
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
}
