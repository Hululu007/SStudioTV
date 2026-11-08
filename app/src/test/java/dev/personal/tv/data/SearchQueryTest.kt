package dev.personal.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTest {
    @Test fun englishPlurals() {
        assertEquals(listOf("gentlemen"), SearchQuery.variants("gentleman"))
        assertEquals(listOf("The Gentleman"), SearchQuery.variants("The Gentlemen"))
        assertEquals(listOf("friend"), SearchQuery.variants("friends"))
        assertEquals(listOf("story"), SearchQuery.variants("stories"))
        assertEquals(listOf("breaking bads"), SearchQuery.variants("breaking bad"))
        assertEquals(emptyList<String>(), SearchQuery.variants("繁花"))
        assertEquals(emptyList<String>(), SearchQuery.variants("1917"))
        assertEquals(emptyList<String>(), SearchQuery.variants("Love Death Robots series"))
        assertEquals(emptyList<String>(), SearchQuery.variants("virus"))
        assertEquals(listOf("movie"), SearchQuery.variants("movies"))
    }

    private fun t(id: String, name: String, count: Int, orig: String = "") =
        Title(id, name, 2020, "", Kind.MOVIE, emptyList(), null, origName = orig, ratingCount = count)

    @Test fun exactMatchFirstThenPopular() {
        // 原名「The Gentlemen」算对上（单复数、去掉 The），没人评价但片名对上的排在只沾边的前面
        val out = SearchQuery.rank(listOf(t("1", "Gentleman", 0), t("2", "绅士", 1868), t("3", "绅士们", 255483, "The Gentlemen")), "gentleman")
        assertEquals(listOf("3", "1", "2"), out.map { it.id })
        // 刚开播的新剧还没人评分，输入完整片名也要排第一
        val fresh = SearchQuery.rank(listOf(t("1", "繁花似锦", 90000), t("2", "繁花", 0)), "繁花")
        assertEquals("2", fresh.first().id)
        val cn = SearchQuery.rank(listOf(t("1", "海上繁花", 22224), t("2", "繁花", 5000)), "繁花")
        assertEquals("2", cn.first().id)
    }
}
