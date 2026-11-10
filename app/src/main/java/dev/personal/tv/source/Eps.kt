package dev.personal.tv.source

/**
 * 一集名字拆出来的各个部分。
 * [date]：「20230317」或没年份的「0317」；[num]：「12」「12.5」「1-2」（合集）；[raw]：名字里原样的数字（判断「0317」是不是日期用）；
 * [part]：0 不分，1 上、2 中、9 下（「(一)」「part2」按数字）；[tag]：加更、花絮这类，空 = 正片；[rest]：剩下认不出的字（嘉宾名之类）。
 */
data class EpId(
    val date: String?,
    val num: String?,
    val raw: String?,
    val unit: Char?,
    val part: Int,
    val tag: String,
    val rest: String,
    val key: String,
) {
    /** 名字就是一个序号（「第05集」「EP5」「05 HD」）；「第3期」是综艺的期号，不算 */
    val plain get() = date == null && num != null && num.all { it.isDigit() } && unit != '期' && part == 0 && tag.isEmpty() && rest.isEmpty()
    /** 预告、花絮、彩蛋这类不是正片的 */
    val extra get() = tag in Eps.EXTRA
    /** 没年份的「0317」：4 位、像月日 */
    val mmdd: String? get() = raw?.takeIf { it.length == 4 && Eps.validMd(it.substring(0, 2).toInt(), it.substring(2).toInt()) }
}

/**
 * 分集名字。电视剧大多是「第1集 / 01 / EP1」，按序号就行；
 * 综艺常常是「20241101期」「第3期上」「加更版」，一期拆成上下、还夹着加更，序号对不上，要按名字认。
 *
 * 不再靠「删掉噪音词后整串比」：先把名字拆成日期、集号、上下、类型、其余几个部分（[parse]），
 * 比的时候先整键一样，再放宽到「同一天 / 同一号 + 同上下 + 同类型」（[indexOf]）。
 */
object Eps {
    private val QUALITY = Regex(
        "(?<![a-z0-9])(?:\\d{3,4}[pi]|1?[02468]bit|[248]k(?:hdr)?(?:\\d{2,3}(?:fps|帧)?)?|\\d{2,3}(?:fps|帧)|hdr10\\+?|hdr|dolby|uhd|fhd|hd|bd|tc|ts|web-?dl|hevc|[hx]\\.?26[45]|aac)(?![a-z])" +
            "|杜比视界|杜比|高清|超清|标清|蓝光|原画|高码率?|低码率?|(?:国语|粤语|英语|普通话|原声|双语)(?:版|中字)?|中英字幕|中文字幕|中字|英字|无删减|未删减|删减版|已完结|完结|大结局|最终[集回话章]?|全集" +
            "|(?<![a-z])(?:end|final)(?![a-z])",
    )
    /**
     * 网盘线路的分集是文件名：「[536.8MB]01_1080P.mp4【第1季（2022）24集全】」。
     * 文件大小、扩展名、「24集全 / 全24集」这种整季的信息、括号里单独的年份，都不是这一集的名字；不去掉的话「24集」会被当成集号。
     */
    private val FILE_NOISE = Regex(
        "\\d+(?:\\.\\d+)?\\s*[kmgt]i?b(?![a-z])" +
            "|\\.(?:mp4|mkv|ts|m3u8|flv|avi|rmvb|rm|mov|wmv|webm|m4v|mpe?g|iso)(?![a-z0-9])" +
            "|(?:全|共)\\s*\\d+\\s*[集话話期]|\\d+\\s*[集话話期]\\s*全|更新至\\s*\\d+\\s*[集话話期]?" +
            "|[（(\\[【]\\s*(?:19|20)\\d{2}\\s*[)）\\]】]" +
            // 「S01E01.2011.1080p」点号隔开的年份（后面跟着月.日的是日期，不动）
            "|(?<=[a-z0-9]\\.)(?<!(?<![a-z])(?:ep?|episode|vol|no)\\.)(?:19|20)\\d{2}(?=\\.)(?!\\.\\d{1,2}\\.\\d{1,2}(?!\\d))" +
            // 网盘重复上传加的时间戳「_1029195914」：9 位以上的数字不会是集号、日期
            "|(?<!\\d)\\d{9,}(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    /**
     * 压制组后缀「AAC-AIU.mp4」「H265-CHDWEB.mkv」：编码 / 清晰度后面、扩展名前面全大写的一截。要在转小写之前去掉。
     * 前面不是编码的不动（「Running.Man-EP05.mp4」「20261022-PLUS.mp4」）
     */
    private val RELEASE_GROUP = Regex(
        "(?<=(?i:aac|ac3|eac3|dts|flac|atmos|ddp?\\d\\.\\d|26[45]|hevc|avc|\\dp|-dl|rip|ray|bit))" +
            "-(?!(?:EP?\\d|PART|PT\\d|PLUS|LIVE|SP|END|CUT)(?![A-Z]))(?=[A-Z0-9]{2,12}\\.)[A-Z0-9]*[A-Z][A-Z0-9]*" +
            "(?=\\.(?:mp4|mkv|ts|flv|avi|mov|webm|m4v)(?![A-Za-z0-9]))",
    )
    /** 选集按钮上不用显示的清晰度、编码（「国语版」「普通话版」留着，同一集分版本时要靠它区分） */
    private val LABEL_NOISE = Regex(
        "(?<![a-z0-9])(?:\\d{3,4}[pi]|[248]k(?:hdr)?|1?[02468]bit|uhd|fhd|hd|bd|web-?dl|hevc|[hx]\\.?26[45]|aac|hdr10\\+?|hdr)(?![a-z])|杜比视界|杜比|高码率?|低码率?",
        RegexOption.IGNORE_CASE,
    )
    /** 网盘文件名开头的英文片名：「Empresses.in.the.Palace.E01」「Running.Man.S01E01」→ 只留集号 */
    private val EN_TITLE = Regex("^[\\s._\\[\\]-]*(?:[a-z]+[\\s._-]+){1,8}(?=(?:s\\d{1,2}[\\s._-]*)?e(?:p)?[\\s._-]*\\d{1,4}(?!\\d))", RegexOption.IGNORE_CASE)
    /** 英文片名里带着类型词（「Live EP3」「Plus E02」「Reaction EP1」）的不是片名，留着 */
    private fun stripEnTitle(s: String): String = EN_TITLE.replace(s) { m ->
        val low = m.value.lowercase()
        if (TAGS.any { it.second.containsMatchIn(low) } || WEAK_TAGS.any { it.second.containsMatchIn(low) }) m.value else ""
    }
    /** 「EP.01」「E_01」：集号前面的分隔符去掉，好认 */
    private val EP_SEP = Regex("(?<![a-z])(ep?|episode)[\\s._-]+(?=\\d)", RegexOption.IGNORE_CASE)
    /** 括号 / 分隔符清掉内容后留下的空壳：「[]」「【 （） 】」「_ .」 */
    private val EMPTY_BRACKETS = Regex("[\\[(（【《<「]\\s*[)）\\]】》>」]")
    private val EDGE = " -_·•.,，。:：;；|/\\~～"
    /** 选集按钮上最多显示的宽度（汉字算 2，字母数字算 1），超了截断加「…」，完整名字聚焦时在标题旁边显示 */
    private const val LABEL_MAX = 20

    /**
     * 去掉名字里的片名。有的站把片名拆开插符号防屏蔽：「1 4KHDR 【X - 小 - 城..良--方/ 高码】」，
     * 片名的字按顺序都在、中间隔着少量符号也算；在括号里的话整个括号去掉（括号里剩下的「X」「/」也是噪音）。
     */
    fun stripTitle(name: String, title: String): String {
        if (title.length < 2) return name
        var s = name.replace(title, " ", ignoreCase = true).replace(title.filterNot { it.isWhitespace() }, " ", ignoreCase = true)
        val cjk = title.filter { it in '\u4e00'..'\u9fff' }
        if (cjk.length < 2) return s
        val loose = synchronized(titleRes) {
            titleRes.getOrPut(cjk) { Regex(cjk.map { Regex.escape(it.toString()) }.joinToString("[^\\u4e00-\\u9fff\\d]{0,4}")) }
        }
        // 片名在括号里（「【H 后宫·甄嬛传】」）：去掉片名后剩下的「【H 】」是空壳
        var removed = BRACKET.findAll(name).any { it.value.contains(title, ignoreCase = true) }
        s = BRACKET.replace(s) { m -> if (loose.containsMatchIn(m.value)) { removed = true; " " } else m.value }
        // 更狠的打乱：「【H‑后‑H‑友‑Q】」「【H【后!＠会!＃友!＆其月】】」只留了片名里的两三个字，其余换成拼音首字母；
        // 括号里没有数字、有片名里至少两个字的整个去掉，去完剩下的「【H 】」这种空壳（只有一两个字母）也去掉。
        // 括号里是类型词、上下的不动（片名「幕后之王」的「【幕后花絮】」、「【兄弟团纯享】」「[SP]」）
        repeat(2) {
            s = BRACKET.replace(s) { m ->
                val c = m.groupValues[1]
                val low = c.lowercase()
                if (TAGS.any { it.second.containsMatchIn(low) } || PART.containsMatchIn(m.value)) return@replace m.value
                val hits = cjk.toSet().count { it in c }
                val shell = removed && c.none { it in '\u4e00'..'\u9fff' || it.isDigit() } && c.count { it.isLetter() } <= 2
                if (c.none { it.isDigit() } && hits >= 2 || shell) { removed = true; " " } else m.value
            }
        }
        return loose.replace(s, " ")
    }
    private val titleRes = HashMap<String, Regex>()
    private val BRACKET = Regex("[【\\[]([^【】\\[\\]]*)[】\\]]")

    private val SEASON = Regex("第[零一二两三四五六七八九十\\d]+季|(?<![a-z])s(?:eason)?\\s*\\d{1,2}(?![\\d])")
    private val SEASON_ANY = Regex(SEASON.pattern, RegexOption.IGNORE_CASE)
    private val DATE_FULL = Regex("第?(?<!\\d)(20\\d{2})(?:[-./年\\s]\\s*(\\d{1,2})\\s*[-./月]\\s*(\\d{1,2})\\s*[日号]?|(\\d{2})(\\d{2}))(?!\\d)\\s*期?")
    private val DATE_MD = Regex("(?<!\\d)(\\d{1,2})月(\\d{1,2})[日号]?\\s*期?")
    private val DATE_QI = Regex("(?<![\\d第])([01]\\d)([0-3]\\d)\\s*期")
    private val TAGS = listOf(
        "加更" to Regex("加更版?|加长版?|(?<![a-z])plus(?![a-z])|超前点映"),
        "抢鲜" to Regex("抢鲜版?|抢先版|会员版"),
        "沉浸" to Regex("沉浸版?|沉浸式"),
        "直播" to Regex("直播回放|直播版|直播全程"),
        "路透" to Regex("路透"),
        "先导" to Regex("先导片?|先行版?"),
        "预告" to Regex("预告片?|抢先看|看点|前瞻"),
        "花絮" to Regex("花絮|幕后|拍摄日记|制作特辑"),
        "彩蛋" to Regex("彩蛋"),
        "纯享" to Regex("纯享版?|直拍|舞台版?"),
        "陪看" to Regex("陪看版?|聊天室"),
        "精华" to Regex("精华版?|精编版?|集锦|回顾|(?<![a-z])cut(?![a-z])"),
        "特别" to Regex("特别篇|特别版|特辑|番外篇?|(?<![a-z])sp(?![a-z])"),
    )
    /**
     * 也常出现在正片副标题里的类型词（「第12集 直播事故」「第08集 海报风波」「第3期 直播带货大战」）：
     * 名字里写明了第几集 / 第几期的不算
     */
    private val WEAK_TAGS = listOf(
        "直播" to Regex("直播|(?<![a-z])live(?![a-z])"),
        "路透" to Regex("官宣|物料|海报|(?<![a-z])reaction(?![a-z])|解说|二创|混剪"),
    )
    private val EP_MARK = Regex("第\\s*[\\d零一二两三四五六七八九十百]+\\s*[集话話回期]|(?<![a-z])(?:ep?|episode)\\s*\\d")

    /** 自动下一集时跳过、数正片时不算的 */
    val EXTRA = setOf("预告", "花絮", "彩蛋", "纯享", "陪看", "精华", "直播", "路透")
    /** 同一期正片的另一个剪辑版本：看正片时自动下一集跳过 */
    val ALT = setOf("抢鲜", "沉浸")
    /** 选集分组：0 正片、1 加更 / 别的完整版本（抢鲜、沉浸、陪看）、2 纯享、花絮、预告这类片段。选集行按组显示，不混在一起 */
    val GROUPS = listOf("正片", "加更", "花絮")
    fun group(id: EpId): Int = when (id.tag) {
        "", "先导", "特别" -> 0
        "加更", "抢鲜", "沉浸", "陪看" -> 1
        else -> 2
    }
    fun group(name: String, title: String = "") = group(parse(name, title))
    private val NUM = Regex("(第|episode|ep|e(?=\\d)|vol\\.?|no\\.?)?\\s*(\\d+(?:\\.\\d+)?(?:\\s*[-~～至]\\s*\\d+)?|[零一二两三四五六七八九十百]+)\\s*([集期话話回章])?")
    private val PART = Regex("[（(\\[【]\\s*([上中下一二三四])\\s*[)）\\]】]|(?<![\\u4e00-\\u9fff])([上中下])(?:集|篇|半场|半|部)?(?![\\u4e00-\\u9fff])|(?<![a-z])(?:part|pt)\\.?\\s*(\\d)")
    private val PUNCT = Regex("[\\s_\\-·•.,，。:：;；|/\\\\()（）\\[\\]【】《》<>「」『』\"'~～!！?？#+]")
    private val UNITS = Regex("[第集期话話回章]")

    private val cache = object : LinkedHashMap<String, EpId>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, EpId>?) = size > 30000
    }

    fun validMd(m: Int, d: Int) = m in 1..12 && d in 1..31

    /** 把名字拆开。同一个名字算过的直接用（线路几百集、每 10 秒存进度都要算） */
    fun parse(name: String, title: String = ""): EpId {
        val ck = "$title\u0000$name"
        synchronized(cache) { cache[ck] }?.let { return it }
        val id = doParse(name, title)
        synchronized(cache) { cache[ck] = id }
        return id
    }

    private fun doParse(name: String, title: String): EpId {
        var s = RELEASE_GROUP.replace(name, "").lowercase().map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        s = stripTitle(s, title.lowercase())
        s = FILE_NOISE.replace(s, " ")
        s = stripEnTitle(s)
        s = EP_SEP.replace(s, "$1")
        s = QUALITY.replace(s, " ")
        s = SEASON.replace(s, " ")

        var date: String? = null
        DATE_FULL.find(s)?.let { m ->
            val g = m.groupValues
            val (mo, d) = if (g[2].isNotEmpty()) g[2] to g[3] else g[4] to g[5]
            if (validMd(mo.toInt(), d.toInt())) {
                date = g[1] + mo.padStart(2, '0') + d.padStart(2, '0')
                s = s.replaceRange(m.range, " ")
            }
        }
        if (date == null) (DATE_MD.find(s) ?: DATE_QI.find(s))?.let { m ->
            val (mo, d) = m.destructured
            if (validMd(mo.toInt(), d.toInt())) {
                date = mo.padStart(2, '0') + d.padStart(2, '0')
                s = s.replaceRange(m.range, " ")
            }
        }

        // 好几个类型词都有时取第一个；但带着纯享、花絮这类的就是片段（「先导片·沙滩小黑脚纯享」不是先导片）
        var tag = ""
        val marked = EP_MARK.containsMatchIn(s)
        for ((t, re) in if (marked) TAGS else TAGS + WEAK_TAGS) {
            if (re.containsMatchIn(s)) {
                if (tag.isEmpty() || t in EXTRA && tag !in EXTRA) tag = t
                s = re.replace(s, " ")
            }
        }

        var num: String? = null
        var raw: String? = null
        var unit: Char? = null
        if (date == null) {
            val ms = NUM.findAll(s).filter { m ->
                val v = m.groupValues[2]
                v[0].isDigit() || m.groupValues[1] == "第" || m.groupValues[3].isNotEmpty()
            }.toList()
            val m = ms.firstOrNull { it.groupValues[1].isNotEmpty() || it.groupValues[3].isNotEmpty() } ?: ms.firstOrNull()
            if (m != null) {
                val v = m.groupValues[2]
                num = if (v[0].isDigit()) normNum(v) else cn(v)?.toString()
                if (num != null) {
                    raw = v.takeIf { it.all { c -> c.isDigit() } }
                    unit = m.groupValues[3].firstOrNull()
                    s = s.replaceRange(m.range, " ")
                }
            }
        } else {
            // 日期之外的期号（「第12期 20230317」）不算进键里
            NUM.find(s)?.takeIf { it.groupValues[3].isNotEmpty() || it.groupValues[1] == "第" }?.let { s = s.replaceRange(it.range, " ") }
        }

        var part = 0
        PART.find(s)?.let { m ->
            val g = m.groupValues
            part = when (val c = g[1].ifEmpty { g[2] }) {
                "上", "一" -> 1
                "中", "二" -> 2
                "三" -> 3
                "四" -> 4
                "下" -> 9
                else -> if (c.isEmpty()) g[3].toIntOrNull() ?: 0 else 0
            }
            if (part != 0) s = s.replaceRange(m.range, " ")
        }

        val rest = PUNCT.replace(UNITS.replace(s, ""), "")
        val p = when (part) { 0 -> ""; 1 -> "上"; 2 -> "中"; 9 -> "下"; else -> "p$part" }
        val key = when {
            date != null -> "d$date$p$tag$rest"
            num != null -> "$num$p$tag$rest"
            else -> "$p$tag$rest".ifEmpty { name.trim() }
        }
        return EpId(date, num, raw, unit, part, tag, rest, key)
    }

    /** 「01」→「1」，「05.5」→「5.5」，「01-02」→「1-2」 */
    private fun normNum(v: String): String =
        v.replace(Regex("\\s"), "").split(Regex("[-~～至]")).joinToString("-") { seg ->
            val (a, b) = seg.split('.', limit = 2).let { it[0] to it.getOrNull(1) }
            a.trimStart('0').ifEmpty { "0" } + (b?.trimEnd('0')?.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
        }

    /** 中文数字：一到九百九十九 */
    private fun cn(s: String): Int? {
        val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var cur = 0
        for (c in s) {
            when (c) {
                '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                else -> cur = d[c] ?: return null
            }
        }
        return (total + cur).takeIf { it > 0 }
    }

    /**
     * 用来比对的键：不同站点的同一集尽量算出一样的键。
     * 「第01期(上)」「1期 上」→ 1上；「2024-11-01 期」「第20241101期」→ d20241101；「第1集 HD」「EP01」「Episode 1」「S01E01」→ 1
     */
    fun key(name: String, title: String = ""): String = parse(name, title).key

    /** 这一集的名字其实就是个序号 */
    fun isNumber(name: String, title: String = "") = parse(name, title).plain

    /** 名字里的集数，不是序号的为 null */
    fun number(name: String, title: String = ""): Int? = parse(name, title).takeIf { it.plain }?.num?.toIntOrNull()

    /**
     * 这条线路按序号显示（1、2、3）还是按名字显示。
     * 电视剧的线路里常夹着「陪看1」「第01集彩蛋」，少数几个不算；按日期 / 期数分的、大半不是序号的、综艺里有名字的，按名字。
     */
    fun numeric(eps: List<Episode>, title: String = "", variety: Boolean = false): Boolean {
        if (eps.isEmpty()) return true
        if (dated(eps, title) || mmddLine(eps, title)) return false
        val odd = eps.count { !isNumber(it.name, title) }
        return odd * 2 <= eps.size && !(variety && odd > 0)
    }

    /** 线路按日期 / 期数分（综艺）：至少三分之一的分集带日期或「第N期」 */
    fun dated(eps: List<Episode>, title: String = ""): Boolean {
        if (eps.isEmpty()) return false
        val n = eps.count { e -> parse(e.name, title).let { it.date != null || it.unit == '期' } }
        return n > 0 && n * 3 >= eps.size
    }

    /** 「0310、0317、0324」：4 位数都像月日、而且不是一个接一个的，是按日期排的综艺，不是第 310 集 */
    private fun mmddLine(eps: List<Episode>, title: String): Boolean {
        val ns = eps.mapNotNull { e -> parse(e.name, title).takeIf { it.mmdd != null }?.num?.toIntOrNull() }
        if (ns.size < 2 || ns.size * 2 < eps.size) return false
        val steps = ns.zipWithNext { a, b -> b - a }
        return steps.count { it == 1 || it == -1 } * 2 < steps.size
    }

    /**
     * 第 n 集在线路里是第几个（0 开始）：按名字里的数字找（线路里夹着花絮时序号会错位）。
     * 只有线路里的名字大多认不出数字时才按位置凑，不然凑出来的多半是彩蛋、别的分段（「501-1000」）。
     */
    fun indexOfNumber(eps: List<Episode>, n: Int, title: String = ""): Int {
        val s = n.toString()
        val ids = eps.map { parse(it.name, title) }
        ids.indexOfFirst { it.plain && it.num == s }.takeIf { it >= 0 }?.let { return it }
        ids.indexOfFirst { it.date == null && it.num == s && it.part == 0 && it.tag.isEmpty() }.takeIf { it >= 0 }?.let { return it }
        val known = ids.count { it.num != null || it.date != null }
        return if (known * 2 < eps.size && n in 1..eps.size) n - 1 else -1
    }

    private fun sameDay(a: EpId, b: EpId): Boolean {
        val da = a.date ?: a.mmdd ?: return false
        val db = b.date ?: b.mmdd ?: return false
        if (a.date == null && b.date == null) return false
        return if (da.length == 8 && db.length == 8) da == db else da.takeLast(4) == db.takeLast(4)
    }

    /**
     * 在线路里找同一集，找不到返回 -1。
     * 先比整键；对不上再放宽：同一天（有没有年份都行）、或同一个号，并且上下、类型一样，嘉宾名这类其余的字不管——只认唯一的一个。
     */
    fun indexOf(eps: List<Episode>, name: String, title: String = ""): Int {
        val a = parse(name, title)
        val ids = eps.map { parse(it.name, title) }
        ids.indexOfFirst { it.key == a.key }.takeIf { it >= 0 }?.let { return it }
        val loose = ids.withIndex().filter { (_, b) ->
            b.part == a.part && b.tag == a.tag && when {
                a.date != null || b.date != null -> sameDay(a, b)
                a.num != null -> b.num == a.num
                else -> false
            }
        }
        return loose.singleOrNull()?.index ?: -1
    }

    /** 两个名字是不是同一集 */
    fun same(a: String, b: String, title: String = ""): Boolean = indexOf(listOf(Episode(b, "")), a, title) == 0

    /**
     * [name] 后面那一集在 [eps] 里是第几个（0 开始），没有 = -1。
     * 按序号的线路找「第 n+1 集」（跳过夹在中间的花絮）；按名字的线路是后面第一个正片（跳过预告、花絮、纯享）。
     */
    fun nextIndex(eps: List<Episode>, name: String, title: String = "", numericLine: Boolean = numeric(eps, title)): Int {
        val n = number(name, title)
        if (numericLine && n != null) {
            val i = indexOfNumber(eps, n + 1, title)
            if (i >= 0) return i
        }
        return nextAfter(eps, indexOf(eps, name, title), title)
    }

    /** 线路里第 [i] 个后面的下一个正片（当前这个本身是花絮的话，下一个是什么都行），没有 = -1 */
    fun nextAfter(eps: List<Episode>, i: Int, title: String = ""): Int {
        if (i !in eps.indices) return -1
        val cur = parse(eps[i].name, title)
        for (j in i + 1 until eps.size) {
            val n = parse(eps[j].name, title)
            if (cur.extra || !n.extra && (n.tag !in ALT || cur.tag in ALT)) return j
        }
        return -1
    }

    /** 正片个数（不算预告、花絮这些），选「选集按哪条线路显示」用 */
    fun mainCount(eps: List<Episode>, title: String = "") = eps.count { !parse(it.name, title).extra }

    /**
     * 线路里每集名字都带着同一个前缀（「奔跑吧第七季第1期」），豆瓣片名又去不掉时，把这个前缀切掉。
     * 不切进数字、「第」、加更这类词；至少 3 集、前缀至少 3 个字才切。
     */
    fun trimCommon(eps: List<Episode>): List<Episode> {
        if (eps.size < 3) return eps
        var p = eps.map { it.name }.reduce { a, b -> a.commonPrefixWith(b) }
        while (p.isNotEmpty() && (p.last().isDigit() || p.last() in "第0123456789零一二两三四五六七八九十百-_.·(（[【 " || p.last().isLetter() && p.last().code < 0x80)) p = p.dropLast(1)
        if (p.trim().length < 3 || p.any { it.isDigit() }) return eps
        if (TAGS.any { it.second.containsMatchIn(p.lowercase()) } || PART.containsMatchIn(p.lowercase())) return eps
        return eps.map { e -> e.name.removePrefix(p).trim().takeIf { it.isNotEmpty() }?.let { e.copy(name = it) } ?: e }
    }

    /**
     * 去掉片名、文件大小、扩展名、清晰度、整季信息后的名字，不截断（聚焦时显示完整名字用）。
     * 「[536.8MB]01_1080P.mp4【第1季（2022）24集全】」→「01」
     */
    fun clean(name: String, title: String = ""): String {
        var s = name.trim()
        s = stripTitle(s, title)
        s = RELEASE_GROUP.replace(s, "")
        s = FILE_NOISE.replace(s, " ")
        s = LABEL_NOISE.replace(s, " ")
        s = stripEnTitle(s)
        s = EP_SEP.replace(s, "$1")
        s = SEASON_ANY.replace(s, " ")
        repeat(3) { s = EMPTY_BRACKETS.replace(s, " ") }
        s = s.replace(Regex("\\s+"), " ").trim { it in EDGE }.trim()
        return s.ifEmpty { name.trim() }
    }

    /**
     * 选集按钮上显示的字：去掉噪音（[clean]），日期缩成「11.01」，「第1话 任务1 <枭>行动」→「1 任务1 <枭>行动」，太长的截断。
     * 「奔跑吧 第20241101期(上)」→「11.01 上」
     */
    fun label(name: String, title: String = ""): String = shorten(fullLabel(name, title))

    /** [label] 不截断的版本 */
    fun fullLabel(name: String, title: String = ""): String {
        val s = clean(name, title)
        val id = parse(name, title)
        if (id.date != null) {
            val low = s.lowercase()
            val m = DATE_FULL.find(low) ?: DATE_MD.find(low) ?: DATE_QI.find(low)
            if (m != null) {
                val d = id.date
                // 「20261022(第1期)」→「10.22 第1期」；只剩「第」「期」这种零头的不要
                val rest = s.removeRange(m.range)
                    .trim(' ', '-', '_', '·', '(', ')', '（', '）', '[', ']', '【', '】', ':', '：')
                    .let { if (it.all { c -> c in "第期 " }) "" else it }
                return "${d.substring(d.length - 4, d.length - 2)}.${d.takeLast(2)}" + if (rest.isNotEmpty()) " $rest" else ""
            }
        }
        // 按名字显示的线路里，「第1期」「0317」原样留着；只有「01」这种纯短数字才去掉前导 0
        if (s.length < 4 && s.all { it.isDigit() }) return s.trimStart('0').ifEmpty { "0" }
        // 电视剧带副标题：「第1话 任务1」「EP01 任务1」→「1 任务1」（综艺的「第3期」不动）
        EP_HEAD.find(s)?.let { m ->
            val rest = s.substring(m.range.last + 1).trim { it in EDGE }.trim()
            // 只有「E01」「EP01」：就是第几集
            if (rest.isEmpty() && !s.startsWith("第")) return normNum(m.groupValues[2])
            if (rest.isNotEmpty() && TAGS.none { it.second.containsMatchIn(rest.lowercase()) }) {
                return normNum(m.groupValues[1].ifEmpty { m.groupValues[2] }) + " " + rest
            }
        }
        return s
    }

    private val EP_HEAD = Regex("^(?:第\\s*(\\d{1,4})\\s*[集话話回]|(?:episode|ep|e)\\s*(\\d{1,4})(?!\\d))", RegexOption.IGNORE_CASE)

    /** 超过 [LABEL_MAX] 宽度截断 */
    private fun shorten(s: String): String {
        var w = 0
        for ((i, c) in s.withIndex()) {
            w += if (c.code < 0x80) 1 else 2
            if (w > LABEL_MAX) return s.substring(0, i).trimEnd() + "…"
        }
        return s
    }

    /**
     * 「第 3 集」或者「11.01 上」。[numericLine]：整条线路是不是都是序号，不知道（null）就只看这一集的名字。
     */
    fun text(name: String?, idx: Int, title: String = "", numericLine: Boolean? = null): String =
        if (name == null || (numericLine ?: isNumber(name, title))) "第 $idx 集" else label(name, title)

    /**
     * 把线路排成从早到晚。有的站按新到旧排，有的站按字符串排（「第10期、第11期…第19期、第1期上、第20期」），
     * 有的上下期反着放。只要带日期 / 期号的分集里有往回走的，就按日期 / 期号重新排（稳定排序）：
     * 认不出号的（「加更版」「超前」）跟着前一个走；同一期里先正片（上 < 中 < 下）、再加更、再其他。
     * 带日期的和只有期号的混在一起时，期号的在前（实测是「第1期…第36期」后面接日期命名的新几期）。
     */
    fun ascending(eps: List<Episode>, title: String = ""): List<Episode> {
        if (eps.size < 2) return eps
        val ids = eps.map { parse(it.name, title) }
        val full = ids.mapNotNull { it.date }.all { it.length == 8 }
        // (类别, 值)：期号 = 0，日期 = 1；太大的数（「50230518」这种写错的日期）当认不出
        // 花絮、陪看这类不参与排序（「陪看1」不是第 1 集），跟着前一个走
        fun key(id: EpId): Pair<Int, Double>? = when {
            id.extra -> null
            id.date != null -> 1 to (if (full) id.date else id.date.takeLast(4)).toDouble()
            else -> id.num?.substringBefore('-')?.toDoubleOrNull()?.takeIf { it < 100_000 }?.let { 0 to it }
        }
        val raw = ids.map { key(it) }
        val keyed = raw.filterNotNull()
        if (keyed.size < 2) return eps
        val cmp = compareBy<Pair<Int, Double>>({ it.first }, { it.second })
        if (keyed.zipWithNext().none { (a, b) -> cmp.compare(a, b) > 0 } && keyed.zipWithNext().none { (a, b) -> a == b }) return eps
        // 认不出号的跟着前一个（排在开头的跟着后一个）
        val first = raw.firstOrNull { it != null }
        var last: Pair<Int, Double>? = null
        val keys = raw.map { k -> (k ?: last ?: first).also { last = it } }
        fun tagRank(t: String) = when (t) { "" -> 1; "先导" -> 0; "加更" -> 2; in ALT -> 3; else -> 4 }
        val order = eps.indices.sortedWith(
            compareBy<Int, Pair<Int, Double>?>(nullsLast(cmp)) { keys[it] }
                .thenBy { if (raw[it] == null) 1 else 0 }
                .thenBy { tagRank(ids[it].tag) }
                .thenBy { ids[it].part },
        )
        return order.map { eps[it] }
    }

    /**
     * 跨线路比对用的粗键：只看是哪一天 / 第几期 / 第几集，不管上下、版本、嘉宾名。
     * 「d」日期、「q」第 N 期、「n」第 N 集；认不出的 null。
     */
    fun coarse(id: EpId, full: Boolean = false): String? = when {
        id.date != null -> "d" + if (full && id.date.length == 8) id.date else id.date.takeLast(4)
        id.num != null && id.unit == '期' -> "q" + id.num
        id.num != null && id.date == null -> "n" + id.num
        else -> null
    }

    /** 一条线路的评分明细，[value] 越大越适合拿来显示选集 */
    /**
     * [good] 对得上别的站的集、[fresh] 比别人新的、[bad] 对不上的；[dup] 名字一模一样重复的；
     * [rich] 同一天分了上下期、加更这些（不超过各站一般的条数）；[excess] 同一天的条数比各站一般的多出来的（同一份名单换个写法又放一遍）；
     * [vague] 名字只有日期、看不出是什么的；[noisy] 按钮上是一长串英文的（「Empresses.in.the.Palace.S01E01」这种网盘文件名）
     */
    data class Score(val good: Int, val fresh: Int, val bad: Int, val dup: Int, val rich: Int, val excess: Int = 0, val vague: Int = 0, val noisy: Int = 0) {
        val value get() = good * 10 + fresh * 5 - bad * 6 - dup * 4 - excess * 2 + minOf(rich, good) - minOf(vague, 5) - minOf(noisy, good + 1) * 2
    }

    /**
     * 给各条线路打分，选「选集按哪条显示」用。[lines]：(站点 key, 分集)。
     * 同一集要有好几个站都有（按站点数，一个站的多个来源只算一次）才算数：
     * 「相关」里的反应视频、拿回顾特辑日期当正片的、把「第01集」和「20261029下」两套名字混在一起的线路，
     * 多出来的那些对不上别的站，扣分；比大家新的几集不扣（更新快的站）。
     * 同一套名字里，分了上下期、带加更的更全，加一点；一模一样的名字重复好几遍的扣分。
     */
    fun score(lines: List<Pair<String, List<Episode>>>, title: String = ""): List<Score> =
        scoreInfo(lines.map { (site, eps) -> site to LineInfo(eps, title) })

    /** 一条线路打分要用的东西，每条线路只算一次（线路是陆续搜到的，每来一条都要重排） */
    class LineInfo(eps: List<Episode>, title: String) {
        val ids = eps.map { parse(it.name, title) }
        /** 名字一模一样（去掉清晰度、文件大小后）的重复条数 */
        val dup = eps.map { clean(it.name, title) }.let { it.size - it.distinct().size }
        /** 按钮上一长串英文字母的个数 */
        val noisy = eps.count { e -> fullLabel(e.name, title).count { it.code < 0x80 && it.isLetter() } >= 6 }
        /** 正片的显示名字连起来：名字一样的线路算同一种显示方式 */
        val sig: String by lazy { eps.filterIndexed { k, _ -> group(ids[k]) == 0 }.joinToString("\u0000") { label(it.name, title) } }
    }

    fun scoreInfo(lines: List<Pair<String, LineInfo>>): List<Score> {
        val ids = lines.map { it.second.ids }
        // 所有站的日期都带年份时按整个日期比（跨年：「20270101」比「1225」新，不是旧）
        val full = ids.all { l -> l.all { it.date == null || it.date.length == 8 } }
        fun coarse(id: EpId) = coarse(id, full)
        val main = ids.map { l -> l.filter { !it.extra }.mapNotNull { coarse(it) }.toSet() }
        // 只写了日期的（「20260905」），别的站标明了是回顾特辑、花絮、彩蛋，又没有哪个站给这天起过正片的名字：
        // 这天不是正片，光写日期的线路把它当正片了（实测《现在就出发》四季：九月的回顾特辑、每周四的花絮特辑）
        fun bare(id: EpId) = id.date != null && id.tag.isEmpty() && id.part == 0 && id.rest.isEmpty()
        val named = HashMap<String, HashSet<String>>()
        val extraOnly = HashMap<String, HashSet<String>>()
        lines.forEachIndexed { i, (site, _) ->
            ids[i].forEach { id -> if (!id.extra && !bare(id)) coarse(id)?.takeIf { it[0] == 'd' }?.let { named.getOrPut(it) { HashSet() } += site } }
            ids[i].filter { it.extra }.mapNotNull { coarse(it) }.filter { it[0] == 'd' && it !in main[i] }
                .forEach { extraOnly.getOrPut(it) { HashSet() } += site }
        }
        val notMain = extraOnly.filter { (k, v) -> v.size >= minOf(2, lines.size) && named[k].isNullOrEmpty() }.keys
        val keys = main.map { it - notMain }
        val support = HashMap<String, HashSet<String>>()
        lines.forEachIndexed { i, (site, _) -> keys[i].forEach { support.getOrPut(it) { HashSet() } += site } }
        val sites = lines.map { it.first }.distinct().size
        val need = if (sites <= 2) 1 else maxOf(2, (sites * 0.15).toInt())
        // 每条线路里每一天（每一期）有几个正片；各站一般是几个（中位数）
        val counts = ids.indices.map { i -> ids[i].filter { !it.extra }.mapNotNull { coarse(it) }.filter { it in keys[i] }.groupingBy { it }.eachCount() }
        val typical = HashMap<String, Int>()
        counts.flatMap { it.entries }.groupBy({ it.key }, { it.value }).forEach { (k, v) -> typical[k] = v.sorted()[v.size / 2] }
        return lines.indices.map { i ->
            val ks = keys[i]
            // 一条线路里混着两套编号（日期 / 期号 / 集号）时，少的那套算对不上
            val kinds = ks.groupBy { it[0] }
            val top = kinds.maxByOrNull { (k, v) -> v.count { (support[it]?.size ?: 0) >= need } * 1000 + v.size + if (k == 'd') 1 else 0 }?.key
            var good = 0; var fresh = 0; var bad = main[i].count { it in notMain }
            for ((kind, v) in kinds) {
                if (kind != top) { bad += v.size; continue }
                val ok = v.filter { (support[it]?.size ?: 0) >= need }
                // 一集都对不上别人的，没有「比别人新」可言；新出的也就最近几集，多了是别的东西
                val newest = ok.maxOfOrNull { ord(it) } ?: Double.POSITIVE_INFINITY
                val cap = maxOf(2, ok.size / 4)
                good += ok.size
                for (k in v) if ((support[k]?.size ?: 0) < need) { if (ord(k) > newest && fresh < cap) fresh++ else bad++ }
            }
            // 重复按显示的名字算：同一天的「第1期」「第2期」键一样，但不是重复
            val l = ids[i]
            val dup = lines[i].second.dup
            val rich = counts[i].entries.sumOf { (k, n) -> minOf(n, typical[k] ?: 1) - 1 }
            val excess = counts[i].entries.sumOf { (k, n) -> (n - maxOf(typical[k] ?: 1, 1)).coerceAtLeast(0) }
            // 日期后面只跟一个光秃秃的序号（「20261022-01」），不如「20261022第1期」好认
            // 只有日期的也一样（同一天上下期、加更都叫「20261012」，看不出是什么）
            val vague = l.count { it.date != null && !it.extra && (bare(it) || it.rest.isNotEmpty() && it.rest.all { c -> c.isDigit() }) }
            Score(good, fresh, bad, dup, rich, excess, vague, lines[i].second.noisy)
        }
    }

    private fun ord(k: String): Double = k.drop(1).substringBefore('-').toDoubleOrNull() ?: 0.0
}
