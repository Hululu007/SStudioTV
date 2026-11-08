package dev.personal.tv.spider

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.personal.tv.source.JarLoader
import dev.personal.tv.source.LocalServer
import com.github.catvod.crawler.SpiderApi
import java.io.File

/**
 * 插件进程（:spider）。第三方 jar 的加载、调用、本地代理都在这里：
 * 插件崩溃、卡死或者自己 killProcess，只会带走这个进程，界面进程不受影响，下一次调用时系统会重新拉起它。
 */
class SpiderService : Service() {

    override fun onCreate() {
        super.onCreate()
        Thread { LocalServer.start() }.start()
        Log.i(TAG, "spider process up, pid=${android.os.Process.myPid()}")
    }

    private val binder = object : ISpiderHost.Stub() {
        override fun call(jar: String, md5: String?, key: String, api: String, ext: String, method: String, args: MutableList<String>?): String {
            val a = args.orEmpty()
            return try {
                val sp = JarLoader.spider(applicationContext, jar, md5?.ifBlank { null }, key, api, ext)
                val r = when (method) {
                    "home" -> sp.homeContent(true)
                    "category" -> sp.categoryContent(a[0], a[1], true, HashMap())
                    "search" -> sp.searchContent(a[0], false)
                    "detail" -> sp.detailContent(listOf(a[0]))
                    "play" -> sp.playerContent(a[0], a[1], a.drop(2))
                    "action" -> sp.action(a[0])
                    "manual" -> sp.manualVideoCheck().toString()
                    "isVideo" -> sp.isVideoFormat(a[0]).toString()
                    else -> error("未知方法 $method")
                }.orEmpty()
                if (r.length > BIG) {
                    val f = File(cacheDir, "spider-out").apply { mkdirs() }.let { File(it, "${System.nanoTime()}.json") }
                    f.writeText(r)
                    "F" + f.absolutePath
                } else "0$r"
            } catch (e: Throwable) {
                val root = generateSequence(e) { it.cause }.last()
                Log.w(TAG, "$method $api", e)
                "1${root.javaClass.simpleName}: ${root.message.orEmpty().take(300)}"
            }
        }

        override fun releasePort() {
            Thread { LocalServer.releasePrimary() }.start()
        }

        override fun port(): Int {
            LocalServer.start() // 回到前台时把 9978 拿回来
            return SpiderApi.port
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "SpiderService"
        /** binder 单次传输上限约 1MB（还和别的调用共享），大结果走文件 */
        private const val BIG = 256 * 1024
    }
}
