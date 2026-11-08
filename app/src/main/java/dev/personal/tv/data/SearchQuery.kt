package dev.personal.tv.data

/** 输入法搜索时到豆瓣搜什么、搜回来怎么排 */
object SearchQuery {
    /**
     * 英文的单复数换一下再搜一遍：豆瓣不做词形还原，搜 gentleman 出不来《绅士们》（The Gentlemen）。
     * 只动最后一个词；中文、数字结尾的不动。
     */
    fun variants(q: String): List<String> {
        val s = q.trim()
        if (s.length < 3 || s.any { it.code > 0x7F } || !s.last().isLetter()) return emptyList()
        val low = s.lowercase()
        // 单复数同形的、以 -us / -is / -ss 结尾的（virus、tennis）不是复数，不造「sery」「viru」这种词
        if (low.endsWith("series") || low.endsWith("species")) return emptyList()
        val v = when {
            low.endsWith("man") -> s.dropLast(3) + "men"
            low.endsWith("men") -> s.dropLast(3) + "man"
            // movies、zombies、cookies 是 -ie 加 s，不是 -y 变的
            listOf("movies", "zombies", "cookies", "rookies", "hippies", "selfies", "goonies").any { low.endsWith(it) } -> s.dropLast(1)
            low.endsWith("ies") -> s.dropLast(3) + "y"
            low.endsWith("ss") -> s + "es"
            low.endsWith("us") || low.endsWith("is") -> return emptyList()
            low.endsWith("s") -> s.dropLast(1)
            low.endsWith("y") && low[low.length - 2] !in "aeiou" -> s.dropLast(1) + "ies"
            else -> s + "s"
        }
        return listOf(v).filter { it.length >= 3 && !it.equals(s, true) }
    }

    /**
     * 合并后的排序，分三档：片名 / 原名正好对上的（含单复数）> 开头对上的 > 其余；档内按评价人数（看的人多的通常就是想找的）。
     * 不用「人数 × 加权」：刚开播的新剧、只在联想里出现的条目没有人数，乘多少都是 0，输入完整片名反而排最后。
     * 人数一样（都没人评）的保持豆瓣给的顺序。
     */
    fun rank(list: List<Title>, q: String): List<Title> {
        val qs = (listOf(q.trim()) + variants(q)).map { it.lowercase() }
        // 「The Gentlemen」按「gentlemen」比
        fun names(t: Title) = listOf(t.name, t.origName).filter { it.isNotEmpty() }
            .flatMap { val n = it.lowercase().trim(); listOf(n, n.replace(Regex("^(the|a|an)\\s+"), "")) }
        fun tier(t: Title): Int {
            val ns = names(t)
            return when {
                ns.any { it in qs } -> 0
                ns.any { n -> qs.any { n.startsWith(it) } } -> 1
                else -> 2
            }
        }
        return list.sortedWith(compareBy<Title> { tier(it) }.thenByDescending { it.ratingCount })
    }
}
