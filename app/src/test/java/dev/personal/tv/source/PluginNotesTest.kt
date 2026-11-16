package dev.personal.tv.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNotesTest {
    @Test fun loginTexts() {
        listOf("请先登录夸克网盘", "Cookie 已失效", "扫码登陆", "token过期，请重新授权", "未登录").forEach {
            assertTrue(it, PluginNotes.isLogin(it))
        }
    }

    @Test fun otherTexts() {
        listOf("此资源失效", "解析中...", "暂无播放地址", "关注公众号获取更多").forEach {
            assertFalse(it, PluginNotes.isLogin(it))
        }
    }
}
