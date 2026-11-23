package dev.personal.tv.source

import dev.personal.tv.data.Progress
import dev.personal.tv.data.ResumeRef
import dev.personal.tv.data.Title
import java.security.MessageDigest

/**
 * 冷启动续播的纯逻辑（没有 Android 依赖，单测直接跑）。
 *
 * 想法：上次成功起播的站点 + 来源 + 详情 id 记在进度里（[ResumeRef]），下次打开先去那个站点取一次详情、按分集名找到同一集直接播，
 * 不用等所有站点重新搜一遍。取不到、对不上就照旧走完整搜索。
 * 不存：cookie、播放地址、配置地址本身（只存指纹）。
 */
object ColdResume {
    /** 片源配置地址的指纹；换了片源（地址不同）就对不上。地址里可能带口令，不原样存 */
    fun configId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val d = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private val SENSITIVE = Regex("(token|sign|auth|cookie|passw|secret|expire|session|key)=", RegexOption.IGNORE_CASE)

    /** 站点详情 id 能不能落盘：只收普通 id；像播放地址的、带查询参数（口令、签名）的都不存 */
    fun safeId(id: String): Boolean =
        id.isNotBlank() && id.length <= 160 && id.none { it.isWhitespace() || it.isISOControl() } &&
            !PlayLine.DIRECT.containsMatchIn(id) && !SENSITIVE.containsMatchIn(id) &&
            !(id.contains("://") && (id.contains('?') || id.contains('#') || id.substringAfter("://").substringBefore('/').contains('@')))

    /** 播成功后记什么；本地线路、没有详情 id、id 不安全的返回 null（调用方保留旧的，别清掉） */
    fun refFor(cfgUrl: String?, l: PlayLine, epName: String, epIdx: Int, localKey: String): ResumeRef? {
        if (l.siteKey == localKey || !safeId(l.vodId)) return null
        val cfg = configId(cfgUrl) ?: return null
        return ResumeRef(cfg, l.siteKey, l.siteName, l.flag, l.vodId, l.vodName, epName, epIdx)
    }

    /**
     * 记录能不能用来冷启动：配置指纹对得上、站点还在（没被屏蔽）、记的是这一集。
     * 老记录（没有 resume）、字段被写坏（Gson 读出 null）都按不能用。
     */
    @Suppress("SENSELESS_COMPARISON")
    fun usable(
        ref: ResumeRef?, cfgUrl: String?, siteKeys: Collection<String>, blocked: Collection<String>,
        wantName: String?, wantEp: Int, title: String,
    ): Boolean {
        if (ref == null) return false
        if (ref.siteName == null || ref.vodName == null || ref.cfg == null || ref.siteKey == null || ref.flag == null || ref.vodId == null || ref.epName == null) return false
        if (ref.cfg != configId(cfgUrl)) return false
        if (ref.siteKey !in siteKeys || ref.siteKey in blocked) return false
        if (!safeId(ref.vodId)) return false
        // 记的得是要放的这一集：有名字按名字，没名字按名字里的集数
        return if (wantName != null) Eps.same(wantName, ref.epName, title) else Eps.number(ref.epName, title) == wantEp
    }

    /** 站点详情里取出记录的那个来源，做成一条线路；没有这个来源、分集是空的、详情明显不是原来那部返回 null */
    fun buildLine(ref: ResumeRef, vod: Vod?, title: String): PlayLine? {
        vod ?: return null
        val f = vod.flags.firstOrNull { it.name == ref.flag && it.episodes.isNotEmpty() } ?: return null
        if (ref.vodName.isNotBlank() && vod.name.isNotBlank() && !sameVod(ref.vodName, vod.name)) return null
        val eps = Eps.ascending(Eps.trimCommon(f.episodes), title)
        return PlayLine(ref.siteKey, ref.siteName, f.name, vod.name.ifBlank { ref.vodName }, vod.remarks, eps, ref.vodId)
    }

    private fun sameVod(a: String, b: String): Boolean {
        val x = a.filterNot { it.isWhitespace() }
        val y = b.filterNot { it.isWhitespace() }
        return x == y || x.contains(y) || y.contains(x)
    }

    /**
     * 要放的这一集，是不是进度里记的那一集。
     * 有名字的按名字（综艺上下期、加更）；进度里没名字的老记录按集数。
     * 没名字时只拿集数比，而且进度里的 ep 是「线路里的位置」（按名字分期的）就不比，免得把位置当集数。
     */
    fun sameEpisode(prev: Progress?, startEp: Int, startName: String?, title: String): Boolean {
        prev ?: return false
        if (startName != null) {
            if (prev.epName != null) return Eps.same(startName, prev.epName, title)
            val n = Eps.number(startName, title)
            return !prev.isNamed(title) && n != null && n == prev.ep
        }
        return !prev.isNamed(title) && prev.episodeNo(title) == startEp
    }

    /** 开播位置（毫秒）：只在是同一集、而且没看完时接着看，别的集一律从头 */
    fun startMs(prev: Progress?, startEp: Int, startName: String?, title: String): Long =
        if (prev != null && !prev.finishedEp && sameEpisode(prev, startEp, startName, title)) prev.positionSec * 1000L else 0L

    /**
     * 这一集在某条线路里是第几个（1 开始），找不到 = null。
     * [ep]、[epName]：要放的这一集；[curSize]：现在这条线路的集数（还没线路 = 1）。
     * 「只有一个文件」的捷径只给真正的电影（豆瓣不是 tv 条目、也不是综艺）：
     * 剧集里某条线路只有一个文件（只下载了第 5 集、站点只放了预告）不能当成第 1 集，
     * 综艺、纪录片里只有一期的线路也不能当成要放的那一期（可能是另一期）。
     */
    fun indexIn(eps: List<Episode>, ep: Int, epName: String?, curSize: Int, t: Title): Int? {
        if (ep == 1 && eps.size == 1 && curSize == 1 && !t.isEpisodic && !t.isVariety) return 1
        val n = epName ?: return Eps.indexOfNumber(eps, ep, t.name).takeIf { it >= 0 }?.plus(1)
        // 选集行按名字点的、这个位置上正好是这个名字：直接用（同一条线路里有好几个「加更版」时，不能都放第一个）
        if (eps.getOrNull(ep - 1)?.name == n) return ep
        val i = Eps.indexOf(eps, n, t.name)
        if (i >= 0) return i + 1
        val num = Eps.number(n, t.name) ?: return null
        if (Eps.dated(eps, t.name)) return null
        return Eps.indexOfNumber(eps, num, t.name).takeIf { it >= 0 }?.plus(1)
    }
}
