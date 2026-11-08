package dev.personal.tv.data

import dev.personal.tv.source.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTest {
    private fun eps(vararg n: String) = n.map { Episode(it, "u/$it") }
    private val show = Title("1", "奔跑吧", 2024, "", Kind.VARIETY, emptyList(), null, totalEps = 12, airedEps = 6)
    private val drama = Title("2", "繁花", 2023, "", Kind.SERIES, emptyList(), null, totalEps = 30, airedEps = 30)
    private fun prog(ep: Int, name: String?, done: Boolean, named: Boolean? = null, next: String? = null) =
        Progress("x", ep, if (done) 3000 else 600, 3000, 0, "s|f", false, name, named, next)

    @Test fun namedShowGoesToNextByName() {
        val line = eps("第1期", "第1期加更", "第2期", "第2期花絮", "第3期", "第4期")
        // 看完「第3期」（线路里第 5 个）：下一个是「第4期」，不是按位置 +1 跳到第 6 期
        val tg = Resume.target(show, prog(5, "第3期", true, named = true), line)!!
        assertEquals("第4期", tg.name)
        assertEquals(false, tg.resume)
        // 跳过花絮
        assertEquals("第3期", Resume.target(show, prog(3, "第2期", true, named = true), line)!!.name)
    }

    @Test fun coldStartUsesSavedNextName() {
        // 线路还没搜（冷启动、换了片源）：用存进度时算好的下一集，不重播同一期
        val tg = Resume.target(show, prog(5, "第3期", true, named = true, next = "第3期加更"), null)!!
        assertEquals("第3期加更", tg.name)
        // 不知道下一集、又是按名字的：交给详情页
        assertNull(Resume.target(show, prog(5, "第3期", true, named = true), null))
    }

    @Test fun unfinishedResumesSameEpisode() {
        val tg = Resume.target(drama, prog(7, "第07集", false), null)!!
        assertEquals(7, tg.ep)
        assertEquals(true, tg.resume)
    }

    @Test fun seriesFallsBackToDoubanCount() {
        assertEquals(8, Resume.target(drama, prog(7, "第07集", true, named = false), null)!!.ep)
        assertNull(Resume.target(drama, prog(30, "第30集", true, named = false), null))
        // 线路里第 8 集前面夹着彩蛋
        val line = eps("第07集", "第07集彩蛋", "第08集")
        val tg = Resume.target(drama, prog(7, "第07集", true, named = false), line)!!
        assertEquals("第08集", tg.name)
        assertEquals(8, tg.ep)
    }
}
