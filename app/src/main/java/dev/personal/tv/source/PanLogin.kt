package dev.personal.tv.source

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject

/**
 * 网盘登录：插件配置中心里各家网盘的登录状态和扫码登录，按网盘分开列。
 * 能扫码的（夸克、UC、百度、115、天翼、光鸭、哔哩）在电视上直接出那家的登录二维码，用那家的 App 扫；
 * 要输手机号 / 密码的（迅雷、移动、123）让手机扫码打开配置中心的对应一栏。
 */
object PanLogin {

    /**
     * @param qr 扫码登录的 provider（loginStart 用），null = 不能扫码
     * @param clear 退出登录的接口
     * @param page 配置中心页面里的分类，手机打开时直接跳到这一栏
     */
    class Pan(
        val key: String, val name: String, val qr: String?, val clear: String, val page: String, val how: String,
        private val flag: String, private val text: String,
    ) {
        fun on(s: JsonObject?): Boolean = s?.get(flag)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        /** 插件给的状态文字：已登录时照用（可能是「有效」「已过期」之类），没登录时统一叫「未登录」，失效了单说 */
        fun status(s: JsonObject?): String {
            val t = s?.get(text)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            return when {
                s == null -> ""
                "失效" in t || "过期" in t -> t
                on(s) -> t.takeIf { it.isNotBlank() && it !in NOT_SET && it != "已配置" } ?: "已登录"
                else -> "未登录"
            }
        }
        fun expired(s: JsonObject?) = status(s).let { "失效" in it || "过期" in it }
    }

    private val NOT_SET = setOf("未扫码", "未配置", "未登录")

    val all = listOf(
        Pan("quark", "夸克网盘", "quark", "clearQuarkCookie", "quark", "用夸克 App 扫码", "quarkCookie", "quarkLoginText"),
        Pan("ucCookie", "UC 网盘 · Cookie", "ucCookie", "clearUc", "uc", "用 UC 浏览器扫码；UC 会员用这个", "ucCookie", "ucCookieLoginText"),
        Pan("ucToken", "UC 网盘 · TV Token", "ucToken", "clearUc", "uc", "用 UC 浏览器扫码；不是会员用这个", "ucTvToken", "ucTvTokenLoginText"),
        Pan("baidu", "百度网盘", "baidu", "clearBaiduCookie", "baidu", "用百度网盘 App 扫码", "baiduCookie", "baiduLoginText"),
        Pan("pan115", "115 网盘", "pan115", "clearPan115Cookie", "pan115", "用 115 App 扫码", "pan115Cookie", "pan115LoginText"),
        Pan("pan189", "天翼云盘", "pan189", "clearPan189Cookie", "pan189", "用天翼云盘 App 扫码", "pan189Cookie", "pan189CookieText"),
        Pan("thunder", "迅雷云盘", null, "clearThunder", "thunder", "手机号收验证码登录", "thunderLogin", "thunderLoginText"),
        Pan("pan139", "移动云盘", null, "clearPan139", "pan139", "手机号收验证码登录", "pan139Cookie", "pan139CookieText"),
        Pan("pan123", "123 网盘", null, "clearPan123Account", "pan123", "账号密码登录", "pan123Configured", "pan123LoginText"),
        Pan("guangya", "光鸭云盘", "guangya", "clearGuangYaToken", "guangya", "用光鸭 App 扫码", "guangYaToken", "guangYaLoginText"),
        Pan("bili", "哔哩哔哩", "bili", "clearBiliCookie", "bili", "用哔哩哔哩 App 扫码；B 站线路登录后清晰度更高", "biliCookie", "biliLoginText"),
    )

    /** 最近一次读到的状态（设置页显示「已登录：夸克、百度」用），进网盘登录页时刷新 */
    var last by mutableStateOf<JsonObject?>(null)

    /** 已登录的网盘名字（UC 两种算一个） */
    fun loggedIn(s: JsonObject? = last): List<String> =
        all.filter { it.on(s) && !it.expired(s) }.map { it.name.substringBefore(" ·") }.distinct()
}
