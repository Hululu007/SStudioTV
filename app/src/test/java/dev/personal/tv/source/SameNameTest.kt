package dev.personal.tv.source

import dev.personal.tv.data.Kind
import dev.personal.tv.data.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同名不同片：湖南卫视纪录片《后会有期》(2019，6 集) 和美国电影《后会有期》(2019)、2026 年的同名综艺、AI 短剧 */
class SameNameTest {
    @Test fun regionAndYear() {
        // 纪录片 vs 美国电影：年份一样，地区对不上
        assertEquals(-1, Match.check(2019, false, "中国大陆", "2019", "美国"))
        // 详情里才有年份：2011 年的比利时电影 vs 2019 的
        assertEquals(-1, Match.check(2011, true, "比利时", "2019-03-09", "美国"))
        assertEquals(1, Match.check(2019, false, "中国大陆", "2019", "内地"))
        assertEquals(1, Match.check(2019, true, "美国", "2019", "美国,加拿大"))
        // 港片标成大陆、美国片标成加拿大：不误伤
        assertEquals(1, Match.check(2004, true, "中国香港", "2004", "大陆"))
        assertEquals(1, Match.check(2019, true, "美国", "", "加拿大"))
        // 没资料：不判断
        assertEquals(0, Match.check(2019, false, "中国大陆", "", ""))
        assertEquals(0, Match.check(2019, false, "中国大陆", "", "其它"))
    }

    private fun line(site: String, vararg eps: String) = PlayLine(site, site, "f", "后会有期", "", eps.map { Episode(it, it) })

    private val doc = Title("1", "后会有期", 2019, "中国大陆", Kind.SERIES, emptyList(), null, dtype = "tv", totalEps = 6)
    private val good = line("橘猫", "永不落幕的青春秀场", "永不褪色的藏蓝青春", "永不散场的社团之约", "永不停歇的音乐之路", "永不止步的篮球之路", "永不缺席的毕业仪式")

    @Test fun otherShowsWithSameName() {
        assertTrue(Lines.stranger(doc, listOf(good), line("夸父", "11.12 第5期（上）", "11.12 第5期（下）", "11.13 加更版", "11.19 第6期（上）")))
        assertTrue(Lines.stranger(doc, listOf(good), line("AI", *Array(73) { "第${it + 1}集" })))
        // 同一部、名字写法不一样（少了一集）：留着
        assertFalse(Lines.stranger(doc, listOf(good), line("荐片", "永不停歇的音乐之路", "永不止步的篮球之路", "永不落幕的青春秀场")))
        // 没有对得上的线路时没法比：都留着
        assertFalse(Lines.stranger(doc, emptyList(), line("AI", "第1集")))
    }

    @Test fun numberedSeries() {
        val show = Title("2", "某剧", 2020, "中国大陆", Kind.SERIES, emptyList(), null, dtype = "tv", totalEps = 30)
        val sure = line("a", *Array(30) { "第${it + 1}集" })
        assertFalse(Lines.stranger(show, listOf(sure), line("b", *Array(30) { "%02d".format(it + 1) })))
        assertFalse(Lines.stranger(show, listOf(sure), line("c", *Array(36) { "第${it + 1}集" })))
        assertTrue(Lines.stranger(show, listOf(sure), line("d", *Array(80) { "第${it + 1}集" })))
    }
}
