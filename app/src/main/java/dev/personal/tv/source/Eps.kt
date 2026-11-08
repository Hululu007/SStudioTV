package dev.personal.tv.source

/**
 * 分集名字。电视剧大多是「第1集 / 01 / EP1」，按序号就行；
 * 综艺常常是「20241101期」「第3期上」「加更版」，一期拆成上下、还夹着加更，序号对不上，要按名字认。
 */
object Eps {
    private val QUALITY = Regex("(hd|bd|tc|ts|1080p?|720p?|4k|高清|超清|标清|蓝光|国语|粤语|中字|中文字幕|无删减|未删减|已完结|完结|大结局|最终[集回话]?|end|final)")
    private val PUNCT = Regex("[\\s_\\-·•.,，。:：|/\\\\()（）\\[\\]【】《》<>「」\"'~]")
    private val DATE = Regex("(20\\d{2})[-./年]?(\\d{1,2})[-./月]?(\\d{1,2})日?")
    private val LEADING_ZERO = Regex("(?<!\\d)0+(?=\\d)")
    private val NUMBER = Regex("^\\d{1,4}$")

    /**
     * 用来比对的键：不同站点的同一集应该算出一样的键。
     * 「第01期(上)」「1期 上」→ 1上；「2024-11-01 期」「第20241101期」→ d20241101；「第1集 HD」「EP01」→ 1
     */
    fun key(name: String, title: String = ""): String {
        var s = name.lowercase()
        if (title.length >= 2) s = s.replace(title.lowercase(), "")
        DATE.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            val rest = PUNCT.replace(QUALITY.replace(s.removeRange(m.range), ""), "")
                .replace("第", "").replace("期", "")
            return "d$y${mo.padStart(2, '0')}${d.padStart(2, '0')}$rest"
        }
        s = PUNCT.replace(QUALITY.replace(s, ""), "")
        s = s.replace("第", "").replace("集", "").replace("期", "").replace("话", "").replace("ep", "")
        return LEADING_ZERO.replace(s, "").ifEmpty { name.trim() }
    }

    /** 这一集的名字其实就是个序号 */
    fun isNumber(name: String, title: String = "") = NUMBER.matches(key(name, title))

    /**
     * 这条线路按序号显示（1、2、3）还是按名字显示。
     * 电视剧的线路里常夹着「陪看1」「第01集彩蛋」，少数几个不算；按日期 / 期数分的、大半不是序号的、综艺里有名字的，按名字。
     */
    fun numeric(eps: List<Episode>, title: String = "", variety: Boolean = false): Boolean {
        if (eps.isEmpty()) return true
        if (dated(eps)) return false
        val odd = eps.count { !isNumber(it.name, title) }
        return odd * 2 <= eps.size && !(variety && odd > 0)
    }

    /** 第 n 集在线路里是第几个（0 开始）：先按名字里的数字找（线路里夹着花絮时序号会错位），找不到按位置 */
    fun indexOfNumber(eps: List<Episode>, n: Int, title: String = ""): Int {
        val i = eps.indexOfFirst { key(it.name, title) == n.toString() }
        return if (i >= 0) i else if (n in 1..eps.size) n - 1 else -1
    }

    /** 线路按日期 / 期数分（综艺）：这种线路里找不到同名的，不能按序号凑 */
    fun dated(eps: List<Episode>) = eps.any { DATE.containsMatchIn(it.name) || "期" in it.name }

    /** 在线路里找同一集，找不到返回 -1 */
    fun indexOf(eps: List<Episode>, name: String, title: String = ""): Int {
        val k = key(name, title)
        return eps.indexOfFirst { key(it.name, title) == k }
    }

    /**
     * 选集按钮上显示的字：去掉片名和清晰度这类噪音，日期缩成「11.01」。
     * 「奔跑吧 第20241101期(上)」→「11.01 上」
     */
    fun label(name: String, title: String = ""): String {
        var s = name.trim()
        if (title.length >= 2) s = s.replace(title, "").trim()
        DATE.find(s)?.let { m ->
            val (_, mo, d) = m.destructured
            val rest = s.removeRange(m.range).replace("第", "").replace("期", "")
                .trim(' ', '-', '_', '·', '(', ')', '（', '）', '[', ']', '【', '】')
            return "${mo.padStart(2, '0')}.${d.padStart(2, '0')}" + if (rest.isNotEmpty()) " $rest" else ""
        }
        // 按名字显示的线路里，「第1期」「0317」原样留着；只有「01」这种纯短数字才去掉前导 0
        if (s.length < 4 && s.all { it.isDigit() }) return key(name, title)
        return s.ifEmpty { name.trim() }
    }

    /**
     * 「第 3 集」或者「11.01 上」。[numericLine]：整条线路是不是都是序号，不知道（null）就只看这一集的名字。
     */
    fun text(name: String?, idx: Int, title: String = "", numericLine: Boolean? = null): String =
        if (name == null || (numericLine ?: isNumber(name, title))) "第 $idx 集" else label(name, title)

    /** 有的站把综艺按新到旧排：都带日期且是倒序的，翻过来 */
    fun ascending(eps: List<Episode>): List<Episode> {
        if (eps.size < 2) return eps
        val dates = eps.map { e -> DATE.find(e.name)?.destructured?.let { (y, m, d) -> "$y${m.padStart(2, '0')}${d.padStart(2, '0')}" } }
        if (dates.any { it == null }) return eps
        return if (dates.first()!! > dates.last()!!) eps.reversed() else eps
    }
}
