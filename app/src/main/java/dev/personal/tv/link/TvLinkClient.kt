package dev.personal.tv.link

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.source.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class PairedTv(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val lastConnectedAt: Long = System.currentTimeMillis(),
) {
    val baseUrl: String get() = "http://$host:$port"
}

data class DiscoveredTv(
    val serviceName: String,
    val deviceId: String?,
    val name: String,
    val host: String,
    val port: Int,
    val version: String = "",
)

data class SyncSummary(
    val progressUpdated: Int,
    val wishChanged: Int,
)

/**
 * 手机端电视联动客户端：
 * - NsdManager 自动发现局域网里的 _sstudiotv._tcp. 电视；
 * - 持久化已配对电视列表（SharedPreferences），DHCP 换 IP 后通过 deviceId 自动更新地址；
 * - 提供投屏接力播、双向记录同步、遥控器控制、远程添源/切源接口。
 */
object TvLinkClient {
    private const val TAG = "TvLinkClient"
    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "uncaught", e) },
    )

    private val fastHttp = Net.client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val longHttp = Net.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var sp: SharedPreferences? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveMutex = Mutex()
    private val syncMutex = Mutex()

    val pairedTvs = mutableStateListOf<PairedTv>()
    val discoveredTvs = mutableStateListOf<DiscoveredTv>()

    var activeId by mutableStateOf<String?>(null)
        private set
    val activeTv: PairedTv? get() = pairedTvs.firstOrNull { it.deviceId == activeId } ?: pairedTvs.firstOrNull()

    var discovering by mutableStateOf(false)
        private set
    var online by mutableStateOf(false)
        private set
    var status by mutableStateOf<TvStatus?>(null)
        private set
    var syncing by mutableStateOf(false)
        private set
    var lastSyncAt by mutableLongStateOf(0L)
        private set
    var lastSyncSummary by mutableStateOf<String?>(null)
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val prefs = ctx.applicationContext.getSharedPreferences("tv_link_client", Context.MODE_PRIVATE)
        sp = prefs
        val raw = prefs.getString("paired", null)
        if (!raw.isNullOrBlank()) {
            runCatching { gson.fromJson(raw, Array<PairedTv>::class.java).toList() }.getOrNull()
                ?.filter { it.deviceId.isNotBlank() && it.host.isNotBlank() && it.token.isNotBlank() }
                ?.let { pairedTvs.addAll(it) }
        }
        activeId = prefs.getString("activeId", null)?.takeIf { id -> pairedTvs.any { it.deviceId == id } }
            ?: pairedTvs.firstOrNull()?.deviceId
        lastSyncAt = prefs.getLong("lastSyncAt", 0L)
    }

    private fun savePaired() {
        sp?.edit()
            ?.putString("paired", gson.toJson(pairedTvs.toList()))
            ?.putString("activeId", activeId)
            ?.putLong("lastSyncAt", lastSyncAt)
            ?.apply()
    }

    fun selectTv(deviceId: String) {
        if (pairedTvs.none { it.deviceId == deviceId }) return
        activeId = deviceId
        online = false
        status = null
        savePaired()
    }

    fun unpair(deviceId: String) {
        pairedTvs.removeAll { it.deviceId == deviceId }
        if (activeId == deviceId) {
            activeId = pairedTvs.firstOrNull()?.deviceId
            online = false
            status = null
        }
        savePaired()
    }

    fun startDiscovery(ctx: Context) {
        if (discoveryListener != null) return
        val mgr = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                scope.launch(Dispatchers.Main) { discovering = true }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_sstudiotv")) return
                scope.launch { resolveAndRegister(mgr, serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch(Dispatchers.Main) {
                    discoveredTvs.removeAll { it.serviceName == serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                scope.launch(Dispatchers.Main) { discovering = false }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "startDiscovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stopDiscovery failed: $errorCode")
            }
        }
        nsdManager = mgr
        discoveryListener = listener
        runCatching {
            mgr.discoverServices(LinkProtocol.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(TAG, "discoverServices", it)
            discoveryListener = null
            discovering = false
        }
    }

    fun stopDiscovery() {
        val mgr = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (mgr != null && listener != null) {
            runCatching { mgr.stopServiceDiscovery(listener) }
        }
        scope.launch(Dispatchers.Main) { discovering = false }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveAndRegister(mgr: NsdManager, info: NsdServiceInfo) {
        val resolved = resolveMutex.withLock {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    mgr.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    })
                }
            }
        } ?: return
        val host = resolved.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return
        val port = resolved.port.takeIf { it in 1..65535 } ?: return
        val probed = runCatching { fetchInfo(host, port) }.getOrNull()
        val devId = probed?.deviceId ?: resolved.attributes["id"]?.let { String(it) }
        val name = probed?.name ?: resolved.attributes["name"]?.let { String(it) } ?: resolved.serviceName
        val ver = probed?.version ?: resolved.attributes["ver"]?.let { String(it) }.orEmpty()
        val entry = DiscoveredTv(resolved.serviceName, devId, name, host, port, ver)
        withContext(Dispatchers.Main) {
            discoveredTvs.removeAll { (devId != null && it.deviceId == devId) || (it.host == host && it.port == port) }
            discoveredTvs.add(entry)
            // 已配对电视换了局域网 IP：按 deviceId 自动刷新地址
            if (devId != null) {
                val idx = pairedTvs.indexOfFirst { it.deviceId == devId }
                if (idx >= 0) {
                    val old = pairedTvs[idx]
                    if (old.host != host || old.port != port || old.name != name) {
                        pairedTvs[idx] = old.copy(host = host, port = port, name = name)
                        savePaired()
                    }
                }
            }
        }
    }

    suspend fun fetchInfo(host: String, port: Int): DeviceInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("http://$host:$port/api/v1/info").get().build()
        fastHttp.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) error(parseErr(txt) ?: "HTTP ${r.code}")
            gson.fromJson(txt, DeviceInfo::class.java) ?: error("返回数据格式不对")
        }
    }

    suspend fun pair(host: String, port: Int, code: String): Result<PairedTv> = runCatching {
        val cleanCode = code.replace(" ", "").trim()
        require(cleanCode.isNotEmpty()) { "请输入电视上显示的 6 位配对码" }
        val payload = gson.toJson(PairRequest(code = cleanCode, clientName = clientName()))
        val resp = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("http://$host:$port/api/v1/pair")
                .post(payload.toRequestBody(JSON_TYPE))
                .build()
            fastHttp.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) error(parseErr(txt) ?: "配对失败（HTTP ${r.code}）")
                gson.fromJson(txt, PairResponse::class.java) ?: error("配对响应解析失败")
            }
        }
        val tv = PairedTv(
            deviceId = resp.deviceId,
            name = resp.name.ifBlank { "SStudioTV" },
            host = host,
            port = port,
            token = resp.token,
            lastConnectedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.Main) {
            val idx = pairedTvs.indexOfFirst { it.deviceId == tv.deviceId }
            if (idx >= 0) pairedTvs[idx] = tv else pairedTvs.add(0, tv)
            activeId = tv.deviceId
            online = true
            savePaired()
        }
        // 配对成功后立即做一次后台双向同步
        scope.launch { syncNow() }
        tv
    }

    suspend fun pairWithInput(rawAddressOrQr: String, codeOverride: String? = null): Result<PairedTv> {
        val target = LinkQr.parseInput(rawAddressOrQr)
            ?: return Result.failure(IllegalArgumentException("地址格式不对，请输入如 192.168.1.10:9981 或粘贴配对链接"))
        val code = codeOverride?.trim()?.takeIf { it.isNotEmpty() } ?: target.code
            ?: return Result.failure(IllegalArgumentException("请输入 6 位配对码"))
        return pair(target.host, target.port, code)
    }

    suspend fun refreshStatus(): Result<TvStatus> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching {
            val s = authedGet<TvStatus>(tv, "/api/v1/status")
            withContext(Dispatchers.Main) {
                online = true
                status = s
            }
            s
        }.onFailure {
            withContext(Dispatchers.Main) { online = false }
        }
    }

    suspend fun cast(title: Title, ep: Int = 1, epName: String? = null, posMs: Long = -1L, action: String = "play"): Result<String> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching {
            val res = authedPost<ApiResult>(
                tv,
                "/api/v1/cast",
                CastRequest(title = title, titleId = title.id, ep = ep.coerceAtLeast(1), epName = epName, posMs = posMs, action = action),
            )
            if (!res.ok) error(res.error ?: "投屏失败")
            withContext(Dispatchers.Main) { online = true }
            res.message ?: "已投到 ${tv.name}"
        }.onFailure {
            if (it !is IllegalStateException) withContext(Dispatchers.Main) { online = false }
        }
    }

    /** 方便无协程上下文（如长按菜单）直接投屏并回到主线程提示 */
    fun castAsync(
        title: Title,
        ep: Int = 1,
        epName: String? = null,
        posMs: Long = -1L,
        action: String = "play",
        onDone: (Result<String>) -> Unit,
    ) {
        scope.launch {
            val r = cast(title, ep, epName, posMs, action)
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    /** 回到前台时静默同步一次（至少间隔 60 秒） */
    fun autoSyncIfDue() {
        if (activeTv == null || syncing) return
        if (System.currentTimeMillis() - lastSyncAt < 60_000L) return
        scope.launch { syncNow() }
    }

    suspend fun control(req: ControlRequest): Result<String> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching {
            val res = authedPost<ApiResult>(tv, "/api/v1/control", req)
            if (!res.ok) error(res.error ?: "操作失败")
            withContext(Dispatchers.Main) { online = true }
            res.message.orEmpty()
        }
    }

    suspend fun syncNow(): Result<SyncSummary> = syncMutex.withLock {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        withContext(Dispatchers.Main) { syncing = true }
        try {
            val outgoing = withContext(Dispatchers.Main) { Store.exportSyncPayload() }
            val mergedFromTv = authedPost<SyncPayload>(tv, "/api/v1/sync", outgoing, useLong = true)
            val applied = withContext(Dispatchers.Main) {
                val r = Store.applySync(mergedFromTv)
                online = true
                lastSyncAt = System.currentTimeMillis()
                lastSyncSummary = if (r.progressUpdated == 0 && r.wishAddedOrChanged == 0) {
                    "已是最新"
                } else {
                    "更新进度 ${r.progressUpdated} · 想看 ${r.wishAddedOrChanged}"
                }
                savePaired()
                r
            }
            Result.success(SyncSummary(applied.progressUpdated, applied.wishAddedOrChanged))
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { online = false }
            Result.failure(e)
        } finally {
            withContext(Dispatchers.Main) { syncing = false }
        }
    }

    suspend fun listTvSources(): Result<SourcesResponse> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching { authedGet<SourcesResponse>(tv, "/api/v1/sources") }
    }

    suspend fun addTvSource(url: String, name: String? = null, select: Boolean = false): Result<SourcesResponse> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching {
            authedPost<SourcesResponse>(tv, "/api/v1/sources/add", AddSourceRequest(url = url.trim(), name = name?.trim()?.takeIf { it.isNotEmpty() }, select = select), useLong = true)
        }
    }

    suspend fun selectTvSource(url: String): Result<SourcesResponse> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching { authedPost<SourcesResponse>(tv, "/api/v1/sources/select", SelectSourceRequest(url)) }
    }

    suspend fun removeTvSource(url: String): Result<SourcesResponse> {
        val tv = activeTv ?: return Result.failure(IllegalStateException("还没有配对电视"))
        return runCatching { authedPost<SourcesResponse>(tv, "/api/v1/sources/remove", RemoveSourceRequest(url)) }
    }

    private suspend inline fun <reified T> authedGet(tv: PairedTv, path: String): T = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(tv.baseUrl + path)
            .header("Authorization", "Bearer ${tv.token}")
            .get()
            .build()
        fastHttp.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (r.code == 401) error("配对已失效，请重新配对电视")
            if (!r.isSuccessful) error(parseErr(txt) ?: "HTTP ${r.code}")
            gson.fromJson(txt, T::class.java) ?: error("响应解析失败")
        }
    }

    private suspend inline fun <reified T> authedPost(tv: PairedTv, path: String, payload: Any, useLong: Boolean = false): T =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(payload).toRequestBody(JSON_TYPE)
            val req = Request.Builder()
                .url(tv.baseUrl + path)
                .header("Authorization", "Bearer ${tv.token}")
                .post(body)
                .build()
            val client = if (useLong) longHttp else fastHttp
            client.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (r.code == 401) error("配对已失效，请重新配对电视")
                if (!r.isSuccessful) error(parseErr(txt) ?: "HTTP ${r.code}")
                gson.fromJson(txt, T::class.java) ?: error("响应解析失败")
            }
        }

    private fun parseErr(raw: String): String? =
        runCatching { gson.fromJson(raw, ApiResult::class.java)?.error?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun clientName(): String = Build.MODEL?.trim().takeIf { !it.isNullOrEmpty() } ?: "Android 手机"
}
