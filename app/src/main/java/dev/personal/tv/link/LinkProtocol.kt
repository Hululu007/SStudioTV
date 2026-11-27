package dev.personal.tv.link

import dev.personal.tv.data.Progress
import dev.personal.tv.data.Title
import dev.personal.tv.source.ColdResume
import dev.personal.tv.source.Sources
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 电视 ↔ 手机局域网联动协议（v1，纯 JSON over HTTP + mDNS）。
 * 后续 iPhone SwiftUI 客户端可直接复用同一套接口，无需云端服务器。
 */
object LinkProtocol {
    const val PROTOCOL_VERSION = 1
    /** Android NSD & Apple Bonjour 通用的服务类型 */
    const val MDNS_SERVICE_TYPE = "_sstudiotv._tcp."
    val PORT_RANGE = 9981..9991
    const val DEFAULT_PORT = 9981
    const val QR_SCHEME = "sstudiotv"
    const val HEADER_TOKEN = "X-SStudio-Token"
}

/** 单条集合项（想看 / 不感兴趣）的最后变更记录，用于无冲突双向合并 */
data class SyncOp(
    val present: Boolean,
    val at: Long,
)

data class DeviceInfo(
    val protocolVersion: Int = LinkProtocol.PROTOCOL_VERSION,
    val deviceId: String,
    val name: String,
    val version: String,
    val mode: String,
)

data class PairRequest(
    val code: String = "",
    val clientName: String = "",
)

data class PairResponse(
    val token: String,
    val deviceId: String,
    val name: String,
    val version: String,
    val protocolVersion: Int = LinkProtocol.PROTOCOL_VERSION,
)

data class VolumeState(
    val level: Int,
    val max: Int,
    val muted: Boolean,
)

data class LineSummary(
    val key: String,
    val siteName: String,
    val flag: String,
    val direct: Boolean,
    val epCount: Int,
    val failed: Boolean,
)

data class EpSummary(
    val index: Int,
    val name: String,
)

data class PlaybackState(
    val titleId: String,
    val titleName: String,
    val poster: String? = null,
    val ep: Int,
    val epName: String? = null,
    val epText: String,
    val episodeCount: Int,
    val posMs: Long,
    val durMs: Long,
    val playing: Boolean,
    val speed: Float,
    val phase: String,
    val status: String? = null,
    val fail: String? = null,
    val lineKey: String? = null,
    val lineName: String? = null,
    val nextName: String? = null,
    val lines: List<LineSummary> = emptyList(),
    val episodes: List<EpSummary> = emptyList(),
)

data class TvStatus(
    val route: String,
    val volume: VolumeState? = null,
    val playback: PlaybackState? = null,
)

data class CastRequest(
    val title: Title? = null,
    val titleId: String = "",
    val ep: Int = 1,
    val epName: String? = null,
    val posMs: Long = -1L,
    /** "play" = 直接起播（默认）；"detail" = 打开详情页 */
    val action: String = "play",
)

data class ControlRequest(
    val cmd: String = "",
    val posMs: Long? = null,
    val deltaSec: Int? = null,
    val ep: Int? = null,
    val epName: String? = null,
    val lineKey: String? = null,
    val speed: Float? = null,
    val query: String? = null,
    val volume: Int? = null,
)

data class SyncPayload(
    val progress: List<Progress> = emptyList(),
    val wish: List<String> = emptyList(),
    val wishOps: Map<String, SyncOp> = emptyMap(),
    val hidden: List<String> = emptyList(),
    val hiddenOps: Map<String, SyncOp> = emptyMap(),
    val titles: List<Title> = emptyList(),
)

data class SourcesResponse(
    val currentUrl: String? = null,
    val list: List<Sources.Entry> = emptyList(),
    val added: List<Sources.Entry>? = null,
)

data class AddSourceRequest(
    val url: String = "",
    val name: String? = null,
    val select: Boolean = false,
)

data class SelectSourceRequest(
    val url: String = "",
)

data class RemoveSourceRequest(
    val url: String = "",
)

data class ApiResult(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
)

data class PairTarget(
    val host: String,
    val port: Int,
    val code: String? = null,
    val name: String? = null,
    val deviceId: String? = null,
)

/** 二维码 URI 与手动输入地址的编解码 */
object LinkQr {
    fun buildUri(host: String, port: Int, code: String, name: String, deviceId: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "${LinkProtocol.QR_SCHEME}://pair?host=${enc(host)}&port=$port&code=${enc(code)}&name=${enc(name)}&id=${enc(deviceId)}"
    }

    /**
     * 支持：
     * - sstudiotv://pair?host=192.168.1.10&port=9981&code=123456&name=...&id=...
     * - http://192.168.1.10:9981
     * - 192.168.1.10:9981
     * - 192.168.1.10（默认端口 9981）
     */
    fun parseInput(raw: String): PairTarget? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("${LinkProtocol.QR_SCHEME}://", ignoreCase = true)) {
            val qIdx = s.indexOf('?')
            if (qIdx < 0) return null
            val params = HashMap<String, String>()
            s.substring(qIdx + 1).split('&').forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = runCatching { URLDecoder.decode(part.substring(0, eq), "UTF-8") }.getOrDefault(part.substring(0, eq))
                    val v = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrDefault(part.substring(eq + 1))
                    params[k] = v
                }
            }
            val host = params["host"]?.trim().orEmpty()
            val port = params["port"]?.toIntOrNull() ?: LinkProtocol.DEFAULT_PORT
            if (host.isEmpty() || port !in 1..65535) return null
            return PairTarget(
                host = host,
                port = port,
                code = params["code"]?.trim()?.takeIf { it.isNotEmpty() },
                name = params["name"]?.trim()?.takeIf { it.isNotEmpty() },
                deviceId = params["id"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        val stripped = s.removePrefix("http://").removePrefix("https://").substringBefore('/').trim()
        if (stripped.isEmpty()) return null
        val colon = stripped.lastIndexOf(':')
        val (host, port) = if (colon > 0 && stripped.indexOf(':') == colon) {
            val h = stripped.substring(0, colon).trim()
            val p = stripped.substring(colon + 1).trim().toIntOrNull() ?: return null
            h to p
        } else {
            stripped to LinkProtocol.DEFAULT_PORT
        }
        if (host.isEmpty() || host.any { it.isWhitespace() } || port !in 1..65535) return null
        return PairTarget(host = host, port = port)
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/**
 * 纯函数双向同步合并引擎：
 * - 观看进度：按 lastWatched 较新者胜出；保留本机线路与冷启动线索（仅同集保留，跨集清空避免跨设备片源错配）。
 * - 想看 / 不感兴趣：按每条记录的 SyncOp(present, at) 较新者胜出；老数据没有时间戳时按 baseline(at=1) 兜底。
 */
object LinkSync {
    data class StoreSnapshot(
        val progress: Map<String, Progress>,
        val wish: List<String>,
        val wishOps: Map<String, SyncOp>,
        val hidden: List<String>,
        val hiddenOps: Map<String, SyncOp>,
        val titles: Map<String, Title>,
    )

    data class MergeResult(
        val progress: Map<String, Progress>,
        val wish: List<String>,
        val wishOps: Map<String, SyncOp>,
        val hidden: List<String>,
        val hiddenOps: Map<String, SyncOp>,
        val titlesToUpsert: List<Title>,
        val progressUpdated: Int,
        val wishAddedOrChanged: Int,
    )

    /** 发出同步包时剥离本机私有线路与冷启动线索，避免另一端设备套用不匹配的片源站点 */
    fun sanitizeForWire(p: Progress): Progress =
        p.copy(line = null, pinned = false, resume = null)

    fun merge(local: StoreSnapshot, remote: SyncPayload): MergeResult {
        val mergedTitles = HashMap(local.titles)
        val toUpsert = ArrayList<Title>()
        for (t in remote.titles) {
            @Suppress("SENSELESS_COMPARISON")
            if (t == null || t.id == null || t.name == null) continue
            val old = mergedTitles[t.id]
            val next = when {
                old == null -> t
                old.full && !t.full -> old.copy(
                    score = t.score ?: old.score,
                    ratingCount = maxOf(t.ratingCount, old.ratingCount),
                    poster = old.poster ?: t.poster,
                ).withEpsFrom(t)
                else -> t.copy(poster = t.poster ?: old.poster)
            }
            if (old == null || next != old) {
                mergedTitles[t.id] = next
                toUpsert += next
            }
        }

        // 1. 合并观看进度
        val mergedProgress = HashMap(local.progress)
        var progressUpdated = 0
        for (rp in remote.progress) {
            @Suppress("SENSELESS_COMPARISON")
            if (rp == null || rp.titleId == null) continue
            val lp = mergedProgress[rp.titleId]
            if (lp == null) {
                mergedProgress[rp.titleId] = sanitizeForWire(rp)
                progressUpdated++
            } else if (rp.lastWatched > lp.lastWatched) {
                val titleName = mergedTitles[rp.titleId]?.name.orEmpty()
                val sameEp = ColdResume.sameEpisode(lp, rp.ep, rp.epName, titleName)
                mergedProgress[rp.titleId] = rp.copy(
                    line = if (sameEp) lp.line else null,
                    pinned = if (sameEp) lp.pinned else false,
                    resume = if (sameEp) lp.resume else null,
                )
                progressUpdated++
            }
        }

        // 2. 合并想看
        val (mergedWish, mergedWishOps, wishChanged) = mergeList(
            localList = local.wish,
            localOps = local.wishOps,
            remoteList = remote.wish,
            remoteOps = remote.wishOps,
        )

        // 3. 合并「不感兴趣」
        val (mergedHidden, mergedHiddenOps, _) = mergeList(
            localList = local.hidden,
            localOps = local.hiddenOps,
            remoteList = remote.hidden,
            remoteOps = remote.hiddenOps,
        )

        // 若某片在「不感兴趣」的时间更新于「想看」，保持互斥
        val finalWish = mergedWish.filter { id ->
            val h = mergedHiddenOps[id]
            val w = mergedWishOps[id]
            !(h != null && h.present && (w == null || h.at > w.at))
        }

        return MergeResult(
            progress = mergedProgress,
            wish = finalWish,
            wishOps = mergedWishOps,
            hidden = mergedHidden,
            hiddenOps = mergedHiddenOps,
            titlesToUpsert = toUpsert,
            progressUpdated = progressUpdated,
            wishAddedOrChanged = wishChanged,
        )
    }

    private fun mergeList(
        localList: List<String>,
        localOps: Map<String, SyncOp>,
        remoteList: List<String>,
        remoteOps: Map<String, SyncOp>,
    ): Triple<List<String>, Map<String, SyncOp>, Int> {
        val lOps = HashMap<String, SyncOp>(localOps)
        localList.forEach { id -> lOps.putIfAbsent(id, SyncOp(present = true, at = 1L)) }

        val rOps = HashMap<String, SyncOp>(remoteOps)
        remoteList.forEach { id -> rOps.putIfAbsent(id, SyncOp(present = true, at = 1L)) }

        val allIds = LinkedHashSet<String>()
        allIds.addAll(localList)
        allIds.addAll(remoteList)
        allIds.addAll(lOps.keys)
        allIds.addAll(rOps.keys)

        val mergedOps = HashMap<String, SyncOp>()
        var changed = 0
        for (id in allIds) {
            val lo = lOps[id]
            val ro = rOps[id]
            val chosen = when {
                lo == null -> ro!!
                ro == null -> lo
                ro.at > lo.at -> ro
                lo.at > ro.at -> lo
                // 同一毫秒冲突：保留 present = true，不误删
                else -> if (lo.present || ro.present) SyncOp(true, lo.at) else lo
            }
            mergedOps[id] = chosen
            val wasPresent = id in localList
            if (chosen.present != wasPresent) changed++
        }

        val orderIndex = HashMap<String, Int>()
        localList.forEachIndexed { i, id -> orderIndex[id] = i }
        remoteList.forEachIndexed { i, id -> orderIndex.putIfAbsent(id, localList.size + i) }

        val presentList = mergedOps.entries
            .filter { it.value.present }
            .sortedWith(compareByDescending<Map.Entry<String, SyncOp>> { it.value.at }.thenBy { orderIndex[it.key] ?: Int.MAX_VALUE })
            .map { it.key }

        return Triple(presentList, mergedOps, changed)
    }
}
