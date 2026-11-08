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
        val v = when {
            low.endsWith("man") -> s.dropLast(3) + "men"
            low.endsWith("men") -> s.dropLast(3) + "man"
            low.endsWith("ies") -> s.dropLast(3) + "y"
            low.endsWith("ss") -> s + "es"
            low.endsWith("s") -> s.dropLast(1)
            low.endsWith("y") && low[low.length - 2] !in "aeiou" -> s.dropLast(1) + "ies"
            else -> s + "s"
        }
        return listOf(v).filter { it.length >= 3 && !it.equals(s, true) }
    }

    /**
     * 合并后的排序：评价人数为主（看的人多的通常就是想找的），片名 / 原名正好对上的 ×10、开头对上的 ×3。
     * 没人评价的冷门条目放最后，保持豆瓣给的顺序。
     */
    fun rank(list: List<Title>, q: String): List<Title> {
        val s = q.trim()
        fun boost(t: Title) = when {
            t.name.equals(s, true) || t.origName.equals(s, true) -> 10L
            t.name.startsWith(s, true) || t.origName.startsWith(s, true) -> 3L
            else -> 1L
        }
        return list.sortedByDescending { it.ratingCount * boost(it) }
    }
}
