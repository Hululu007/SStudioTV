package dev.personal.tv.source

import android.content.Context
import dev.personal.tv.spider.SpiderClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 插件的配置中心（网盘登录、播放选项）：很多配置的网盘站点要先在这里扫码登录夸克 / UC，不然播放只会回「先去配置中心登录」。
 * 页面由插件自己出，经本地代理 /proxy?do=pan&action=config 访问；手机扫码打开带口令的局域网地址（见 [LocalServer]）。
 */
object PluginPortal {

    sealed interface State {
        data object Loading : State
        data class Ready(val url: String) : State
        data class Missing(val why: String) : State
    }

    /** 有阻塞调用，放 IO 线程。先唤醒插件（配置中心是插件 jar 里的，jar 没加载时代理不认），再确认页面能打开 */
    suspend fun open(ctx: Context): State {
        if (Sources.empty) return State.Missing("还没有片源")
        val config = Sources.config()
        val jars = config.sites.filter { it.kind == SiteKind.JAR }
        val site = jars.firstOrNull { "Config" in it.api } ?: jars.firstOrNull()
            ?: return State.Missing("当前片源没有插件，不需要配置")
        runCatching { (Sites.get(ctx, config, site) as JarSite).wake() }
        val port = SpiderClient.reclaimPort().takeIf { it > 0 } ?: return State.Missing("插件进程没起来")
        val token = LanToken.get(ctx)
        val path = "/$token/proxy?do=pan&action=config"
        val ok = runCatching {
            val c = Net.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute().use { r ->
                r.isSuccessful && r.body?.string().orEmpty().contains("<html", ignoreCase = true)
            }
        }.getOrDefault(false)
        if (!ok) return State.Missing("当前片源的插件没有配置中心")
        val ip = Remote.lanIp() ?: return State.Missing("电视没连上局域网")
        return State.Ready("http://$ip:$port$path")
    }

    /** 播放失败的提示是不是在叫人去登录网盘 / 配置中心 */
    fun wantsLogin(msg: String?): Boolean =
        msg != null && listOf("配置中心", "Cookie", "cookie", "登录", "扫码").any { it in msg }
}
