package dev.personal.tv.source

import android.content.Context
import java.io.File

/**
 * 局域网访问本地代理用的口令（地址里的一段路径）。界面进程生成、插件进程读，存在 filesDir/lan-token。
 * 手机扫码打开插件配置中心时要带上它，局域网里别人猜不到地址就碰不到插件的网盘 Cookie。
 */
object LanToken {
    @Volatile private var cached: String? = null

    fun get(ctx: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val f = File(ctx.applicationContext.filesDir, "lan-token")
            val t = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 8 }
                ?: (1..12).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("").also { n ->
                    runCatching { File(f.parentFile, "lan-token.tmp").apply { writeText(n) }.renameTo(f) }
                }
            cached = t
            return t
        }
    }
}
