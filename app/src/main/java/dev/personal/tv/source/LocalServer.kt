package dev.personal.tv.source

import android.util.Log
import com.github.catvod.crawler.SpiderApi
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 本地代理 http://127.0.0.1:9978。
 * 插件给出的播放地址经常是 127.0.0.1:9978/proxy?do=xxx&…，由这里转回插件自己处理（B 站合流、网盘取直链等）。
 * 只监听本机回环地址，局域网里别的设备访问不到。
 */
class LocalServer private constructor(port: Int) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
        return when (session.uri) {
            "/proxy" -> proxy(params)
            "/" -> text("ok")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun proxy(params: Map<String, String>): Response {
        val r = runCatching { JarLoader.proxy(params) }
            .onFailure { Log.w(TAG, "proxy ${params["do"]}", it) }
            .getOrNull()
        if (r == null || r.size < 3) {
            // 插件探测端口用的是 do=ck，没加载 jar 时也要答应
            return if (params["do"] == "ck") text("ok") else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }
        val code = (r[0] as? Int) ?: 200
        val mime = (r[1] as? String) ?: "application/octet-stream"
        val body: InputStream = when (val b = r[2]) {
            is InputStream -> b
            is ByteArray -> ByteArrayInputStream(b)
            null -> ByteArrayInputStream(ByteArray(0))
            else -> ByteArrayInputStream(b.toString().toByteArray())
        }
        val status = Response.Status.lookup(code) ?: Response.Status.OK
        val resp = newChunkedResponse(status, mime, body)
        @Suppress("UNCHECKED_CAST")
        (r.getOrNull(3) as? Map<String, String>)?.forEach { (k, v) -> resp.addHeader(k, v) }
        return resp
    }

    private fun text(s: String) = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, s)

    companion object {
        private const val TAG = "LocalServer"
        private const val PRIMARY = 9978
        /** 已经发出去的地址可能带着旧端口，所以开过的端口都留着继续服务 */
        private val servers = java.util.concurrent.CopyOnWriteArrayList<LocalServer>()

        private fun tryStart(port: Int): Boolean {
            val s = LocalServer(port)
            if (runCatching { s.start(SOCKET_READ_TIMEOUT, true) }.isFailure) return false
            servers.add(s)
            Log.i(TAG, "listening on 127.0.0.1:$port")
            return true
        }

        @Synchronized
        fun start() {
            if (servers.isEmpty()) {
                if (claimPrimary()) return
                for (port in PRIMARY + 1..PRIMARY + 20) if (tryStart(port)) { SpiderApi.port = port; return }
                Log.w(TAG, "no free port in 9978..9998")
            } else claimPrimary()
        }

        /**
         * 退到后台时让出 9978，别的端口留着。
         * 插件从 9978 往上找第一个应答的端口；别的影视 App 在前台时要用 9978，我们被冻结在后台占着不应答会把它卡住。
         */
        @Synchronized
        fun releasePrimary() {
            servers.filter { it.listeningPort == PRIMARY }.forEach { s ->
                runCatching { s.stop() }
                servers.remove(s)
                Log.i(TAG, "released $PRIMARY (background)")
            }
        }

        /** 拿 9978：空着就开，被别的 App 占着就算了（用后面的端口） */
        @Synchronized
        fun claimPrimary(): Boolean {
            if (servers.any { it.listeningPort == PRIMARY && it.isAlive }) return true
            if (!tryStart(PRIMARY)) return false
            SpiderApi.port = PRIMARY
            return true
        }
    }
}
