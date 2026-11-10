package dev.personal.tv.source

import android.content.Context
import dev.personal.tv.spider.SpiderClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 一个站点能做的事。全部是阻塞调用，调用方放在 IO 线程 */
interface SiteApi {
    fun home(): VodPage
    fun category(tid: String, page: Int): VodPage
    fun search(wd: String): List<Vod>
    fun detail(id: String): Vod?
    fun play(flag: String, id: String): PlayInfo
    /** 插件自定义的「这个地址是不是视频」（嗅探时用）；没有就返回 null */
    fun videoCheck(): ((String) -> Boolean)? = null
}

/** 苹果 CMS JSON 接口（type 1）：?ac=list / ?ac=detail&wd= / ?ac=detail&ids= */
class CmsSite(private val cfg: SiteCfg) : SiteApi {

    private fun call(vararg q: Pair<String, String>): String {
        val b = cfg.api.toHttpUrl().newBuilder()
        q.forEach { (k, v) -> b.setQueryParameter(k, v) }
        return Net.get(b.build().toString(), cfg.header, cfg.timeout.toLong().coerceIn(5, 30))
    }

    override fun home() = VodJson.page(call("ac" to "list"))
    override fun category(tid: String, page: Int) = VodJson.page(call("ac" to "detail", "t" to tid, "pg" to "$page"))
    override fun search(wd: String) = VodJson.page(call("ac" to "detail", "wd" to wd)).list
    override fun detail(id: String) = VodJson.page(call("ac" to "detail", "ids" to id)).list.firstOrNull()

    /** CMS 给的地址多数直接能播；不是 m3u8/mp4 的标记成需要解析 */
    override fun play(flag: String, id: String): PlayInfo {
        val direct = Regex("\\.(m3u8|mp4|flv|mkv)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(id)
        return PlayInfo(id, cfg.header, parse = !direct, jx = false)
    }
}

/**
 * 苹果 CMS XML 接口（type 0）：?ac=list / ?ac=videolist&wd= / ?ac=videolist&ids=
 * <rss><list><video><id/><name/><pic/><note/><dl><dd flag="lzm3u8">第1集$地址#…</dd></dl></video></list><class><ty id="1">电影</ty></class></rss>
 */
class CmsXmlSite(private val cfg: SiteCfg) : SiteApi {
    private val json = CmsSite(cfg)

    private fun call(vararg q: Pair<String, String>): VodPage {
        val b = cfg.api.toHttpUrl().newBuilder()
        q.forEach { (k, v) -> b.setQueryParameter(k, v) }
        return parse(Net.get(b.build().toString(), cfg.header, cfg.timeout.toLong().coerceIn(5, 30)))
    }

    override fun home() = call("ac" to "list")
    override fun category(tid: String, page: Int) = call("ac" to "videolist", "t" to tid, "pg" to "$page")
    override fun search(wd: String) = call("ac" to "videolist", "wd" to wd).list
    override fun detail(id: String) = call("ac" to "videolist", "ids" to id).list.firstOrNull()
    override fun play(flag: String, id: String) = json.play(flag, id)

    companion object {
        fun parse(xml: String): VodPage {
            val p = android.util.Xml.newPullParser()
            p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(java.io.StringReader(xml))
            val classes = ArrayList<VodClass>()
            val list = ArrayList<Vod>()
            var v: HashMap<String, String>? = null
            var flags = ArrayList<PlayFlag>()
            var tag = ""
            var ddFlag = ""
            var tyId = ""
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        tag = p.name
                        when (tag) {
                            "video" -> { v = HashMap(); flags = ArrayList() }
                            "dd" -> ddFlag = p.getAttributeValue(null, "flag").orEmpty()
                            "ty" -> tyId = p.getAttributeValue(null, "id").orEmpty()
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> {
                        val t = p.text.orEmpty().trim()
                        if (t.isNotEmpty()) when {
                            tag == "ty" -> classes += VodClass(tyId, t)
                            tag == "dd" && v != null -> {
                                val eps = t.split('#').mapIndexedNotNull { i, s ->
                                    if (s.isBlank()) null else s.indexOf('$').let { k ->
                                        if (k < 0) Episode("第 ${i + 1} 集", s) else Episode(s.substring(0, k), s.substring(k + 1))
                                    }
                                }
                                if (eps.isNotEmpty()) flags += PlayFlag(ddFlag.ifBlank { "播放" }, eps)
                            }
                            v != null -> v[tag] = (v[tag].orEmpty() + t)
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        if (p.name == "video" && v != null) {
                            val m = v
                            list += Vod(
                                id = m["id"].orEmpty(), name = m["name"].orEmpty(), pic = m["pic"].orEmpty(),
                                remarks = m["note"].orEmpty(), year = m["year"].orEmpty(), area = m["area"].orEmpty(),
                                typeName = m["type"].orEmpty(), director = m["director"].orEmpty(), actor = m["actor"].orEmpty(),
                                content = m["des"].orEmpty().replace(Regex("<[^>]+>"), "").trim(), flags = flags,
                            )
                            v = null
                        }
                        tag = ""
                    }
                }
                ev = p.next()
            }
            return VodPage(classes, list)
        }
    }
}

/**
 * 插件站点（type 3 + csp_xxx）：jar 里的 com.github.catvod.spider.Xxx。
 * 插件跑在 :spider 进程里，这里只是把调用转过去。
 */
class JarSite(private val jar: String, private val md5: String?, private val cfg: SiteCfg, private val vipFlags: List<String>) : SiteApi {
    private fun call(method: String, vararg args: String) =
        SpiderClient.call(jar, md5, cfg.key, cfg.api, cfg.ext, method, *args)

    /** 只加载 jar、初始化插件（插件的配置中心、本地代理要先有它） */
    fun wake() { call("isVideo", "") }

    override fun home() = VodJson.page(call("home"))
    override fun category(tid: String, page: Int) = VodJson.page(call("category", tid, "$page"))
    override fun search(wd: String): List<Vod> {
        // 有的插件（摸鱼儿）刚加载时异步做宿主验证，验证完之前每个站点只回一条「宿主验证未就绪，请稍候」：等一下再搜
        repeat(3) {
            val r = VodJson.page(call("search", wd)).list
            if (!notReady(r)) return r
            Thread.sleep(3500)
        }
        return emptyList()
    }

    private fun notReady(r: List<Vod>) = r.size == 1 && r[0].let { "${it.name}${it.remarks}" }.let { "未就绪" in it || "验证中" in it }
    override fun detail(id: String) = VodJson.page(call("detail", id)).list.firstOrNull()
    override fun play(flag: String, id: String) = VodJson.play(call("play", flag, id, *vipFlags.toTypedArray()), id)

    private val manual by lazy { runCatching { call("manual") == "true" }.getOrDefault(false) }
    override fun videoCheck(): ((String) -> Boolean)? =
        if (!manual) null else { url -> runCatching { call("isVideo", url) == "true" }.getOrDefault(false) }
}

object Sites {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, SiteApi>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * 拿到（必要时加载 jar、初始化插件）一个站点；不支持的类型抛异常，信息给界面显示。
     * 按站点加锁：一个插件初始化卡住，不会拖住别的站点。
     */
    fun get(ctx: Context, config: TvConfig, cfg: SiteCfg): SiteApi {
        val key = "${config.url}#${cfg.key}"
        cache[key]?.let { return it }
        return synchronized(locks.getOrPut(key) { Any() }) {
            cache.getOrPut(key) {
                when (cfg.kind) {
                    SiteKind.CMS -> CmsSite(cfg)
                    SiteKind.CMS_XML -> CmsXmlSite(cfg)
                    SiteKind.JAR -> {
                        val jarUrl = cfg.jar ?: config.spider ?: error("配置里没有 spider jar")
                        val md5 = if (cfg.jar == null) config.spiderMd5 else null
                        SpiderClient.bind(ctx)
                        JarSite(jarUrl, md5, cfg, config.flags)
                    }
                    else -> error("${cfg.kind.label} 类型的站点还没支持")
                }
            }
        }
    }

    fun clear() = cache.clear()
}
