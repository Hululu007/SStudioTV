package dev.personal.tv.source

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.personal.tv.spider.SpiderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 插件的配置中心（网盘登录、播放选项）：很多配置的网盘站点要先在这里扫码登录夸克 / UC，不然播放只会回「先去配置中心登录」。
 * 页面由插件自己出，经本地代理 /proxy?do=pan&action=config 访问；手机扫码打开带口令的局域网地址（见 [LocalServer]）。
 * 页面背后是一组接口（?do=pan&action=status / loginStart / loginPoll / clearXxx），网盘登录页（[PanLogin]）直接调它们。
 */
object PluginPortal {

    sealed interface State {
        data object Loading : State
        data class Ready(val url: String) : State
        data class Missing(val why: String) : State
    }

    /** [local]：电视自己调接口用的 127.0.0.1 地址；[lan]：给手机扫的局域网地址，没连局域网时为 null。都到「?do=pan」为止 */
    class Base(val local: String, val lan: String?)

    private val http by lazy { Net.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** 先唤醒插件（配置中心是插件 jar 里的，jar 没加载时代理不认），再确认接口能用 */
    suspend fun base(ctx: Context): Result<Base> = withContext(Dispatchers.IO) { runCatching {
        if (Sources.empty) error("还没有片源")
        val config = Sources.config()
        val jars = config.sites.filter { it.kind == SiteKind.JAR }
        val site = jars.firstOrNull { "Config" in it.api } ?: jars.firstOrNull() ?: error("当前片源没有插件，不需要配置")
        runCatching { (Sites.get(ctx, config, site) as JarSite).wake() }
        val port = SpiderClient.reclaimPort().takeIf { it > 0 } ?: error("插件进程没起来")
        val path = "/${LanToken.get(ctx)}/proxy?do=pan"
        val local = "http://127.0.0.1:$port$path"
        val ok = runCatching { get("$local&action=status").let { it.has("code") } }.getOrDefault(false)
        if (!ok) error("当前片源的插件没有配置中心")
        Base(local, Remote.lanIp()?.let { "http://$it:$port$path" })
    } }

    /** 调配置中心的接口，返回 JSON。阻塞调用 */
    fun api(b: Base, action: String, vararg q: Pair<String, String>): JsonObject {
        val u = "${b.local}&action=$action".toHttpUrl().newBuilder()
        q.forEach { (k, v) -> u.addQueryParameter(k, v) }
        return get(u.build().toString())
    }

    private fun get(url: String): JsonObject =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
        }

    /** 手机扫码打开的配置中心页面；[category] 是页面左边的分类（quark、thunder…），直接打开那一栏 */
    fun pageUrl(b: Base, category: String? = null): String? =
        b.lan?.let { "$it&action=config" + (category?.let { c -> "#$c" } ?: "") }

    /** 播放失败的提示是不是在叫人去登录网盘 / 配置中心 */
    fun wantsLogin(msg: String?): Boolean =
        msg != null && listOf("配置中心", "Cookie", "cookie", "登录", "扫码").any { it in msg }
}
