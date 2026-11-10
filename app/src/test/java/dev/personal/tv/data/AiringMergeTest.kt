package dev.personal.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiringMergeTest {
    private fun tv(aired: Int, total: Int, schedule: String? = null, full: Boolean = true) =
        Title("37191789", "嫁金钗", 2026, "", Kind.SERIES, emptyList(), null, totalEps = total, airedEps = aired, schedule = schedule, full = full)

    @Test fun listSaysCompleteClearsUpdating() {
        // 本地存着「更新至 28 / 共 30」，列表接口现在写「30集全」
        val m = tv(28, 30, "更新中").withEpsFrom(tv(30, 30, full = false))
        assertEquals(30, m.airedEps)
        assertNull(m.schedule)
        assertFalse(m.airing)
        assertEquals("全 30 集", m.epsText)
    }

    @Test fun freshDetailCompleteWinsOverStaleUpdating() {
        // 刷新时：新详情并上旧的（旧的可能被线路集数推高过）
        val m = tv(30, 30).withEpsFrom(tv(28, 30, "更新中"))
        assertNull(m.schedule)
        assertEquals(30, m.airedEps)
    }

    @Test fun listUpdateMovesForwardOnly() {
        val m = tv(28, 30, "更新中").withEpsFrom(tv(29, 29, "更新中", full = false))
        assertEquals(29, m.airedEps)
        assertEquals(30, m.totalEps)
        assertTrue(m.airing)
        val back = tv(28, 30, "更新中").withEpsFrom(tv(20, 20, "更新中", full = false))
        assertEquals(28, back.airedEps)
    }

    @Test fun lineBumpedCountIsKept() {
        // 线路已经有 29 集，豆瓣详情还写更新至 28
        val m = tv(28, 30, "更新中").withEpsFrom(tv(29, 30, "更新中"))
        assertEquals(29, m.airedEps)
        assertTrue(m.airing)
    }

    @Test fun listWithoutEpisodeInfoChangesNothing() {
        val old = tv(28, 30, "更新中")
        assertEquals(old, old.withEpsFrom(tv(1, 1, full = false)))
    }
}
