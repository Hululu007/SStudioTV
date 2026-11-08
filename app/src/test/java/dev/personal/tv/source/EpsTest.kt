package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpsTest {
    private fun eps(vararg n: String) = n.map { Episode(it, "u/$it") }

    @Test fun seriesNamesAreNumbers() {
        assertTrue(Eps.numeric(eps("第1集", "第02集", "EP03", "04", "第5集HD")))
        assertEquals("12", Eps.key("第12集 高清"))
        assertEquals("第 3 集", Eps.text("第03集", 3))
    }

    @Test fun varietyNamesAreNotNumbers() {
        assertFalse(Eps.numeric(eps("第1期上", "第1期下", "第2期上")))
        assertFalse(Eps.numeric(eps("20241101期", "20241108期")))
    }

    @Test fun sameEpisodeAcrossSites() {
        assertEquals(Eps.key("第01期(上)"), Eps.key("1期 上"))
        assertEquals(Eps.key("第20241101期"), Eps.key("2024-11-01期"))
        assertEquals(Eps.key("奔跑吧 20241101期", "奔跑吧"), Eps.key("20241101", ""))
        assertTrue(Eps.key("20241101期上") != Eps.key("20241101期下"))
        val line = eps("20241025期", "20241101期上", "20241101期下")
        assertEquals(2, Eps.indexOf(line, "2024-11-01 下"))
        assertEquals(-1, Eps.indexOf(line, "20241108期"))
    }

    @Test fun labels() {
        assertEquals("11.01 上", Eps.label("奔跑吧 第20241101期(上)", "奔跑吧"))
        assertEquals("11.08", Eps.label("2024-11-08"))
        assertEquals("加更版", Eps.label("加更版"))
        assertEquals("第1期", Eps.label("第1期"))
        assertEquals("7", Eps.label("07"))
        assertEquals("0317", Eps.label("种地吧 0317", "种地吧"))
        assertEquals("0317", Eps.text("0317", 3, numericLine = false))
        assertEquals("第 3 集", Eps.text("0317", 3, numericLine = true))
    }

    @Test fun seriesWithExtrasStaysNumeric() {
        // 真实线路：正片里夹着陪看、彩蛋，最后一集叫「第30集已完结」
        val line = eps("第01集", "第01集彩蛋", "第02集", "陪看1", "第03集", "第30集已完结")
        assertTrue(Eps.numeric(line))
        assertEquals("30", Eps.key("第30集已完结"))
        assertEquals(4, Eps.indexOfNumber(line, 3))
        assertEquals(1, Eps.indexOfNumber(eps("a", "b", "c"), 2))
        // 综艺里有一个名字就按名字
        assertFalse(Eps.numeric(eps("第1期", "第1期加更", "第2期"), variety = true))
        // 按「期」分的也按名字
        assertFalse(Eps.numeric(eps("第1期", "第2期", "第3期")))
    }

    @Test fun newestFirstGetsReversed() {
        val line = eps("20241108期", "20241101期", "20241025期")
        assertEquals("20241025期", Eps.ascending(line).first().name)
        val mixed = eps("20241108期", "加更")
        assertEquals(mixed, Eps.ascending(mixed))
    }
}
