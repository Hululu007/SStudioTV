package dev.personal.tv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekGestureTest {
    private val dur = 600_000L
    private val key = "right"

    @Test fun tapCommitsTenSecondsOnRelease() {
        val g = SeekGesture()
        assertTrue(g.down(key, 1, 0, 100_000, dur, true))
        assertFalse(g.previewing)
        assertEquals(110_000L, g.up(key, 100_000))
        assertFalse(g.active)
    }

    @Test fun tapBackClampsAtZero() {
        val g = SeekGesture()
        g.down(key, -1, 0, 4_000, dur, true)
        assertEquals(0L, g.up(key, 4_000))
    }

    @Test fun tapForwardClampsBeforeEnd() {
        val g = SeekGesture()
        g.down(key, 1, 0, 598_000, dur, true)
        assertEquals(dur - SeekGesture.END_GUARD_MS, g.up(key, 598_000))
    }

    @Test fun holdPreviewsWithoutSeekAndCommitsOnce() {
        val g = SeekGesture()
        g.down(key, 1, 0, 100_000, dur, true)
        g.down(key, 1, 500, 100_500, dur, true)
        assertTrue(g.previewing)
        assertEquals(110_000L, g.target())
        g.down(key, 1, 550, 100_550, dur, true)
        // 50ms * 20 s/s = 1s
        assertEquals(111_000L, g.target())
        // 提交的是预览目标，不是松手时位置 + 10s
        assertEquals(111_000L, g.up(key, 101_000))
        assertNull(g.up(key, 101_000))
    }

    @Test fun holdAccelerates() {
        val g = SeekGesture()
        g.down(key, 1, 0, 0, dur, true)
        g.down(key, 1, 500, 0, dur, true)
        var t = 500L
        var slow = 0L
        val a = g.target()
        while (t < 1_500) { t += 50; g.down(key, 1, t, 0, dur, true) }
        slow = g.target() - a // 1s @20
        val b = g.target()
        // 跳过 2s 以后的段速度更快
        t = 2_000
        g.down(key, 1, t, 0, dur, true)
        val c = g.target()
        while (t < 3_000) { t += 50; g.down(key, 1, t, 0, dur, true) }
        val fast = g.target() - c
        assertEquals(20_000L, slow)
        assertTrue(fast > slow)
        assertTrue(c > b)
    }

    @Test fun holdBackwardClampsAtZero() {
        val g = SeekGesture()
        g.down(key, -1, 0, 15_000, dur, true)
        g.down(key, -1, 500, 15_000, dur, true)
        var t = 500L
        repeat(20) { t += 100; g.down(key, -1, t, 15_000, dur, true) }
        assertEquals(0L, g.up(key, 15_000))
    }

    @Test fun cancelDiscardsPreview() {
        val g = SeekGesture()
        g.down(key, 1, 0, 100_000, dur, true)
        g.down(key, 1, 500, 100_000, dur, true)
        g.cancel()
        assertFalse(g.active)
        assertNull(g.up(key, 100_000))
    }

    @Test fun liveOrUnseekableIsIgnored() {
        val g = SeekGesture()
        assertFalse(g.down(key, 1, 0, 100_000, dur, false))
        assertFalse(g.active)
        assertNull(g.up(key, 100_000))
    }

    @Test fun unknownDurationTapsButNeverPreviews() {
        val g = SeekGesture()
        g.down(key, 1, 0, 50_000, 0, true)
        g.down(key, 1, 500, 50_000, 0, true)
        g.down(key, 1, 600, 50_000, 0, true)
        assertFalse(g.previewing)
        assertEquals(60_000L, g.up(key, 50_000))
    }

    @Test fun otherKeyWhileHeldIsSwallowedAndDoesNotCommit() {
        val g = SeekGesture()
        g.down("right", 1, 0, 100_000, dur, true)
        assertTrue(g.down("left", -1, 100, 100_000, dur, true))
        assertNull(g.up("left", 100_000))
        assertTrue(g.active)
        assertEquals(110_000L, g.up("right", 100_000))
    }

    @Test fun staleWhenKeyUpLost() {
        val g = SeekGesture()
        g.down(key, 1, 0, 0, dur, true)
        assertFalse(g.stale(1_000))
        assertTrue(g.stale(SeekGesture.STALE_MS + 1))
        g.cancel()
        assertFalse(g.stale(10_000))
    }

    @Test fun stallDoesNotJump() {
        val g = SeekGesture()
        g.down(key, 1, 0, 0, dur, true)
        g.down(key, 1, 500, 0, dur, true)
        val a = g.target()
        g.down(key, 1, 1_100, 0, dur, true) // 600ms 卡顿，只按 250ms 算
        assertEquals(a + 5_000L, g.target())
    }

    private fun SeekGesture.target() = targetMs
}
