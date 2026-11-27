package dev.personal.tv.link

import com.google.gson.Gson
import dev.personal.tv.data.Kind
import dev.personal.tv.data.Progress
import dev.personal.tv.data.ResumeRef
import dev.personal.tv.data.Title
import dev.personal.tv.source.Sources
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLinkTest {
    private val gson = Gson()

    private fun sampleTitle(id: String, name: String, full: Boolean = false) = Title(
        id = id,
        name = name,
        year = 2024,
        region = "中国大陆",
        kind = Kind.SERIES,
        genres = listOf("剧情"),
        score = 8.5f,
        totalEps = 24,
        airedEps = 24,
        full = full,
    )

    @Test fun qrBuildAndParseRoundTrip() {
        val uri = LinkQr.buildUri("192.168.1.25", 9981, "482910", "客厅电视 (Shield)", "abc12345")
        val parsed = LinkQr.parseInput(uri)
        assertNotNull(parsed)
        assertEquals("192.168.1.25", parsed!!.host)
        assertEquals(9981, parsed.port)
        assertEquals("482910", parsed.code)
        assertEquals("客厅电视 (Shield)", parsed.name)
        assertEquals("abc12345", parsed.deviceId)
    }

    @Test fun manualAddressParsingHandlesCommonForms() {
        assertEquals(PairTarget("192.168.1.8", 9985), LinkQr.parseInput("http://192.168.1.8:9985/"))
        assertEquals(PairTarget("10.0.0.2", 9981), LinkQr.parseInput("10.0.0.2:9981"))
        assertEquals(PairTarget("192.168.31.100", LinkProtocol.DEFAULT_PORT), LinkQr.parseInput("192.168.31.100"))
        assertNull(LinkQr.parseInput(""))
        assertNull(LinkQr.parseInput("192.168.1.1:99999"))
        assertNull(LinkQr.parseInput("not a valid host"))
    }

    @Test fun syncProgressNewerWinsAndProtectsLocalLineMetadata() {
        val t1 = sampleTitle("101", "三体", full = true)
        val t2 = sampleTitle("102", "繁花", full = false)
        val localRef = ResumeRef("cfgA", "siteA", "站A", "4K", "vod1", "三体", "第3集", 3)
        val localSnap = LinkSync.StoreSnapshot(
            progress = mapOf(
                // 同集：远端更新，应该更新进度但保留本地线路和冷启动线索
                "101" to Progress("101", 3, 120, 2700, 1_000L, line = "siteA|4K", pinned = true, epName = "第3集", resume = localRef),
                // 本地更新：保持本地
                "102" to Progress("102", 5, 600, 2700, 5_000L, line = "siteB|HD", epName = "第5集"),
            ),
            wish = listOf("101"),
            wishOps = mapOf("101" to SyncOp(true, 1_000L)),
            hidden = emptyList(),
            hiddenOps = emptyMap(),
            titles = mapOf("101" to t1, "102" to t2),
        )
        val remote = SyncPayload(
            progress = listOf(
                Progress("101", 3, 980, 2700, 4_000L, line = "cms_phone|m3u8", epName = "第3集"),
                Progress("102", 4, 200, 2700, 2_000L, epName = "第4集"),
                // 跨集更新：新片来自远端，应清空远端私有 line/resume
                Progress("103", 2, 450, 2500, 6_000L, line = "cms_phone|m3u8", pinned = true, epName = "第2集"),
            ),
            titles = listOf(sampleTitle("101", "三体", full = false), sampleTitle("103", "漫长的季节")),
        )

        val merged = LinkSync.merge(localSnap, remote)
        assertEquals(2, merged.progressUpdated)

        val p101 = merged.progress["101"]!!
        assertEquals(980, p101.positionSec)
        assertEquals(4_000L, p101.lastWatched)
        assertEquals("siteA|4K", p101.line)
        assertTrue(p101.pinned)
        assertEquals(localRef, p101.resume)

        val p102 = merged.progress["102"]!!
        assertEquals(5, p102.ep)
        assertEquals(600, p102.positionSec)

        val p103 = merged.progress["103"]!!
        assertEquals(2, p103.ep)
        assertEquals(450, p103.positionSec)
        assertNull(p103.line)
        assertFalse(p103.pinned)
        assertNull(p103.resume)

        // 101 本地已是 full=true，不会被远端 full=false 降级
        assertTrue(merged.titlesToUpsert.any { it.id == "103" })
        assertFalse(merged.titlesToUpsert.any { it.id == "101" && !it.full })
    }

    @Test fun syncWishAndHiddenConvergeAcrossTwoDevices() {
        val devA = LinkSync.StoreSnapshot(
            progress = emptyMap(),
            wish = listOf("a", "b"),
            wishOps = mapOf("a" to SyncOp(true, 1_000L), "b" to SyncOp(true, 2_000L)),
            hidden = emptyList(),
            hiddenOps = emptyMap(),
            titles = emptyMap(),
        )
        val devB = LinkSync.StoreSnapshot(
            progress = emptyMap(),
            wish = listOf("c"),
            wishOps = mapOf(
                "a" to SyncOp(false, 3_000L), // B 在更晚时间移除了 a
                "c" to SyncOp(true, 4_000L),  // B 新增了 c
            ),
            hidden = listOf("d"),
            hiddenOps = mapOf("d" to SyncOp(true, 5_000L)),
            titles = emptyMap(),
        )

        val payloadFromB = SyncPayload(
            wish = devB.wish,
            wishOps = devB.wishOps,
            hidden = devB.hidden,
            hiddenOps = devB.hiddenOps,
        )
        val mergedOnA = LinkSync.merge(devA, payloadFromB)
        assertEquals(listOf("c", "b"), mergedOnA.wish)
        assertEquals(listOf("d"), mergedOnA.hidden)

        val payloadBackToB = SyncPayload(
            wish = mergedOnA.wish,
            wishOps = mergedOnA.wishOps,
            hidden = mergedOnA.hidden,
            hiddenOps = mergedOnA.hiddenOps,
        )
        val mergedOnB = LinkSync.merge(devB, payloadBackToB)
        assertEquals(mergedOnA.wish, mergedOnB.wish)
        assertEquals(mergedOnA.hidden, mergedOnB.hidden)
    }

    @Test fun routerAuthRateLimitAndEndToEndHttpServer() {
        var now = 100_000L
        val sources = mutableListOf(Sources.Entry("默认源", "https://example.com/a.json"))
        var currentSource: String? = sources.first().url
        val casted = mutableListOf<CastRequest>()
        val controls = mutableListOf<ControlRequest>()

        val fakeHost = object : LinkHost {
            override fun deviceInfo() = DeviceInfo(1, "tv-01", "客厅电视", "0.9.16", "tv")
            override fun currentPairCode() = "482910"
            override fun currentAuthToken() = "tok_secret_1234567890"
            override fun status() = TvStatus("player", VolumeState(8, 15, false), null)
            override fun cast(req: CastRequest): ApiResult {
                casted += req
                return ApiResult(true, "已投屏")
            }
            override fun control(req: ControlRequest): ApiResult {
                controls += req
                return ApiResult(true, "OK:${req.cmd}")
            }
            override fun sync(payload: SyncPayload) = payload
            override fun listSources() = SourcesResponse(currentSource, sources.toList())
            override fun addSource(req: AddSourceRequest): SourcesResponse {
                val e = Sources.Entry(req.name ?: "新源", req.url)
                sources += e
                if (req.select) currentSource = e.url
                return SourcesResponse(currentSource, sources.toList(), added = listOf(e))
            }
            override fun selectSource(url: String): SourcesResponse {
                currentSource = url
                return SourcesResponse(currentSource, sources.toList())
            }
            override fun removeSource(url: String): SourcesResponse {
                sources.removeAll { it.url == url }
                if (currentSource == url) currentSource = sources.firstOrNull()?.url
                return SourcesResponse(currentSource, sources.toList())
            }
        }

        val router = LinkRouter(fakeHost, gson) { now }

        // 1. /info 无需鉴权
        val infoReply = router.handle("GET", "/api/v1/info", emptyMap(), "")
        assertEquals(200, infoReply.status)
        assertEquals("客厅电视", gson.fromJson(infoReply.body, DeviceInfo::class.java).name)

        // 2. 受保护接口无令牌返回 401
        assertEquals(401, router.handle("GET", "/api/v1/status", emptyMap(), "").status)
        assertEquals(401, router.handle("POST", "/api/v1/cast", mapOf("Authorization" to "Bearer wrong"), "{}").status)

        // 3. 配对码输错 5 次触发 429 限流，过 61 秒恢复
        repeat(5) {
            val bad = router.handle("POST", "/api/v1/pair", emptyMap(), """{"code":"000000"}""")
            assertEquals(403, bad.status)
        }
        assertEquals(429, router.handle("POST", "/api/v1/pair", emptyMap(), """{"code":"482910"}""").status)
        now += 61_000L

        // 4. 正确配对（支持带空格）拿 token
        val okPair = router.handle("POST", "/api/v1/pair", emptyMap(), """{"code":"482 910","clientName":"Pixel"}""")
        assertEquals(200, okPair.status)
        val token = gson.fromJson(okPair.body, PairResponse::class.java).token
        assertEquals("tok_secret_1234567890", token)

        // 5. 真 NanoHTTPD + OkHttp 本地回环端到端测试
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val body = if (session.method == Method.POST) {
                    val files = HashMap<String, String>()
                    runCatching { session.parseBody(files) }
                    files["postData"].orEmpty()
                } else ""
                val r = router.handle(session.method.name, session.uri, session.headers, body)
                val st = when (r.status) {
                    200 -> Response.Status.OK
                    400 -> Response.Status.BAD_REQUEST
                    401 -> Response.Status.UNAUTHORIZED
                    403 -> Response.Status.FORBIDDEN
                    404 -> Response.Status.NOT_FOUND
                    else -> Response.Status.INTERNAL_ERROR
                }
                return newFixedLengthResponse(st, r.contentType, r.body)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            val http = OkHttpClient()
            val jsonType = "application/json; charset=utf-8".toMediaType()

            // Cast
            val castJson = gson.toJson(CastRequest(title = sampleTitle("201", "庆余年"), titleId = "201", ep = 7, posMs = 65_000L))
            http.newCall(
                Request.Builder().url("$base/api/v1/cast")
                    .header("Authorization", "Bearer $token")
                    .post(castJson.toRequestBody(jsonType)).build(),
            ).execute().use { resp ->
                assertEquals(200, resp.code)
            }
            assertEquals(1, casted.size)
            assertEquals(7, casted[0].ep)
            assertEquals(65_000L, casted[0].posMs)

            // Control
            http.newCall(
                Request.Builder().url("$base/api/v1/control")
                    .header("Authorization", "Bearer $token")
                    .post(gson.toJson(ControlRequest("seek_by", deltaSec = 15)).toRequestBody(jsonType)).build(),
            ).execute().use { resp ->
                assertEquals(200, resp.code)
            }
            assertEquals("seek_by", controls.single().cmd)

            // Sources add + select + remove
            http.newCall(
                Request.Builder().url("$base/api/v1/sources/add")
                    .header("Authorization", "Bearer $token")
                    .post(gson.toJson(AddSourceRequest("https://example.com/b.json", "备用源", select = true)).toRequestBody(jsonType)).build(),
            ).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = gson.fromJson(resp.body!!.string(), SourcesResponse::class.java)
                assertEquals("https://example.com/b.json", body.currentUrl)
                assertEquals(2, body.list.size)
            }
        } finally {
            server.stop()
        }
    }
}
