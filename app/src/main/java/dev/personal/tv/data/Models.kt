package dev.personal.tv.data

import androidx.compose.ui.graphics.Color

enum class Kind(val label: String) {
    SERIES("剧集"), MOVIE("电影"), VARIETY("综艺"), ANIME("动漫"), DOC("纪录片"),
}

/**
 * 一部片在本 App 里的统一条目。字段来自豆瓣（id 就是豆瓣条目 id），线路来自片源聚合（[dev.personal.tv.source.Lines]）。
 * 列表接口只给名字、年份、评分、海报，[full] = true 表示已经用详情接口补全。
 */
data class Title(
    val id: String,
    val name: String,
    val year: Int,
    val region: String,
    val kind: Kind,
    val genres: List<String>,
    /** null = 豆瓣暂无评分 */
    val score: Float?,
    val oneLiner: String = "",
    /** 豆瓣海报地址（doubanio，要带 Referer） */
    val poster: String? = null,
    /** 豆瓣的大类：tv / movie，决定详情接口和是否分集 */
    val dtype: String = if (kind == Kind.MOVIE) "movie" else "tv",
    val origName: String = "",
    val ratingCount: Int = 0,
    val desc: String = oneLiner,
    val director: String = "",
    val cast: List<String> = emptyList(),
    /** 0 = 豆瓣没写总集数 */
    val totalEps: Int = 1,
    val airedEps: Int = totalEps,
    val schedule: String? = null,
    val minutes: Int = 0,
    val full: Boolean = false,
) {
    val isEpisodic get() = dtype == "tv"
    val isVariety get() = kind == Kind.VARIETY
    val scoreText get() = score?.let { "%.1f".format(it) }
    /** 没海报时用的渐变，按 id 固定，同一部片每次一样 */
    val art: ArtStyle get() = ArtStyle.entries[(id.hashCode() and 0x7fffffff) % ArtStyle.entries.size]
    /** 拼音首字母，用于遥控器搜索 */
    val initials: String get() = Pinyin.initials(name)
    /** 已找到的线路数；-1 = 还没找过 / 正在找 */
    val lines: Int get() = dev.personal.tv.source.Lines.count(id)

    /** 「2025 · 剧集 · 全 16 集」这种一行概要 */
    val summary: String
        get() = buildString {
            if (year > 0) append(year).append(" · ")
            append(kind.label)
            if (isEpisodic) {
                if (totalEps > 1 || airedEps > 1) {
                    append(" · ")
                    append(if (airedEps < totalEps) "更新至 $airedEps 集" else "全 $totalEps 集")
                }
            } else if (minutes > 0) {
                append(" · ").append(minutes).append(" 分钟")
            }
        }

    /** 还在连载（豆瓣写着更新中，或已出的集数没到总集数） */
    val airing: Boolean get() = isEpisodic && (schedule != null || airedEps < totalEps)

    /**
     * 把另一份数据（列表接口、刚拉的详情）里的集数进度并进来：已出集数只往前走；
     * 对方写着「N 集全」就按完结算，去掉「更新中」。对方没带集数信息时原样返回。
     */
    fun withEpsFrom(n: Title): Title {
        if (!isEpisodic || n.totalEps <= 1 && n.airedEps <= 1) return this
        val aired = maxOf(airedEps, n.airedEps)
        val ended = n.schedule == null && n.airedEps >= n.totalEps
        val total = if (ended) maxOf(n.totalEps, aired) else maxOf(totalEps, n.totalEps, aired)
        return copy(airedEps = aired, totalEps = total, schedule = if (aired < total) schedule ?: n.schedule ?: "更新中" else null)
    }

    /** 「更新至 12 集 / 共 24 集」「全 16 集」「118 分钟」，没数据时为空 */
    val epsText: String
        get() = when {
            !isEpisodic -> if (minutes > 0) "$minutes 分钟" else ""
            totalEps <= 1 && airedEps <= 1 -> ""
            airedEps < totalEps -> "更新至 $airedEps 集 / 共 $totalEps 集"
            else -> "全 $totalEps 集"
        }
}

data class Progress(
    val titleId: String,
    val ep: Int,
    val positionSec: Int,
    val durationSec: Int,
    /** 毫秒时间戳 */
    val lastWatched: Long,
    /** 上次能播的线路（站点|来源），下次优先用它 */
    val line: String? = null,
    /** 这条线路是用户手动选的：下次排在「优先站点」前面 */
    val pinned: Boolean = false,
    /** 这一集在线路里的名字（综艺换线路时按名字找同一期）；老记录没有 */
    val epName: String? = null,
    /**
     * [ep] 是什么：true = 当时那条线路（[line]）里的位置（按名字分期的综艺）；false = 名字里的集数。
     * 0.7.3 之前的记录没有（Gson 读出来是 null），按 [epName] 像不像序号猜。
     */
    val named: Boolean? = null,
    /** 存进度时算好的下一集名字（看完以后首页、详情页按它接着放，不用等线路搜完）；没有下一集 = null */
    val nextName: String? = null,
) {
    /** [ep] 是线路里的位置，不是集数 */
    fun isNamed(title: String) = named ?: (epName != null && !dev.personal.tv.source.Eps.isNumber(epName, title))

    /** 名字里的集数；按名字分期的没有 */
    fun episodeNo(title: String): Int? = if (!isNamed(title)) ep else epName?.let { dev.personal.tv.source.Eps.number(it, title) }

    /** 「第 3 集」或者「11.01 上」 */
    fun epText(title: String) = dev.personal.tv.source.Eps.text(epName, ep, title, named?.not())

    val fraction get() = if (durationSec == 0) 0f else positionSec.toFloat() / durationSec
    val finishedEp get() = durationSec > 0 && positionSec >= durationSec - 60
    val remainingMin get() = ((durationSec - positionSec) / 60).coerceAtLeast(1)
}

data class Glow(val x: Float, val y: Float, val r: Float, val color: Color)

enum class ArtStyle(val top: Color, val mid: Color, val bottom: Color, val sil: Color, val glows: List<Glow>) {
    FOG(c(0x36545e), c(0x18303a), c(0x0a1418), Color(4, 8, 10, 140), listOf(Glow(.72f, .36f, .22f, Color(255, 196, 128, 190)))),
    NIGHT(c(0x26295a), c(0x11132c), c(0x07080f), Color(2, 3, 8, 178), listOf(Glow(.28f, .24f, .07f, Color(241, 234, 217, 240)))),
    SNOW(c(0xb9c7d6), c(0x7b90a6), c(0x3c4d60), Color(40, 55, 72, 140), listOf(Glow(.6f, .2f, .5f, Color(255, 255, 255, 140)))),
    LETTER(c(0x8a5632), c(0x4a2814), c(0x1e0f06), Color(30, 14, 5, 153), listOf(Glow(.35f, .4f, .3f, Color(248, 205, 140, 204)))),
    WHALE(c(0x167085), c(0x0b3b48), c(0x04161b), Color(2, 14, 18, 166), listOf(Glow(.5f, 0f, .5f, Color(140, 230, 240, 115)))),
    MOUNT(c(0x6f9a7a), c(0x36604a), c(0x0e1f15), Color(8, 22, 14, 178), listOf(Glow(.64f, .44f, .14f, Color(247, 217, 143, 240)))),
    WIT(c(0x5c1616), c(0x2a0909), c(0x110404), Color(10, 2, 2, 190), listOf(Glow(.7f, .3f, .4f, Color(255, 80, 60, 115)))),
    SUMMER(c(0xf6a774), c(0xc75d6f), c(0x3a1830), Color(58, 20, 40, 140), listOf(Glow(.5f, .5f, .2f, Color(255, 224, 163, 240)))),
    EDGE(
        c(0x2a0f4a), c(0x1a0a30), c(0x12061f), Color(6, 2, 14, 204),
        listOf(Glow(.25f, .35f, .35f, Color(255, 61, 154, 153)), Glow(.78f, .3f, .35f, Color(47, 211, 255, 128))),
    ),
    LIGHT(c(0x15365a), c(0x0a1c30), c(0x050c14), Color(2, 8, 14, 190), listOf(Glow(.3f, .46f, .08f, Color(255, 244, 200, 240)))),
    HOME(c(0xd6b06a), c(0x9a6c34), c(0x4a3015), Color(50, 30, 10, 140), listOf(Glow(.5f, .3f, .4f, Color(255, 236, 190, 153)))),
    ISLAND(c(0x3b7484), c(0x1a3f4a), c(0x0b1d22), Color(6, 20, 24, 178), listOf(Glow(.4f, .3f, .3f, Color(143, 211, 199, 140)))),
}

private fun c(rgb: Int) = Color(0xFF000000 or rgb.toLong())

// ---------- 「值不值得看」 ----------

/** 豆瓣的人数统计和星级分布，只有数字 */
data class Heat(
    val wish: Int,
    val doing: Int,
    val done: Int,
    /** 1 星到 5 星的比例 */
    val stars: List<Float>,
    /** 「好于 99% 犯罪片」 */
    val betterThan: String?,
)

enum class SimilarSource(val label: String) {
    ALSO_LIKED("喜欢这部的也喜欢"), DIRECTOR("同导演"), CAST("同主演"), TAG("同标签"),
}
