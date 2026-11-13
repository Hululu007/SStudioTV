package dev.personal.tv.ui.phone

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 用电视界面还是手机界面。同一个包：电视（Android TV / 没触摸屏的盒子）用遥控器界面，手机、平板用触屏界面。
 * 「我的 → 界面」可以手动改（平板接了遥控器、盒子误报有触摸屏的时候用）。
 */
object UiMode {
    enum class Choice(val label: String) { AUTO("自动"), PHONE("手机界面"), TV("电视界面") }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun choice(ctx: Context): Choice =
        runCatching { Choice.valueOf(prefs(ctx).getString("ui", null) ?: "AUTO") }.getOrDefault(Choice.AUTO)

    fun set(ctx: Context, c: Choice) = prefs(ctx).edit().putString("ui", c.name).apply()

    /** 自动判断：是不是电视 */
    fun looksLikeTv(ctx: Context): Boolean {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun phone(ctx: Context): Boolean = when (choice(ctx)) {
        Choice.PHONE -> true
        Choice.TV -> false
        Choice.AUTO -> !looksLikeTv(ctx)
    }

    /** 浏览时的方向：手机竖屏；平板随用户转 */
    fun browseOrientation(ctx: Context): Int =
        if (ctx.resources.configuration.smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
