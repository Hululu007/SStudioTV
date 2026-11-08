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
    }

    private fun t(id: String, name: String, count: Int, orig: String = "") =
        Title(id, name, 2020, "", Kind.MOVIE, emptyList(), null, origName = orig, ratingCount = count)

    @Test fun popularFirstExactBoosted() {
        val out = SearchQuery.rank(listOf(t("1", "Gentleman", 0), t("2", "绅士", 1868), t("3", "绅士们", 255483)), "gentleman")
        assertEquals(listOf("3", "2", "1"), out.map { it.id })
        val cn = SearchQuery.rank(listOf(t("1", "海上繁花", 22224), t("2", "繁花", 5000)), "繁花")
        assertEquals("2", cn.first().id)
    }
}
