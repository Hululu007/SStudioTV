package dev.personal.tv.link

import com.google.gson.Gson
import java.util.ArrayDeque

/**
 * 纯 JVM 可测的电视端 API 路由器：负责鉴权、配对限流、请求反序列化与分发。
 * 由 [TvLinkServer] 接入 NanoHTTPD 和 Android 宿主实现。
 */
interface LinkHost {
    fun deviceInfo(): DeviceInfo
    fun currentPairCode(): String
    fun currentAuthToken(): String
    fun onPaired(clientName: String) {}
    fun status(): TvStatus
    fun cast(req: CastRequest): ApiResult
    fun control(req: ControlRequest): ApiResult
    fun sync(payload: SyncPayload): SyncPayload
    fun listSources(): SourcesResponse
    fun addSource(req: AddSourceRequest): SourcesResponse
    fun selectSource(url: String): SourcesResponse
    fun removeSource(url: String): SourcesResponse
}

data class HttpReply(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
)

class LinkRouter(
    private val host: LinkHost,
    private val gson: Gson = Gson(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val failedPairs = ArrayDeque<Long>()

    fun handle(method: String, rawUri: String, headers: Map<String, String>, body: String): HttpReply {
        val m = method.uppercase()
        val path = rawUri.substringBefore('?').trimEnd('/').ifEmpty { "/" }

        // CORS / 预检（便于局域网调试或浏览器工具）
        if (m == "OPTIONS") return HttpReply(204, "")

        if (m == "GET" && path == "/api/v1/info") {
            return json(200, host.deviceInfo())
        }

        if (m == "POST" && path == "/api/v1/pair") {
            return handlePair(body)
        }

        // 其余 /api/v1/* 均需已配对的令牌
        if (!path.startsWith("/api/v1/")) {
            return json(404, ApiResult(false, error = "not_found"))
        }
        val token = extractToken(headers)
        if (token.isNullOrEmpty() || !LinkQr.constantTimeEquals(token, host.currentAuthToken())) {
            return json(401, ApiResult(false, error = "unauthorized"))
        }

        return try {
            when {
                m == "GET" && path == "/api/v1/status" -> json(200, host.status())
                m == "POST" && path == "/api/v1/cast" -> {
                    val req = parseBody<CastRequest>(body) ?: return json(400, ApiResult(false, error = "invalid_json"))
                    if (req.titleId.isBlank() && req.title == null) {
                        return json(400, ApiResult(false, error = "missing_title_id"))
                    }
                    val res = host.cast(req)
                    json(if (res.ok) 200 else 400, res)
                }
                m == "POST" && path == "/api/v1/control" -> {
                    val req = parseBody<ControlRequest>(body) ?: return json(400, ApiResult(false, error = "invalid_json"))
                    if (req.cmd.isBlank()) return json(400, ApiResult(false, error = "missing_cmd"))
                    val res = host.control(req)
                    json(if (res.ok) 200 else 400, res)
                }
                m == "POST" && path == "/api/v1/sync" -> {
                    val payload = if (body.isBlank()) SyncPayload() else (parseBody<SyncPayload>(body)
                        ?: return json(400, ApiResult(false, error = "invalid_json")))
                    json(200, host.sync(payload))
                }
                m == "GET" && path == "/api/v1/sources" -> json(200, host.listSources())
                m == "POST" && path == "/api/v1/sources/add" -> {
                    val req = parseBody<AddSourceRequest>(body) ?: return json(400, ApiResult(false, error = "invalid_json"))
                    if (req.url.isBlank()) return json(400, ApiResult(false, error = "missing_url"))
                    json(200, host.addSource(req))
                }
                m == "POST" && path == "/api/v1/sources/select" -> {
                    val req = parseBody<SelectSourceRequest>(body) ?: return json(400, ApiResult(false, error = "invalid_json"))
                    if (req.url.isBlank()) return json(400, ApiResult(false, error = "missing_url"))
                    json(200, host.selectSource(req.url))
                }
                m == "POST" && path == "/api/v1/sources/remove" -> {
                    val req = parseBody<RemoveSourceRequest>(body) ?: return json(400, ApiResult(false, error = "invalid_json"))
                    if (req.url.isBlank()) return json(400, ApiResult(false, error = "missing_url"))
                    json(200, host.removeSource(req.url))
                }
                else -> json(404, ApiResult(false, error = "not_found"))
            }
        } catch (e: IllegalArgumentException) {
            json(400, ApiResult(false, error = e.message ?: "bad_request"))
        } catch (e: Throwable) {
            json(500, ApiResult(false, error = e.message?.take(160) ?: "internal_error"))
        }
    }

    private fun handlePair(body: String): HttpReply {
        val now = clock()
        synchronized(failedPairs) {
            while (failedPairs.isNotEmpty() && now - failedPairs.first() > 60_000L) {
                failedPairs.removeFirst()
            }
            if (failedPairs.size >= 5) {
                return json(429, ApiResult(false, error = "配对码输错次数过多，请 1 分钟后再试"))
            }
        }
        val req = parseBody<PairRequest>(body) ?: return json(400, ApiResult(false, error = "invalid_json"))
        val code = req.code.replace(" ", "").trim()
        val expected = host.currentPairCode()
        if (code.isEmpty() || !LinkQr.constantTimeEquals(code, expected)) {
            synchronized(failedPairs) { failedPairs.addLast(now) }
            return json(403, ApiResult(false, error = "配对码不对"))
        }
        host.onPaired(req.clientName.trim())
        val info = host.deviceInfo()
        return json(
            200,
            PairResponse(
                token = host.currentAuthToken(),
                deviceId = info.deviceId,
                name = info.name,
                version = info.version,
                protocolVersion = info.protocolVersion,
            ),
        )
    }

    private fun extractToken(headers: Map<String, String>): String? {
        val lower = headers.entries.associate { it.key.lowercase() to it.value }
        lower["authorization"]?.trim()?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                return auth.substring(7).trim().takeIf { it.isNotEmpty() }
            }
        }
        return lower[LinkProtocol.HEADER_TOKEN.lowercase()]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private inline fun <reified T> parseBody(raw: String): T? =
        runCatching { gson.fromJson(raw, T::class.java) }.getOrNull()

    private fun json(status: Int, data: Any): HttpReply =
        HttpReply(status, gson.toJson(data))
}
