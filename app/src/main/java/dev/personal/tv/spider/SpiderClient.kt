package dev.personal.tv.spider

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 界面进程这一侧：绑定 :spider 进程，把调用转过去。全部是阻塞调用，别在主线程用。
 */
object SpiderClient {
    private const val TAG = "SpiderClient"

    @Volatile private var host: ISpiderHost? = null
    @Volatile private var latch = CountDownLatch(1)
    private var bound = false
    private lateinit var app: Context

    /** 插件进程退出时通知（源调试页显示原因） */
    @Volatile var onDeath: ((String) -> Unit)? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder) {
            host = ISpiderHost.Stub.asInterface(b)
            b.linkToDeath({
                host = null
                latch = CountDownLatch(1)
                Thread.sleep(500) // 退出原因要等系统记下来
                val why = exitReason()
                Log.w(TAG, "spider process died: $why")
                onDeath?.invoke(why)
                rebind()
            }, 0)
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            host = null
            latch = CountDownLatch(1)
        }

        /** 系统放弃了这个绑定（进程反复崩溃）：不会再自己连回来，要重新绑 */
        override fun onBindingDied(name: ComponentName?) {
            host = null
            latch = CountDownLatch(1)
            Thread { rebind() }.start()
        }
    }

    /**
     * 插件进程死了马上重新绑定。只等系统自己重启不行：短时间里崩两次（插件原生崩溃），系统会把重启推到半小时以后，
     * 这期间所有插件调用都是「插件进程没起来」。解绑会撤掉那个排队的重启，再绑就是立刻起一个新进程。
     */
    @Synchronized
    private fun rebind() {
        if (!bound) return
        runCatching { app.unbindService(conn) }
        bound = false
        bind(app)
    }

    /** App 启动时先拉起插件进程（本地代理也随之起来） */
    @Synchronized
    fun bind(ctx: Context) {
        app = ctx.applicationContext
        if (bound) return
        bound = app.bindService(Intent(app, SpiderService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun host(): ISpiderHost {
        host?.let { return it }
        if (!bound) bind(app)
        // 插件进程刚被杀时，系统重新拉起要一两秒；等不到就重新绑一次再等
        if (!latch.await(10, TimeUnit.SECONDS)) {
            rebind()
            if (!latch.await(10, TimeUnit.SECONDS)) error("插件进程没起来")
        }
        return host ?: error("插件进程没起来")
    }

    fun call(jar: String, md5: String?, key: String, api: String, ext: String, method: String, vararg args: String): String {
        val r = try {
            host().call(jar, md5.orEmpty(), key, api, ext, method, args.toList())
        } catch (e: DeadObjectException) {
            host = null
            error("插件进程退出了（插件崩溃或自行结束）")
        }
        return when (r.firstOrNull()) {
            '0' -> r.substring(1)
            'F' -> File(r.substring(1)).let { f -> f.readText().also { f.delete() } }
            else -> error(r.drop(1))
        }
    }

    /** 界面回到前台时调用（IO 线程）：本地代理把 9978 拿回来 */
    fun reclaimPort() = runCatching { host().port() }.getOrDefault(0)

    /** 界面退到后台时调用（IO 线程） */
    fun releasePort() { runCatching { host().releasePort() } }

    /** Android 11+ 能查到上一次进程退出的原因：被自己杀、崩溃、ANR… */
    private fun exitReason(): String {
        if (Build.VERSION.SDK_INT < 30) return "插件进程退出"
        val am = app.getSystemService(ActivityManager::class.java)
        val info = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 5) }.getOrNull()
            ?.firstOrNull { it.processName.endsWith(":spider") } ?: return "插件进程退出"
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_SIGNALED -> "被结束（signal ${info.status}，多半是插件自己 killProcess）"
            ApplicationExitInfo.REASON_CRASH -> "崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
            ApplicationExitInfo.REASON_ANR -> "无响应"
            ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被回收"
            else -> "退出（${info.reason}）"
        }
        return "插件进程$reason"
    }
}
