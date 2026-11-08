package dev.personal.tv.source

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest

/**
 * 加载配置里的 spider jar（其实是 dex）。
 *
 * 注意：这一步会在本机执行下载来的第三方代码，它有和本 App 一样的权限（联网、读写 App 私有目录）。
 * 用户已明确接受这个风险；这里只做最基本的约束：md5 对不上就不加载，文件设为只读（Android 14 要求）。
 */
object JarLoader {
    private const val TAG = "JarLoader"

    private class Loaded(val loader: DexClassLoader, val spiders: java.util.concurrent.ConcurrentHashMap<String, Spider> = java.util.concurrent.ConcurrentHashMap())

    private val jars = java.util.concurrent.ConcurrentHashMap<String, Loaded>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun <T> locked(key: String, block: () -> T): T = synchronized(locks.getOrPut(key) { Any() }, block)

    /** 最近一次用到的 jar：本地代理 /proxy 交给它处理 */
    @Volatile
    var recent: ClassLoader? = null
        private set

    /** jar 按地址加锁、插件按站点加锁，互不阻塞。只在插件进程（:spider）里调用 */
    fun spider(ctx: Context, url: String, md5: String?, key: String, api: String, ext: String): Spider {
        val app = ctx.applicationContext
        val loaded = jars[url] ?: locked("jar:$url") { jars.getOrPut(url) { load(app, url, md5) } }
        recent = loaded.loader
        loaded.spiders[key]?.let { return it }
        return locked("site:$url#$key") { loaded.spiders.getOrPut(key) {
            val cls = "com.github.catvod.spider." + api.removePrefix("csp_")
            val sp = loaded.loader.loadClass(cls).getDeclaredConstructor().newInstance() as Spider
            sp.siteKey = key
            val t = System.currentTimeMillis()
            sp.init(app, ext)
            Log.i(TAG, "init $api ($key) ${System.currentTimeMillis() - t}ms")
            sp
        } }
    }

    private fun load(app: Context, url: String, md5: String?): Loaded {
        val dir = File(app.cacheDir, "jar").apply { mkdirs() }
        val name = md5 ?: md5Hex(url.toByteArray())
        val file = File(dir, "$name.jar")
        if (!file.exists() || (md5 != null && md5Hex(file.readBytes()) != md5)) {
            file.delete()
            val bytes = extractJar(Net.bytes(url))
            if (md5 != null) {
                val got = md5Hex(bytes)
                if (!got.equals(md5, ignoreCase = true)) error("jar md5 不符：期望 $md5，实际 $got")
            }
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            Log.i(TAG, "downloaded ${bytes.size} bytes from $url")
        }
        file.setReadOnly()
        val opt = File(app.codeCacheDir, "jar").apply { mkdirs() }
        val loader = DexClassLoader(file.absolutePath, opt.absolutePath, null, app.classLoader)
        // 插件的全局初始化（有的会起线程、读 assets）
        runCatching {
            loader.loadClass("com.github.catvod.spider.Init").getMethod("init", Context::class.java).invoke(null, app)
        }.onFailure { Log.w(TAG, "Init.init failed", it) }
        return Loaded(loader)
    }

    /** 有的 jar 藏在图片后面：找到 zip 头 PK\x03\x04 从那里截取 */
    private fun extractJar(b: ByteArray): ByteArray {
        for (i in 0 until b.size - 3) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 3.toByte() && b[i + 3] == 4.toByte()) {
                return if (i == 0) b else b.copyOfRange(i, b.size)
            }
        }
        error("下载到的不是 jar（找不到 zip 头）")
    }

    /** 本地代理请求：调用 jar 里的 com.github.catvod.spider.Proxy.proxy(Map) */
    fun proxy(params: Map<String, String>): Array<Any?>? {
        val loader = recent ?: return null
        val m = loader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, params) as Array<Any?>?
    }

    private fun md5Hex(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}
