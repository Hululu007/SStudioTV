package dev.personal.tv.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.personal.tv.source.Net
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 豆瓣移动版接口（m.douban.com/rexxar），不用登录。全部是阻塞调用，放 IO 线程。
 * 要手机 UA + Referer，否则 403；海报图床 doubanio 不带 Referer 返回 418（见 App 里 Coil 的拦截器）。
 */
object Douban {
    const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    const val REFERER = "https://m.douban.com/"
    private const val API = "https://m.douban.com/rexxar/api/v2"

    private val client = Net.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** 接口结果的磁盘缓存：filesDir/douban/<sha1>，文件修改时间就是拉取时间 */
    private var dir: File? = null
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val bg = Executors.newFixedThreadPool(2) { r -> Thread(r, "douban-bg").apply { isDaemon = true } }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "douban").apply { mkdirs() }
        // 清掉一个月没用过的
        bg.execute { dir?.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30 * DAY }?.forEach { it.delete() } }
    }

    /**
     * 拉接口。缓存没过 [ttl] 直接用；过期了但 [stale] = true：先返回旧的，后台再刷新（界面不用等网络）；
     * 网络失败时有旧的也用旧的。
     */
    private fun get(url: String, q: Map<String, String> = emptyMap(), ttl: Long = 0, stale: Boolean = false): JsonElement {
        val b = url.toHttpUrl().newBuilder()
        q.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val full = b.build()
        val f = dir?.let { File(it, sha1(full.toString())) }
        if (f != null && ttl > 0 && f.exists()) {
            val cached = runCatching { JsonParser.parseString(f.readText()) }.getOrNull()
            if (cached != null) {
                val age = System.currentTimeMillis() - f.lastModified()
                if (age < ttl) return cached
                if (stale) {
                    if (refreshing.add(f.name)) bg.execute { runCatching { fetch(full.toString(), f) }; refreshing.remove(f.name) }
                    return cached
                }
                return runCatching { fetch(full.toString(), f) }.getOrElse { cached }
            }
        }
        return fetch(full.toString(), if (ttl > 0) f else null)
    }

    /** 豆瓣返回 403 / 429（请求太多被限了）后先停 2 分钟，这期间有缓存用缓存，别越搜越被封 */
    @Volatile private var blockedUntil = 0L

    private fun fetch(url: String, f: File?): JsonElement {
        if (System.currentTimeMillis() < blockedUntil) error("豆瓣请求太多，歇一会儿")
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER).build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 403 || r.code == 429) blockedUntil = System.currentTimeMillis() + 2 * 60_000
            if (!r.isSuccessful) error("豆瓣 HTTP ${r.code}")
            val json = JsonParser.parseString(body)
            if (f != null) runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(body)
                tmp.renameTo(f)
            }
            return json
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 最近热门：type = tv / movie */
    fun recentHot(type: String, limit: Int = 30): List<Title> =
        get("$API/subject/recent_hot/$type", mapOf("start" to "0", "limit" to "$limit"), ttl = 10 * MIN).asJsonObject.arr("items").mapNotNull { item(it.obj(), type) }

    /**
     * 选片：tags 是逗号分隔的筛选（「悬疑,华语,2020年代」），sort：U 近期热度 / T 综合 / S 高分 / R 首播时间
     */
    fun recommend(type: String, tags: List<String>, sort: String, start: Int, count: Int, minScore: Float = 0f, cache: Boolean = true): List<Title> {
        val q = mutableMapOf("start" to "$start", "count" to "$count", "sort" to sort, "tags" to tags.joinToString(","))
        if (minScore > 0f) q["score_range"] = "$minScore,10"
        val ttl = if (cache) 6 * HOUR else 0
        return get("$API/$type/recommend", q, ttl, stale = true).asJsonObject.arr("items")
            .mapNotNull { it.obj() }.filter { it.str("type") == null || it.str("type") == "tv" || it.str("type") == "movie" }
            .mapNotNull { item(it, type) }
    }

    /** 喜欢这部的也喜欢 */
    fun recommendations(t: Title): List<Title> =
        (get("$API/${t.dtype}/${t.id}/recommendations", ttl = 7 * DAY, stale = true) as? JsonArray).orEmpty().mapNotNull { item(it.obj(), t.dtype) }

    /** 中文片名联想（不认拼音首字母）。type 一律是 movie，有 episode 的是剧集 */
    fun suggest(q: String): List<Title> =
        (get("https://movie.douban.com/j/subject_suggest", mapOf("q" to q), ttl = DAY) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            if (o.str("type") != "movie") return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val tv = !o.str("episode").isNullOrBlank()
            Title(
                id = id, name = o.str("title").orEmpty(), year = o.str("year")?.take(4)?.toIntOrNull() ?: 0,
                region = "", kind = if (tv) Kind.SERIES else Kind.MOVIE, genres = emptyList(), score = null,
                poster = o.str("img")?.replace("/s_ratio_poster/", "/m_ratio_poster/"),
                dtype = if (tv) "tv" else "movie", origName = o.str("sub_title").orEmpty(),
            )
        }

    private val GENRES = setOf(
        "剧情", "喜剧", "动作", "爱情", "科幻", "动画", "悬疑", "惊悚", "恐怖", "犯罪", "同性", "音乐", "歌舞", "传记", "历史", "战争",
        "西部", "奇幻", "冒险", "灾难", "武侠", "情色", "纪录片", "短片", "家庭", "儿童", "古装", "运动", "黑色电影", "真人秀", "脱口秀", "戏曲",
    )
    private val PIC = Regex("/public/(p\\d+\\.\\w+)")

    /**
     * 全站搜索：中文、英文原名都认，比联想全（联想只给三五条，英文常常搜不到）。
     * card_subtitle 是「地区 / 类型 / 导演 / 主演」，没类型的条目第二段是演员，按类型表过滤。
     */
    fun search(q: String): List<Title> =
        get("$API/search/movie", mapOf("q" to q, "count" to "20"), ttl = DAY).asJsonObject.arr("items").mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val type = o.str("target_type")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
            val t = o.obj("target") ?: return@mapNotNull null
            val id = t.str("id") ?: return@mapNotNull null
            val name = t.str("title")?.trim()?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
            val parts = t.str("card_subtitle").orEmpty().split(" / ").map { it.trim() }
            val genres = parts.getOrNull(1)?.split(" ")?.filter { it in GENRES }.orEmpty()
            val rest = if (genres.isEmpty()) parts.drop(1) else parts.drop(2)
            val rating = t.obj("rating")
            val count = rating?.int("count") ?: 0
            Title(
                id = id, name = name, year = t.str("year")?.take(4)?.toIntOrNull() ?: 0,
                region = parts.getOrNull(0)?.split(" ")?.take(2)?.joinToString(" ").orEmpty(),
                kind = kindOf(type, genres, null), genres = genres,
                score = rating?.float("value")?.takeIf { it > 0f && count > 0 },
                // 搜索给的是带签名的缩略图地址，换成和联想一样的海报地址
                poster = t.str("cover_url")?.let { PIC.find(it) }?.let { "https://img3.doubanio.com/view/photo/m_ratio_poster/public/${it.groupValues[1]}" },
                dtype = type, ratingCount = count,
                director = rest.getOrNull(0).orEmpty().split(" ").filter { it.isNotBlank() }.take(2).joinToString(" / "),
                cast = rest.getOrNull(1).orEmpty().split(" ").filter { it.isNotBlank() }.take(4),
            )
        }

    /** 详情。/movie/{id} 对剧集会 302 到 /tv/{id}，所以类型猜错也没关系 */
    fun detail(id: String, dtype: String): Title {
        val o = get("$API/$dtype/$id", ttl = 3 * DAY, stale = true).asJsonObject
        val type = o.str("type") ?: dtype
        val genres = o.arr("genres").mapNotNull { it.s() }
        val rating = o.obj("rating")
        val count = rating?.int("count") ?: 0
        val score = rating?.float("value")?.takeIf { it > 0f && count > 0 }
        val total = o.int("episodes_count") ?: 0
        val info = o.str("episodes_info").orEmpty()
        val aired = Regex("更新至\\s*(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()
        val intro = o.str("intro").orEmpty().trim()
        val kind = kindOf(type, genres, o.bool("is_show"))
        return Title(
            id = id,
            name = o.str("title").orEmpty(),
            year = o.str("year")?.take(4)?.toIntOrNull() ?: 0,
            region = o.arr("countries").mapNotNull { it.s() }.take(2).joinToString(" "),
            kind = kind,
            genres = genres,
            score = score,
            oneLiner = intro.substringBefore('\n').take(60),
            poster = o.obj("pic")?.str("large") ?: o.obj("pic")?.str("normal"),
            dtype = type,
            origName = o.str("original_title").orEmpty().takeIf { it != o.str("title") }.orEmpty(),
            ratingCount = count,
            desc = intro.replace(Regex("\\s*\\n\\s*"), " "),
            director = o.arr("directors").mapNotNull { it.obj()?.str("name") }.take(2).joinToString(" / "),
            cast = o.arr("actors").mapNotNull { it.obj()?.str("name") }.take(4),
            totalEps = if (type == "tv") total.coerceAtLeast(aired ?: 0) else 1,
            airedEps = if (type == "tv") (aired ?: total) else 1,
            schedule = if (aired != null && total > aired) "更新中" else null,
            minutes = o.arr("durations").firstNotNullOfOrNull { it.s()?.let { d -> Regex("(\\d+)").find(d)?.value?.toIntOrNull() } } ?: 0,
            full = true,
        )
    }

    /** 想看 / 在看 / 看过人数、星级分布、「好于 x% 某类片」 */
    fun rating(t: Title): Heat {
        val o = get("$API/${t.dtype}/${t.id}/rating", ttl = DAY, stale = true).asJsonObject
        val stats = o.arr("stats").mapNotNull { runCatching { it.asFloat }.getOrNull() }
        val rank = o.arr("type_ranks").mapNotNull { it.obj() }.maxByOrNull { it.float("rank") ?: 0f }
        val better = rank?.let { r ->
            val pct = ((r.float("rank") ?: 0f) * 100).toInt()
            if (pct > 0) "$pct% ${r.str("type").orEmpty()}" else null
        }
        return Heat(o.int("wish_count") ?: 0, o.int("doing_count") ?: 0, o.int("done_count") ?: 0, stats, better)
    }

    /** 列表条目：card_subtitle 是「2026 / 中国大陆 / 剧情 悬疑 / 导演 / 主演」 */
    private fun item(o: JsonObject?, type: String): Title? {
        o ?: return null
        val id = o.str("id") ?: return null
        val name = o.str("title") ?: return null
        val parts = o.str("card_subtitle").orEmpty().split(" / ").map { it.trim() }
        val year = o.str("year")?.take(4)?.toIntOrNull() ?: parts.getOrNull(0)?.take(4)?.toIntOrNull() ?: 0
        val genres = parts.getOrNull(2)?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val t = o.str("type") ?: type
        val rating = o.obj("rating")
        val count = rating?.int("count") ?: 0
        val info = o.str("episodes_info").orEmpty()
        val aired = Regex("更新至\\s*(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()
        val all = Regex("(\\d+)集全").find(info)?.groupValues?.get(1)?.toIntOrNull()
        return Title(
            id = id, name = name, year = year,
            region = parts.getOrNull(1)?.split(" ")?.take(2)?.joinToString(" ").orEmpty(),
            kind = kindOf(t, genres, null),
            genres = genres,
            score = rating?.float("value")?.takeIf { it > 0f && count > 0 },
            poster = o.obj("pic")?.let { it.str("large") ?: it.str("normal") },
            dtype = t,
            ratingCount = count,
            director = parts.getOrNull(3).orEmpty().split(" ").take(2).joinToString(" / "),
            cast = parts.getOrNull(4).orEmpty().split(" ").filter { it.isNotBlank() }.take(4),
            totalEps = all ?: aired ?: 1,
            airedEps = aired ?: all ?: 1,
            schedule = if (aired != null) "更新中" else null,
        )
    }

    private fun kindOf(type: String, genres: List<String>, isShow: Boolean?): Kind = when {
        "动画" in genres -> Kind.ANIME
        "纪录片" in genres -> Kind.DOC
        type == "movie" -> Kind.MOVIE
        isShow == true || "真人秀" in genres || "脱口秀" in genres -> Kind.VARIETY
        else -> Kind.SERIES
    }

    // ---------------------------------------------------------------- json 小工具

    private fun JsonElement?.obj(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null
    private fun JsonElement?.s(): String? = if (this != null && isJsonPrimitive) asString else null
    private fun JsonObject.obj(k: String): JsonObject? = get(k).obj()
    private fun JsonObject.str(k: String): String? = get(k).s()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.int(k: String): Int? = runCatching { get(k)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()
    private fun JsonObject.float(k: String): Float? = runCatching { get(k)?.takeIf { it.isJsonPrimitive }?.asFloat }.getOrNull()
    private fun JsonObject.bool(k: String): Boolean? = runCatching { get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean }.getOrNull()
    private fun JsonObject.arr(k: String): List<JsonElement> = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
