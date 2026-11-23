package dev.personal.tv.source

import dev.personal.tv.data.Kind
import dev.personal.tv.data.Progress
import dev.personal.tv.data.ResumeRef
import dev.personal.tv.data.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdResumeTest {
    private fun eps(vararg n: String) = n.map { Episode(it, "id/$it") }
    private val drama = Title("2", "繁花", 2023, "", Kind.SERIES, emptyList(), null, totalEps = 30, airedEps = 30)
    private val movie = Title("3", "流浪地球", 2019, "", Kind.MOVIE, emptyList(), null)
    private val url = "http://example.com/cfg.json"
    private fun line(vodId: String = "123", vararg e: String) =
        PlayLine("siteA", "站点A", "lzm3u8", "繁花", "", eps(*e), vodId)
    private fun ref(cfg: String = ColdResume.configId(url)!!, ep: String = "第07集") =
        ResumeRef(cfg, "siteA", "站点A", "lzm3u8", "123", "繁花", ep, 7)
    private fun prog(ep: Int, name: String?, sec: Int, dur: Int = 3000, named: Boolean? = null) =
        Progress("2", ep, sec, dur, 0, "siteA|lzm3u8", false, name, named)

    @Test fun configIdIsStableAndHidesUrl() {
        val a = ColdResume.configId(url)!!
        assertEquals(a, ColdResume.configId(" $url "))
        assertNotEquals(a, ColdResume.configId("http://example.com/other.json"))
        assertEquals(16, a.length)
        assertFalse(a.contains("example"))
        assertNull(ColdResume.configId(null))
        assertNull(ColdResume.configId(" "))
    }

    @Test fun safeIdRejectsStreamUrlsAndSecrets() {
        assertTrue(ColdResume.safeId("12345"))
        assertTrue(ColdResume.safeId("/detail/abc-def"))
        assertTrue(ColdResume.safeId("https://pan.quark.cn/s/abcd1234"))
        assertFalse(ColdResume.safeId(""))
        assertFalse(ColdResume.safeId("https://cdn.x.com/v/index.m3u8"))
        assertFalse(ColdResume.safeId("https://pan.x.com/s/abc?pwd=1234"))
        assertFalse(ColdResume.safeId("id?token=abc"))
        assertFalse(ColdResume.safeId("a b"))
        assertFalse(ColdResume.safeId("x".repeat(200)))
    }

    @Test fun refForSkipsLocalAndMissingIds() {
        val l = line("123", "第07集")
        val r = ColdResume.refFor(url, l, "第07集", 1, "local")!!
        assertEquals("siteA", r.siteKey)
        assertEquals("123", r.vodId)
        // 不存播放地址：分集地址不在记录里
        assertFalse(r.toString().contains("id/"))
        assertNull(ColdResume.refFor(url, l.copy(siteKey = "local"), "第07集", 1, "local"))
        assertNull(ColdResume.refFor(url, l.copy(vodId = ""), "第07集", 1, "local"))
        assertNull(ColdResume.refFor(null, l, "第07集", 1, "local"))
    }

    @Test fun usableChecksConfigSiteAndEpisode() {
        val keys = listOf("siteA", "siteB")
        assertTrue(ColdResume.usable(ref(), url, keys, emptyList(), "第07集", 7, "繁花"))
        // 名字写法不同但是同一集
        assertTrue(ColdResume.usable(ref(), url, keys, emptyList(), "第7集", 7, "繁花"))
        // 没名字按集数
        assertTrue(ColdResume.usable(ref(), url, keys, emptyList(), null, 7, "繁花"))
        assertFalse(ColdResume.usable(ref(), url, keys, emptyList(), null, 8, "繁花"))
        // 换了片源
        assertFalse(ColdResume.usable(ref(), "http://other/cfg.json", keys, emptyList(), "第07集", 7, "繁花"))
        assertFalse(ColdResume.usable(ref(), null, keys, emptyList(), "第07集", 7, "繁花"))
        // 站点没了 / 被屏蔽
        assertFalse(ColdResume.usable(ref(), url, listOf("siteB"), emptyList(), "第07集", 7, "繁花"))
        assertFalse(ColdResume.usable(ref(), url, keys, listOf("siteA"), "第07集", 7, "繁花"))
        // 要放别的集
        assertFalse(ColdResume.usable(ref(), url, keys, emptyList(), "第08集", 8, "繁花"))
        // 老记录
        assertFalse(ColdResume.usable(null, url, keys, emptyList(), "第07集", 7, "繁花"))
    }

    @Test fun buildLineFindsFlagAndRejectsOtherVod() {
        val vod = Vod("123", "繁花", "", "", "2023", "", "", "", "", "",
            listOf(PlayFlag("other", eps("第01集")), PlayFlag("lzm3u8", eps("第06集", "第07集", "第08集"))))
        val l = ColdResume.buildLine(ref(), vod, "繁花")!!
        assertEquals("siteA|lzm3u8", l.key)
        assertEquals("123", l.vodId)
        assertEquals(3, l.episodes.size)
        assertNull(ColdResume.buildLine(ref(), vod.copy(flags = listOf(PlayFlag("other", eps("第01集")))), "繁花"))
        assertNull(ColdResume.buildLine(ref(), vod.copy(flags = emptyList()), "繁花"))
        // id 被站点复用给了别的片
        assertNull(ColdResume.buildLine(ref(), vod.copy(name = "完全不同的另一部"), "繁花"))
        assertNull(ColdResume.buildLine(ref(), null, "繁花"))
    }

    @Test fun startMsOnlyForSameEpisode() {
        val p = prog(7, "第07集", 600)
        assertEquals(600_000L, ColdResume.startMs(p, 7, "第07集", "繁花"))
        assertEquals(600_000L, ColdResume.startMs(p, 7, null, "繁花"))
        // 选了别的集：从头，不能拿第 7 集的位置
        assertEquals(0L, ColdResume.startMs(p, 8, "第08集", "繁花"))
        assertEquals(0L, ColdResume.startMs(p, 8, null, "繁花"))
        // 看完了的也从头
        assertEquals(0L, ColdResume.startMs(prog(7, "第07集", 2990), 7, "第07集", "繁花"))
        assertEquals(0L, ColdResume.startMs(null, 7, null, "繁花"))
    }

    @Test fun startMsNamedProgressDoesNotMatchByPosition() {
        // 综艺记录里的 ep 是线路里的位置：选的集只有集数、没有名字时不能拿位置去比
        val p = prog(5, "第3期", 600, named = true)
        assertEquals(0L, ColdResume.startMs(p, 5, null, "奔跑吧"))
        assertEquals(600_000L, ColdResume.startMs(p, 1, "第3期", "奔跑吧"))
        // 老记录（没有 epName）：按集数
        assertEquals(600_000L, ColdResume.startMs(Progress("2", 7, 600, 3000, 0), 7, "第07集", "繁花"))
        assertEquals(0L, ColdResume.startMs(Progress("2", 7, 600, 3000, 0), 8, "第08集", "繁花"))
    }

    @Test fun singleFileShortcutOnlyForFilms() {
        // 电影：每条线路一个文件，名字各写各的
        assertEquals(1, ColdResume.indexIn(eps("The.Wandering.Earth.mp4"), 1, "BD国英双语", 1, movie))
        // 剧集：线路里只有下载好的第 5 集，要放第 1 集不能当成它
        assertNull(ColdResume.indexIn(eps("第05集"), 1, null, 1, drama))
        assertNull(ColdResume.indexIn(eps("第05集"), 1, "第01集", 1, drama))
        assertEquals(1, ColdResume.indexIn(eps("第05集"), 5, null, 1, drama))
        assertEquals(1, ColdResume.indexIn(eps("第05集"), 5, "第05集", 1, drama))
        // 只有一集的 tv 条目（单集纪录片）不走「单文件」捷径：名字明确是别的集就对不上
        val one = Title("4", "某纪录片", 2020, "", Kind.DOC, emptyList(), null, dtype = "tv", totalEps = 1, airedEps = 1)
        assertNull(ColdResume.indexIn(eps("第05集"), 1, "第01集", 1, one))
        // 但名字对得上还是能放
        assertEquals(1, ColdResume.indexIn(eps("第01集"), 1, "第1集", 1, one))
    }

    @Test fun singleVarietyIssueIsNotAnyIssue() {
        val show = Title("5", "奔跑吧", 2024, "", Kind.VARIETY, emptyList(), null, dtype = "tv", totalEps = 1, airedEps = 1)
        // 线路里只有一期、名字认不出数字：不能当成要放的那一期
        assertNull(ColdResume.indexIn(eps("0412 上"), 1, null, 1, show))
        assertNull(ColdResume.indexIn(eps("第3期"), 1, "第1期", 1, show))
        // 综艺不是 tv 条目（dtype 空）也不当电影
        val show2 = show.copy(dtype = "")
        assertNull(ColdResume.indexIn(eps("0412 上"), 1, null, 1, show2))
    }

    @Test fun saveUsesConfigThatProducedTheLine() {
        val l = line("123", "第07集")
        val old = "http://example.com/cfg.json"
        val now = "http://other.example.com/cfg.json"
        // 线路是老配置找的，现在全局已经换了：线索按老配置存，不能按现在的存
        val r = ColdResume.refFor(old, l, "第07集", 1, "local")!!
        assertEquals(ColdResume.configId(old), r.cfg)
        assertNotEquals(ColdResume.configId(now), r.cfg)
        // 换了片源以后用新配置取就对不上
        assertFalse(ColdResume.usable(r, now, listOf("siteA"), emptyList(), "第07集", 7, "繁花"))
        assertTrue(ColdResume.usable(r, old, listOf("siteA"), emptyList(), "第07集", 7, "繁花"))
        // 本地播放没有配置：不存
        assertNull(ColdResume.refFor(null, l, "第07集", 1, "local"))
    }

    @Test fun nextEpisodeLookupUsesNameAndNumber() {
        val line = eps("第06集", "第07集", "第07集彩蛋", "第08集")
        assertEquals(4, ColdResume.indexIn(line, 8, "第08集", line.size, drama))
        // 位置提示错了（别的线路按 +1 凑的），按名字纠正
        assertEquals(4, ColdResume.indexIn(line, 8 + 1, "第08集", line.size, drama))
        // 线路里没有下一集
        assertNull(ColdResume.indexIn(eps("第06集", "第07集"), 8, "第08集", 2, drama))
    }
}
