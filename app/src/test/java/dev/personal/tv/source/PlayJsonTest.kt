package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayJsonTest {

    private fun error(text: String, fallback: String = "aGVsbG8="): String? =
        runCatching { VodJson.play(text, fallback) }.exceptionOrNull()?.let { (it as PluginError).message }

    @Test fun pluginMessageIsShownInsteadOfPlayingTheId() {
        assertEquals(
            "还没有配置夸克 Cookie，请先去配置中心登录夸克",
            error("""{"msg":"还没有配置夸克 Cookie，请先去配置中心登录夸克","list":[],"parse":0}"""),
        )
    }

    @Test fun emptyUrlWithoutMessage() {
        assertEquals("插件没给播放地址", error("""{"url":"","parse":0}"""))
        assertEquals("插件没给播放地址", error("""{"parse":1}""", fallback = "a|||b"))
    }

    @Test fun parseFallsBackToWebPageId() {
        val p = VodJson.play("""{"parse":1,"url":""}""", "https://v.qq.com/x/1.html")
        assertEquals("https://v.qq.com/x/1.html", p.url)
        assertTrue(p.parse)
    }

    @Test fun normalUrlAndStringHeader() {
        val p = VodJson.play("""{"url":"http://x/a.m3u8","msg":"ok","header":"{\"Referer\":\"http://r\"}"}""", "id")
        assertEquals("http://x/a.m3u8", p.url)
        assertEquals("http://r", p.headers["Referer"])
    }

    @Test fun multiQualityArrayTakesFirstUrl() {
        val p = VodJson.play("""{"url":["4K","http://uhd","原画","http://uhd","坏项",null,"1080P","http://hd","720P","http://sd","无地址"]}""", "id")
        assertEquals("http://uhd", p.url)
        assertEquals(listOf("http://hd", "http://sd"), p.alternatives)
    }

    @Test fun nonJsonKeepsOldBehaviour() {
        assertEquals("https://x/1", VodJson.play("not json", "https://x/1").url)
    }
}
