package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {
    @Test fun seasons() {
        val k = Match.Key("庆余年", 2)
        assertEquals(k, Match.key("庆余年 第二季"))
        assertEquals(k, Match.key("庆余年第二季"))
        assertEquals(k, Match.key("庆余年2"))
        assertEquals(k, Match.key("庆余年第二季[国语]"))
        assertEquals(k, Match.key("庆余年 第二季(2024)"))
        assertEquals(k, Match.key("庆余年第2季"))
        assertEquals(Match.Key("庆余年", 1), Match.key("庆余年"))
        assertEquals(Match.Key("请回答", 12), Match.key("请回答第十二季"))
        assertEquals(Match.Key("1917", 1), Match.key("1917"))
        assertEquals(Match.Key("流浪地球", 2), Match.key("流浪地球2"))
        assertEquals(Match.Key("theoffice", 3), Match.key("The Office Season 3"))
    }

    @Test fun same() {
        assertTrue(Match.same("庆余年 第二季", 2024, false, "庆余年第二季", "2024"))
        assertTrue(Match.same("庆余年 第二季", 2024, false, "庆余年2", ""))
        assertFalse(Match.same("庆余年 第二季", 2024, false, "庆余年", "2019"))
        assertTrue(Match.same("繁花", 2023, false, "繁花 粤语版", "2023"))
        assertTrue(Match.same("繁花", 2023, false, "繁花（2023）", "2024"))
        assertFalse(Match.same("沙丘", 2021, true, "沙丘", "1984"))
        assertTrue(Match.same("毛骗 终结篇", 2015, false, "毛骗终结篇", "2015"))
        assertFalse(Match.same("毛骗 终结篇", 2015, false, "毛骗", "2010"))
        assertTrue(Match.same("哪吒之魔童闹海", 2025, true, "哪吒之魔童闹海 HD", "2025"))
    }

    @Test fun query() {
        assertEquals("庆余年", Match.query("庆余年 第二季"))
        assertEquals("庆余年", Match.query("庆余年第二季"))
        assertEquals("毛骗", Match.query("毛骗 终结篇"))
        assertEquals("流浪地球", Match.query("流浪地球2"))
        assertEquals("繁花", Match.query("繁花"))
        assertEquals("1917", Match.query("1917"))
    }
}
