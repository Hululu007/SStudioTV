package dev.personal.tv.source

/**
 * 片源里的片名 ↔ 豆瓣条目。片源的名字很乱：「庆余年第二季」「庆余年2」「庆余年 第二季[国语]」「庆余年第二季(2024)」，
 * 都要认成同一部；「庆余年」（第一季）不能认成第二季；同名不同年的电影（翻拍）也不能混。
 */
object Match {
    data class Key(val base: String, val season: Int)

    private val CN = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)

    private fun cnNum(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty() || s.any { it !in CN }) return null
        // 十一、二十、二十三
        val i = s.indexOf('十')
        if (i < 0) return CN[s[0]]
        val tens = if (i == 0) 1 else CN[s[0]] ?: return null
        val ones = if (i == s.lastIndex) 0 else CN[s[i + 1]] ?: return null
        return tens * 10 + ones
    }

    /** 去掉括号里的东西、语言版本、清晰度标记、标点和空格，统一小写和半角 */
    fun norm(name: String): String {
        var s = name.trim()
            .map { c -> if (c in '！'..'～') (c - 0xFEE0) else if (c == '　') ' ' else c }.joinToString("")
            .lowercase()
        s = s.replace(Regex("[\\[【(（].*?[\\]】)）]"), "")
            .replace(Regex("(国语|粤语|普通话|台配|日语|英语|中字|双语)版?$"), "")
            .replace(Regex("(hd|tc|ts|1080p|720p|4k|蓝光|超清|高清|完整版|未删减版?|tv版|剧场版)$"), "")
        return s.replace(Regex("[\\s\\p{P}\\p{S}·]"), "")
    }

    fun key(name: String): Key {
        val n = norm(name)
        Regex("^(.+?)第([一二两三四五六七八九十\\d]+)[季部]$").find(n)?.let { m ->
            cnNum(m.groupValues[2])?.let { return Key(m.groupValues[1], it) }
        }
        Regex("^(.+?)(?:season|s)(\\d{1,2})$").find(n)?.let { m -> return Key(m.groupValues[1], m.groupValues[2].toInt()) }
        // 末尾一两位数字当季数（流浪地球2、庆余年2），但纯数字片名（1917、2046）不拆
        Regex("^(.*\\D)(\\d{1,2})$").find(n)?.let { m ->
            if (m.groupValues[1].length >= 2) return Key(m.groupValues[1], m.groupValues[2].toInt())
        }
        return Key(n, 1)
    }

    /** 拿去片源搜索的词：去掉季数，取空格前的主名（「毛骗 终结篇」搜「毛骗」，结果再按完整名字比） */
    fun query(name: String): String {
        var s = name.trim().replace(Regex("[\\[【(（].*?[\\]】)）]"), "").trim()
        s = s.replace(Regex("\\s*(第[一二两三四五六七八九十\\d]+[季部]|season\\s*\\d+)\\s*$", RegexOption.IGNORE_CASE), "")
        val head = s.substringBefore(' ').trim()
        if (head.length >= 2 && head != s) s = head
        s = s.replace(Regex("(?<=\\D)\\d{1,2}$"), "")
        return s.trim().ifEmpty { name.trim() }
    }

    /**
     * 是不是同一部。年份两边都有才比：电影差 1 年以内（上映年 / 出品年），剧集差 2 年以内（有的站写首季年份）。
     */
    fun same(doubanName: String, doubanYear: Int, isMovie: Boolean, vodName: String, vodYear: String): Boolean {
        val a = key(doubanName)
        val b = key(vodName)
        if (a != b) return false
        val vy = Regex("(19|20)\\d{2}").find(vodYear)?.value?.toInt() ?: return true
        if (doubanYear <= 0) return true
        return kotlin.math.abs(vy - doubanYear) <= if (isMovie) 1 else 2
    }
}
