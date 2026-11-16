package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {
    private fun q(flag: String, remarks: String = "", name: String = "片") = Lines.quality(PlayLine("s", "s", flag, name, remarks, emptyList()))

    @Test fun marks() {
        assertEquals(0, q("ffm3u8", "HDTC"))
        assertEquals(0, q("lzm3u8", "抢先版"))
        assertEquals(0, q("TC中字"))
        assertEquals(3, q("4K"))
        assertEquals(3, q("夸克原画"))
        assertEquals(2, q("ffm3u8", "BD国语"))
        assertEquals(2, q("1080P"))
        assertEquals(1, q("bdm3u8", "HD中字"))
        assertEquals(1, q("ffm3u8", "更新至12集"))
        assertEquals(1, q("tsm3u8", "正片"))
    }
}
