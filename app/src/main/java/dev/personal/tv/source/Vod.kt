package dev.personal.tv.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/** CMS 接口和插件返回的是同一种 JSON：{"class":[...], "list":[{vod_id, vod_name, ...}]} */
data class VodClass(val id: String, val name: String)

data class Episode(val name: String, val url: String)

/** 一个播放来源（flag），比如 lzm3u8、bfzym3u8 */
data class PlayFlag(val name: String, val episodes: List<Episode>)

data class Vod(
    val id: String,
    val name: String,
    val pic: String,
    val remarks: String,
    val year: String,
    val area: String,
    val typeName: String,
    val director: String,
    val actor: String,
    val content: String,
    val flags: List<PlayFlag>,
    /** 影视仓的 vod_tag：「folder」是文件夹（B站个人中心、媒体库、网盘分享的目录），点开是下一层列表，不是详情 */
    val tag: String = "",
    /** 海报宽高比（宽 / 高），插件用 style 指定；null = 默认竖版 */
    val ratio: Float? = null,
) {
    val folder get() = tag == "folder"
}

/** [ratio]：整页海报的宽高比（插件给的 style），null = 默认竖版 2:3 */
data class VodPage(val classes: List<VodClass>, val list: List<Vod>, val ratio: Float? = null)

/** 播放解析结果。parse = true 表示 url 还是网页，需要再嗅探/解析 */
data class PlayInfo(val url: String, val headers: Map<String, String>, val parse: Boolean, val jx: Boolean) {
    /** 探测出来的格式（hls / dash / mp4 / html…），地址没有扩展名时播放器靠它选解码方式 */
    var format: String? = null
}

/** 插件自己说的失败原因（要登录网盘、触发验证、资源失效…），原样给人看 */
class PluginError(msg: String) : Exception(msg)

object VodJson {

    fun page(text: String): VodPage {
        val o = root(text) ?: return VodPage(emptyList(), emptyList())
        val classes = o.arr("class").mapNotNull { it.obj() }.map {
            VodClass(it.str("type_id").orEmpty(), it.str("type_name").orEmpty())
        }
        return VodPage(classes, o.arr("list").mapNotNull { it.obj() }.map(::vod), style(o))
    }

    /**
     * 影视仓的 style：{"type":"rect","ratio":1.33} 横版、{"type":"oval"} 圆形、{"type":"list"} 列表。ratio 是宽 / 高。
     * 插件的「海报样式」设置（横版海报）就是靠它。
     */
    private fun style(o: JsonObject): Float? {
        val s = o.get("style")?.obj() ?: return null
        return when (s.str("type")) {
            "rect" -> runCatching { s.get("ratio")?.asFloat }.getOrNull()?.takeIf { it in 0.3f..3f } ?: 0.75f
            "oval" -> 1f
            "list" -> 1.6f
            else -> null
        }
    }

    /**
     * 插件 playerContent 的结果。没给地址时：parse = 1 且原 id 本身是网页地址，交给解析；
     * 否则是插件在报错（{"msg": "还没有配置夸克 Cookie…", "url": ""}），把 msg 抛出去给界面显示——
     * 以前拿集的 id（base64、「a|||b」之类）当地址去播，只会得到一句看不懂的「播放失败」。
     */
    fun play(text: String, fallbackUrl: String): PlayInfo {
        val o = root(text) ?: return PlayInfo(fallbackUrl, emptyMap(), parse = true, jx = false)
        val u = o.get("url")
        // 多清晰度时 url 是 ["1080P", "http://…", "720P", "http://…"]
        val url = when {
            u == null || u.isJsonNull -> ""
            u.isJsonArray -> u.asJsonArray.let { a -> if (a.size() >= 2) a[1].asString else a.firstOrNull()?.asString.orEmpty() }
            u.isJsonPrimitive -> u.asString
            else -> ""
        }.trim()
        val parse = o.int("parse", 0) == 1
        if (url.isEmpty() && !(parse && fallbackUrl.startsWith("http"))) {
            val msg = (o.str("msg") ?: o.str("message") ?: o.str("error"))?.trim()?.takeIf { it.isNotEmpty() }
            throw PluginError(msg ?: "插件没给播放地址")
        }
        return PlayInfo(
            url = url.ifBlank { fallbackUrl },
            headers = o.map("header").ifEmpty { o.map("headers") },
            parse = parse,
            jx = o.int("jx", 0) == 1,
        )
    }

    private fun root(text: String): JsonObject? = runCatching {
        JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }).asJsonObject
    }.getOrNull()

    private fun vod(o: JsonObject): Vod {
        val froms = o.str("vod_play_from").orEmpty().split("$$$")
        val urls = o.str("vod_play_url").orEmpty().split("$$$")
        val flags = froms.zip(urls).mapNotNull { (from, list) ->
            val eps = list.split("#").mapIndexedNotNull { i, s ->
                if (s.isBlank()) return@mapIndexedNotNull null
                val idx = s.indexOf('$')
                if (idx < 0) Episode("第 ${i + 1} 集", s) else Episode(s.substring(0, idx), s.substring(idx + 1))
            }
            if (from.isBlank() || eps.isEmpty()) null else PlayFlag(from, eps)
        }
        return Vod(
            id = o.str("vod_id").orEmpty(),
            name = o.str("vod_name").orEmpty(),
            pic = o.str("vod_pic").orEmpty(),
            remarks = o.str("vod_remarks").orEmpty(),
            year = o.str("vod_year").orEmpty(),
            area = o.str("vod_area").orEmpty(),
            typeName = o.str("type_name").orEmpty(),
            director = o.str("vod_director").orEmpty(),
            actor = o.str("vod_actor").orEmpty(),
            content = o.str("vod_content").orEmpty().replace(Regex("<[^>]+>"), "").trim(),
            flags = flags,
            tag = o.str("vod_tag").orEmpty(),
            ratio = style(o),
        )
    }
}
