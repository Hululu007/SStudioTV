package dev.personal.tv.link

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Store
import dev.personal.tv.source.Remote
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.Navigator
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.screens.PlaySession
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom

/**
 * 电视端常驻局域网联动服务：
 * - NanoHTTPD 监听 9981..9991，对外暴露 /api/v1 JSON 接口；
 * - Android NsdManager 广播 _sstudiotv._tcp.，供 Android 手机和未来的 iPhone Bonjour 自动发现；
 * - 所有敏感接口均需配对后获得的 Bearer Token，支持在电视设置里随时换配对码或重置授权。
 */
object TvLinkServer {
    private const val TAG = "TvLinkServer"
    private val gson = Gson()
    private val rng = SecureRandom()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "uncaught", e) },
    )

    private data class AuthConfig(
        val deviceId: String,
        val token: String,
        val pairCode: String,
        val deviceName: String,
    )

    private var appCtx: Context? = null
    private var auth: AuthConfig? = null
    private var server: HttpServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var router: LinkRouter? = null

    var running by mutableStateOf(false)
        private set
    var host by mutableStateOf<String?>(null)
        private set
    var port by mutableIntStateOf(0)
        private set
    var deviceName by mutableStateOf("SStudioTV")
        private set
    var pairCode by mutableStateOf("")
        private set
    var qrUri by mutableStateOf<String?>(null)
        private set
    var lastEvent by mutableStateOf<String?>(null)
        private set

    val address: String? get() = host?.let { h -> if (port > 0) "$h:$port" else null }

    fun formattedPairCode(): String =
        if (pairCode.length == 6) "${pairCode.substring(0, 3)} ${pairCode.substring(3)}" else pairCode

    @Synchronized
    fun start(ctx: Context): Boolean {
        val c = ctx.applicationContext
        appCtx = c
        val cfg = loadOrCreateAuth(c)
        val ip = Remote.lanIp()
        if (server != null) {
            if (ip != null && ip != host) updateUiState(true, ip, port, cfg)
            return true
        }
        val r = LinkRouter(AndroidLinkHost(c), gson)
        router = r
        for (p in LinkProtocol.PORT_RANGE) {
            val s = HttpServer(p, r)
            if (runCatching { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }.isSuccess) {
                server = s
                updateUiState(true, ip ?: "0.0.0.0", p, cfg)
                registerMdns(c, cfg, p)
                Log.i(TAG, "listening ${ip ?: "0.0.0.0"}:$p id=${cfg.deviceId}")
                return true
            }
        }
        updateUiState(false, null, 0, cfg)
        Log.w(TAG, "failed to bind any port in ${LinkProtocol.PORT_RANGE}")
        return false
    }

    @Synchronized
    fun stop() {
        unregisterMdns()
        runCatching { server?.stop() }
        server = null
        router = null
        val cfg = auth
        scope.launch {
            running = false
            host = null
            port = 0
            qrUri = null
            if (cfg != null) {
                deviceName = cfg.deviceName
                pairCode = cfg.pairCode
            }
        }
    }

    /** 刷新 IP（比如刚连上 Wi-Fi 时） */
    @Synchronized
    fun refreshAddress() {
        val cfg = auth ?: return
        val ip = Remote.lanIp() ?: return
        if (running && port > 0 && ip != host) {
            updateUiState(true, ip, port, cfg)
        }
    }

    /** 更换 6 位配对码（不影响已配对设备） */
    @Synchronized
    fun rotatePairCode() {
        val c = appCtx ?: return
        val cur = loadOrCreateAuth(c)
        val next = cur.copy(pairCode = randomDigits(6))
        saveAuth(c, next)
        auth = next
        updateUiState(running, host, port, next)
    }

    /** 重置授权：已配对的所有手机全部失效，并生成新的配对码 */
    @Synchronized
    fun resetPairing() {
        val c = appCtx ?: return
        val cur = loadOrCreateAuth(c)
        val next = cur.copy(token = randomToken(32), pairCode = randomDigits(6))
        saveAuth(c, next)
        auth = next
        updateUiState(running, host, port, next)
    }

    private fun updateUiState(isRunning: Boolean, ip: String?, p: Int, cfg: AuthConfig) {
        val qr = if (isRunning && !ip.isNullOrBlank() && ip != "0.0.0.0" && p > 0) {
            LinkQr.buildUri(ip, p, cfg.pairCode, cfg.deviceName, cfg.deviceId)
        } else null
        scope.launch {
            running = isRunning
            host = ip?.takeIf { it != "0.0.0.0" }
            port = p
            deviceName = cfg.deviceName
            pairCode = cfg.pairCode
            qrUri = qr
        }
    }

    private fun loadOrCreateAuth(ctx: Context): AuthConfig {
        auth?.let { return it }
        val f = File(ctx.filesDir, "link-auth.json")
        val loaded = runCatching {
            if (f.exists()) gson.fromJson(f.readText(), AuthConfig::class.java) else null
        }.getOrNull()
        @Suppress("SENSELESS_COMPARISON")
        if (loaded != null && loaded.deviceId != null && loaded.token != null && loaded.pairCode != null &&
            loaded.deviceId.length >= 6 && loaded.token.length >= 16 && loaded.pairCode.length == 6
        ) {
            val fixed = if (loaded.deviceName.isNullOrBlank()) loaded.copy(deviceName = defaultDeviceName()) else loaded
            auth = fixed
            return fixed
        }
        val fresh = AuthConfig(
            deviceId = randomToken(8),
            token = randomToken(32),
            pairCode = randomDigits(6),
            deviceName = defaultDeviceName(),
        )
        saveAuth(ctx, fresh)
        auth = fresh
        return fresh
    }

    private fun saveAuth(ctx: Context, cfg: AuthConfig) {
        runCatching {
            val f = File(ctx.filesDir, "link-auth.json")
            val tmp = File(ctx.filesDir, "link-auth.json.tmp")
            tmp.writeText(gson.toJson(cfg))
            tmp.renameTo(f)
        }.onFailure { Log.w(TAG, "saveAuth", it) }
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isNotEmpty()) "SStudioTV ($model)" else "SStudioTV"
    }

    private fun randomDigits(n: Int): String = buildString(n) {
        repeat(n) { append(('0'.code + rng.nextInt(10)).toChar()) }
    }

    private fun randomToken(n: Int): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return buildString(n) { repeat(n) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    private fun registerMdns(ctx: Context, cfg: AuthConfig, listenPort: Int) {
        unregisterMdns()
        runCatching {
            val mgr = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "SStudioTV-${cfg.deviceId.take(4)}"
                serviceType = LinkProtocol.MDNS_SERVICE_TYPE
                port = listenPort
                setAttribute("id", cfg.deviceId)
                setAttribute("name", cfg.deviceName.take(48))
                setAttribute("ver", appVersion(ctx))
                setAttribute("proto", LinkProtocol.PROTOCOL_VERSION.toString())
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered: ${serviceInfo.serviceName} :$listenPort")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS unregistration failed: $errorCode")
                }
            }
            nsdManager = mgr
            nsdListener = listener
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(TAG, "registerMdns", it) }
    }

    private fun unregisterMdns() {
        val mgr = nsdManager
        val listener = nsdListener
        nsdManager = null
        nsdListener = null
        if (mgr != null && listener != null) {
            runCatching { mgr.unregisterService(listener) }
        }
    }

    private fun appVersion(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun <T> onMain(timeoutMs: Long = 10_000L, block: suspend () -> T): T = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main.immediate) { block() }
        } ?: error("电视界面响应超时")
    }

    private class AndroidLinkHost(private val ctx: Context) : LinkHost {
        override fun deviceInfo(): DeviceInfo {
            val cfg = loadOrCreateAuth(ctx)
            return DeviceInfo(
                protocolVersion = LinkProtocol.PROTOCOL_VERSION,
                deviceId = cfg.deviceId,
                name = cfg.deviceName,
                version = appVersion(ctx),
                mode = "tv",
            )
        }

        override fun currentPairCode(): String = loadOrCreateAuth(ctx).pairCode
        override fun currentAuthToken(): String = loadOrCreateAuth(ctx).token

        override fun onPaired(clientName: String) {
            val who = clientName.ifBlank { "手机" }
            scope.launch {
                lastEvent = "已与 $who 配对"
                Navigator.current?.toast("已与 $who 配对成功")
            }
        }

        override fun status(): TvStatus = onMain(5_000L) {
            val nav = Navigator.current
            val route = when (nav?.stack?.lastOrNull()) {
                is Route.Player -> "player"
                is Route.Detail -> "detail"
                is Route.Search -> "search"
                is Route.LivePlay -> "live"
                Route.Main, null -> "main"
                else -> "other"
            }
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val vol = audio?.let { am ->
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val muted = am.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0
                VolumeState(cur, max, muted)
            }
            val ps = PlaySession.current?.get()
            val pb = ps?.let { s ->
                val curLine = s.line
                val lines = s.set.lines.take(40).map { l ->
                    LineSummary(
                        key = l.key,
                        siteName = l.siteName,
                        flag = l.flag,
                        direct = l.direct,
                        epCount = l.episodes.size,
                        failed = l.key in s.set.failed,
                    )
                }
                val eps = curLine?.episodes?.take(200)?.mapIndexed { i, e -> EpSummary(i + 1, e.name) }
                    ?: (1..s.episodeCount.coerceAtMost(200)).map { EpSummary(it, "第 $it 集") }
                PlaybackState(
                    titleId = s.t.id,
                    titleName = s.t.name,
                    poster = s.t.poster,
                    ep = s.epNumber,
                    epName = s.epName,
                    epText = s.epText(),
                    episodeCount = s.episodeCount,
                    posMs = s.here(),
                    durMs = s.durMs,
                    playing = s.playing,
                    speed = s.speed,
                    phase = s.phase.name,
                    status = s.status,
                    fail = s.fail,
                    lineKey = curLine?.key,
                    lineName = curLine?.let { "${it.siteName} · ${it.flag}" },
                    nextName = s.nextName,
                    lines = lines,
                    episodes = eps,
                )
            }
            TvStatus(route = route, volume = vol, playback = pb)
        }

        override fun cast(req: CastRequest): ApiResult = onMain(10_000L) {
            val nav = Navigator.current ?: return@onMain ApiResult(false, error = "电视界面还没就绪")
            val incoming = req.title
            val id = req.titleId.ifBlank { incoming?.id.orEmpty() }
            if (id.isBlank()) return@onMain ApiResult(false, error = "缺少片目 id")
            if (incoming != null) Catalog.put(listOf(incoming))
            val title = Catalog.get(id) ?: incoming
                ?: return@onMain ApiResult(false, error = "电视片库里找不到这部片，请随请求带上片目信息")
            if (req.action == "detail") {
                nav.openDetail(title.id)
                nav.toast("手机打开《${title.name}》")
                lastEvent = "打开《${title.name}》"
            } else {
                val ep = req.ep.coerceAtLeast(1)
                nav.castPlay(title.id, ep, req.epName, req.posMs)
                val posHint = if (req.posMs > 5_000L) " · 从 ${formatTime(req.posMs)}" else ""
                val epHint = if (title.isEpisodic) " 第 $ep 集" else ""
                nav.toast("手机投屏：《${title.name}》$epHint$posHint")
                lastEvent = "投屏播放《${title.name}》"
            }
            ApiResult(true, message = "已发送到电视")
        }

        override fun control(req: ControlRequest): ApiResult = onMain(15_000L) {
            val nav = Navigator.current
            val ps = PlaySession.current?.get()
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            when (req.cmd) {
                "toggle_play" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    ps.touch(); ps.togglePlay()
                    ApiResult(true, if (ps.playing) "已继续播放" else "已暂停")
                }
                "play" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    ps.touch(); ps.setPlayWhenReady(true)
                    ApiResult(true, "已播放")
                }
                "pause" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    ps.touch(); ps.setPlayWhenReady(false)
                    ApiResult(true, "已暂停")
                }
                "seek_to" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    val ms = req.posMs ?: return@onMain ApiResult(false, error = "missing_posMs")
                    ps.touch(); ps.seekTo(ms)
                    ApiResult(true, "已跳转到 ${formatTime(ms)}")
                }
                "seek_by" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    val sec = req.deltaSec ?: 15
                    ps.touch(); ps.seekBy(sec)
                    ApiResult(true, if (sec >= 0) "快进 ${sec}s" else "快退 ${-sec}s")
                }
                "next_ep" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    if (ps.nextName == null) return@onMain ApiResult(false, error = "没有下一集了")
                    ps.touch(); ps.playNext()
                    ApiResult(true, "已切到下一集")
                }
                "prev_ep" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    ps.touch(); ps.playPrev()
                    ApiResult(true, "已切到上一集")
                }
                "select_ep" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    val ep = req.ep ?: return@onMain ApiResult(false, error = "missing_ep")
                    ps.touch(); ps.playEpisode(ep.coerceAtLeast(1), req.epName)
                    ApiResult(true, "已切换分集")
                }
                "discover_lines" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    ps.discover()
                    ApiResult(true, "正在搜索更多线路")
                }
                "switch_line" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    val key = req.lineKey ?: return@onMain ApiResult(false, error = "missing_lineKey")
                    ps.discover()
                    val target = ps.set.lines.firstOrNull { it.key == key }
                        ?: return@onMain ApiResult(false, error = "找不到该线路")
                    ps.touch(); ps.switchLine(target)
                    ApiResult(true, "已切换到 ${target.siteName} · ${target.flag}")
                }
                "retry" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    ps.touch(); ps.retry()
                    ApiResult(true, "已重试播放")
                }
                "set_speed" -> {
                    if (ps == null) return@onMain ApiResult(false, error = "电视当前没有在播放")
                    val sp = (req.speed ?: 1f).coerceIn(0.5f, 3f)
                    ps.touch(); ps.setRate(sp)
                    ApiResult(true, "倍速 ${sp}x")
                }
                "vol_up" -> {
                    audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ApiResult(true, "音量已调大")
                }
                "vol_down" -> {
                    audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ApiResult(true, "音量已调小")
                }
                "vol_mute" -> {
                    if (Build.VERSION.SDK_INT >= 23) {
                        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    }
                    ApiResult(true, "已切换静音")
                }
                "vol_set" -> {
                    val v = req.volume ?: return@onMain ApiResult(false, error = "missing_volume")
                    val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    audio?.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
                    ApiResult(true, "音量已调整")
                }
                "back" -> {
                    if (nav == null) return@onMain ApiResult(false, error = "电视界面还没就绪")
                    nav.back()
                    ApiResult(true, "已返回")
                }
                "home" -> {
                    if (nav == null) return@onMain ApiResult(false, error = "电视界面还没就绪")
                    nav.goHome()
                    ApiResult(true, "已回到首页")
                }
                "search" -> {
                    if (nav == null) return@onMain ApiResult(false, error = "电视界面还没就绪")
                    val q = req.query?.trim().orEmpty()
                    if (q.isEmpty()) {
                        nav.openSearch()
                        return@onMain ApiResult(true, "已打开电视搜索页")
                    }
                    val hits = runCatching { Catalog.search(q, remote = true) }.getOrDefault(emptyList())
                    val first = hits.firstOrNull()
                    if (first != null) {
                        nav.openDetail(first.id)
                        nav.toast("手机搜索：《${first.name}》")
                        lastEvent = "搜索并打开《${first.name}》"
                        ApiResult(true, "已在电视打开《${first.name}》")
                    } else {
                        nav.openSearch()
                        nav.toast("豆瓣没找到「$q」，请在搜索页查片源")
                        ApiResult(false, error = "没搜到「$q」")
                    }
                }
                else -> ApiResult(false, error = "未知指令：${req.cmd}")
            }
        }

        override fun sync(payload: SyncPayload): SyncPayload = onMain(15_000L) {
            val res = Store.applySync(payload)
            if (res.progressUpdated > 0 || res.wishAddedOrChanged > 0) {
                Navigator.current?.toast("已与手机同步（进度 ${res.progressUpdated} · 想看 ${res.wishAddedOrChanged}）")
                lastEvent = "已与手机同步记录"
            }
            Store.exportSyncPayload()
        }

        override fun listSources(): SourcesResponse = onMain(5_000L) {
            SourcesResponse(currentUrl = Sources.url, list = Sources.list.toList())
        }

        override fun addSource(req: AddSourceRequest): SourcesResponse {
            val added = runBlocking {
                val job = scope.async { Sources.add(req.name, req.url) }
                withTimeoutOrNull(40_000L) { job.await() } ?: error("超时：电视 40 秒内没下载到这个配置")
            }
            return onMain(5_000L) {
                if (req.select) added.firstOrNull()?.let { Sources.select(it) }
                Navigator.current?.toast("手机已添加 ${added.size} 个片源")
                lastEvent = "手机添源：${added.firstOrNull()?.name.orEmpty()}"
                SourcesResponse(currentUrl = Sources.url, list = Sources.list.toList(), added = added)
            }
        }

        override fun selectSource(url: String): SourcesResponse = onMain(5_000L) {
            val target = Sources.list.firstOrNull { it.url == url }
                ?: throw IllegalArgumentException("电视上没有这个片源")
            Sources.select(target)
            Navigator.current?.toast("手机切换片源：${target.name}")
            lastEvent = "切源：${target.name}"
            SourcesResponse(currentUrl = Sources.url, list = Sources.list.toList())
        }

        override fun removeSource(url: String): SourcesResponse = onMain(5_000L) {
            val target = Sources.list.firstOrNull { it.url == url }
                ?: throw IllegalArgumentException("电视上没有这个片源")
            Sources.remove(target)
            Navigator.current?.toast("手机删除片源：${target.name}")
            SourcesResponse(currentUrl = Sources.url, list = Sources.list.toList())
        }
    }

    private class HttpServer(port: Int, private val router: LinkRouter) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val body = if (session.method == Method.POST || session.method == Method.PUT) {
                val files = HashMap<String, String>()
                runCatching { session.parseBody(files) }
                files["postData"].orEmpty()
            } else ""
            val reply = router.handle(session.method.name, session.uri, session.headers, body)
            val status = mapStatus(reply.status)
            return newFixedLengthResponse(status, reply.contentType, reply.body).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, ${LinkProtocol.HEADER_TOKEN}")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            }
        }

        private fun mapStatus(code: Int): Response.IStatus = when (code) {
            200 -> Response.Status.OK
            204 -> Response.Status.NO_CONTENT
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            429 -> object : Response.IStatus {
                override fun getDescription() = "429 Too Many Requests"
                override fun getRequestStatus() = 429
            }
            else -> Response.Status.INTERNAL_ERROR
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
