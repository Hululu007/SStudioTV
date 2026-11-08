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
)

data class VodPage(val classes: List<VodClass>, val list: List<Vod>)

/** 播放解析结果。parse = true 表示 url 还是网页，需要再嗅探/解析 */
data class PlayInfo(val url: String, val headers: Map<String, String>, val parse: Boolean, val jx: Boolean) {
    /** 探测出来的格式（hls / dash / mp4 / html…），地址没有扩展名时播放器靠它选解码方式 */
    var format: String? = null
}

object VodJson {

    fun page(text: String): VodPage {
        val o = root(text) ?: return VodPage(emptyList(), emptyList())
        val classes = o.arr("class").mapNotNull { it.obj() }.map {
            VodClass(it.str("type_id").orEmpty(), it.str("type_name").orEmpty())
        }
        return VodPage(classes, o.arr("list").mapNotNull { it.obj() }.map(::vod))
    }

    fun play(text: String, fallbackUrl: String): PlayInfo {
        val o = root(text) ?: return PlayInfo(fallbackUrl, emptyMap(), parse = true, jx = false)
        val u = o.get("url")
        // 多清晰度时 url 是 ["1080P", "http://…", "720P", "http://…"]
        val url = when {
            u == null || u.isJsonNull -> fallbackUrl
            u.isJsonArray -> u.asJsonArray.let { a -> if (a.size() >= 2) a[1].asString else a.firstOrNull()?.asString.orEmpty() }
            else -> u.asString
        }
        return PlayInfo(
            url = url.ifBlank { fallbackUrl },
            headers = o.map("header").ifEmpty { o.map("headers") },
            parse = o.int("parse", 0) == 1,
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
        )
    }
}
