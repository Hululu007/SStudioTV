package dev.personal.tv.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/**
 * 已保存配置的只读预览（片源页 → 在某个片源上按 → ）。
 * 不碰全局状态：不 select、不写 Sources.cfg、不进 Sites 缓存、不起 :spider 进程、不跑任何 jar。
 * 站点目录只开放 CMS（type 0/1，纯 HTTP 取 JSON/XML）；JAR/JS 站点只列出来，不会被调用。
 */
object ConfigPreview {
    const val CONFIG_TIMEOUT_MS = 35_000L
    const val SITE_TIMEOUT_MS = 25_000L

    /** 只有纯 HTTP 的采集站能预览，插件站点要执行 jar，预览里不做 */
    fun canBrowse(s: SiteCfg) = s.kind == SiteKind.CMS || s.kind == SiteKind.CMS_XML

    /** 当前片源直接用已缓存的；其它的下载一份，用完即弃 */
    suspend fun config(e: Sources.Entry): TvConfig =
        if (e.url == Sources.url) withTimeout(CONFIG_TIMEOUT_MS) { Sources.config() }
        else bounded(CONFIG_TIMEOUT_MS) { ConfigLoader.load(e.url) }

    /** 站点最近更新的片子（CMS 首页），带集数目录的就直接带着 */
    suspend fun recent(s: SiteCfg): List<Vod> {
        require(canBrowse(s)) { "${s.kind.label} 站点不会在预览里运行" }
        return bounded(SITE_TIMEOUT_MS) { api(s).home().list.filter { it.name.isNotBlank() }.take(40) }
    }

    /** 一部片子的播放来源 + 集目录；列表里没带集数就按 id 取一次详情 */
    suspend fun catalog(s: SiteCfg, v: Vod): List<PlayFlag> {
        require(canBrowse(s)) { "${s.kind.label} 站点不会在预览里运行" }
        if (v.flags.isNotEmpty()) return v.flags
        return bounded(SITE_TIMEOUT_MS) { api(s).detail(v.id)?.flags.orEmpty() }
    }

    private fun api(s: SiteCfg): SiteApi = if (s.kind == SiteKind.CMS_XML) CmsXmlSite(s) else CmsSite(s)

    /**
     * 阻塞的网络调用没法被取消：超时 / 返回键后不再等它（okhttp 自带 callTimeout，最多 30 秒内线程自己结束），结果直接丢掉。
     */
    private suspend fun <T> bounded(ms: Long, block: () -> T): T {
        val job = bgIoScope.async { block() }
        return try {
            withTimeout(ms) { job.await() }
        } finally {
            job.cancel()
        }
    }
}
