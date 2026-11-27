package dev.personal.tv.spider

import android.util.Log
import android.widget.Toast
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * 插件进程里的 Toast 静音：后台搜线路时，网盘插件没登录会一直弹「请先登录」「此资源失效」，满屏跳。
 * 做法是把 Toast 用的系统通知服务换成一层代理，弹 Toast 的调用不弹，文字转给界面进程（[dev.personal.tv.source.PluginNotes]），
 * 跟登录有关的、正在播放时的在那边照样提示。
 * 只有打开插件自带的设置页时（扫码登录、清 Cookie 要看插件的提示）才放行。
 * Toast 的这两个成员是隐藏接口，Android 9+ 要先用 HiddenApiBypass 放开；换不了就什么都不做，跟原来一样。
 */
object QuietToasts {
    private const val TAG = "QuietToasts"

    /** 插件设置页在前台：放行 */
    @Volatile var allow = false

    fun install(ctx: android.content.Context) {
        val app = ctx.applicationContext
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/widget/Toast;", "Landroid/app/INotificationManager")
            }
            val get = Toast::class.java.getDeclaredMethod("getService").apply { isAccessible = true }
            val real = get.invoke(null) ?: return
            val field = Toast::class.java.getDeclaredField("sService").apply { isAccessible = true }
            val iface = Class.forName("android.app.INotificationManager")
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                if (!allow && m.name.startsWith("enqueue") && m.name.contains("Toast")) {
                    // 第一个参数是包名，后面的文字是 Toast 内容（只有纯文字 Toast 带）
                    val text = args?.drop(1)?.firstOrNull { it is CharSequence }?.toString()
                    Log.i(TAG, "静音：${text ?: m.name}")
                    // 交给界面进程判断：登录相关的、正在播放时的照样提示（见 PluginNotes）
                    if (!text.isNullOrBlank()) runCatching {
                        app.sendBroadcast(android.content.Intent(dev.personal.tv.source.PluginNotes.ACTION).setPackage(app.packageName).putExtra("text", text))
                    }
                    when (m.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        Double::class.javaPrimitiveType -> 0.0
                        Short::class.javaPrimitiveType -> 0.toShort()
                        Byte::class.javaPrimitiveType -> 0.toByte()
                        Char::class.javaPrimitiveType -> '\u0000'
                        else -> null
                    }
                } else try {
                    m.invoke(real, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            field.set(null, proxy)
            Log.i(TAG, "插件 Toast 已静音")
        }.onFailure { Log.w(TAG, "装不上，插件 Toast 照常弹", it) }
    }
}
