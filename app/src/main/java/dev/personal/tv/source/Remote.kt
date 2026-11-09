package dev.personal.tv.source

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 手机扫码给电视加片源：在局域网开一个小网页，手机上粘贴配置地址提交。
 * 只在「片源」页打开时运行；地址里带一段随机口令，局域网里别人猜不到。
 */
object Remote {
    private const val TAG = "Remote"
    private var server: Server? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val token = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")

    /** 手机该打开的地址；没联网或没起来是 null */
    var url: String? = null
        private set

    @Synchronized
    fun start(): String? {
        server?.let { return url }
        val ip = lanIp() ?: return null
        for (port in 9966..9976) {
            val s = Server(port)
            if (runCatching { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }.isSuccess) {
                server = s
                url = "http://$ip:$port/$token"
                Log.i(TAG, "listening $url")
                return url
            }
        }
        return null
    }

    @Synchronized
    fun stop() {
        server?.stop(); server = null; url = null
    }

    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private class Server(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri.trimEnd('/') != "/$token") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            var msg: String? = null
            if (session.method == Method.POST) {
                val body = HashMap<String, String>()
                runCatching { session.parseBody(body) }
                val p = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
                val u = p["url"].orEmpty().trim()
                msg = if (u.isEmpty()) "地址是空的" else runBlocking {
                    val job = scope.async { runCatching { Sources.add(p["name"], u) } }
                    when (val r = withTimeoutOrNull(40_000) { job.await() }) {
                        null -> "超时：电视 40 秒内没下载到这个配置"
                        else -> r.fold(
                            { list -> "✓ 已添加 ${list.size} 个片源：" + list.joinToString("、") { it.name } },
                            { e -> "✗ 加不上：${e.message?.take(120)}" },
                        )
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(msg))
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(msg: String?): String {
        val items = Sources.list.joinToString("") { e ->
            "<li><b>${esc(e.name)}</b>${if (e.url == Sources.url) " <em>当前</em>" else ""}<br><small>${esc(e.url)}</small></li>"
        }
        return """<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SStudioTV · 添加片源</title><style>
body{font:16px/1.5 -apple-system,system-ui,sans-serif;margin:0;padding:24px;background:#0b0d10;color:#eee}
h1{font-size:20px;margin:0 0 4px}p{color:#9aa}input{box-sizing:border-box;width:100%;font-size:16px;padding:12px;margin:6px 0 14px;border-radius:10px;border:1px solid #333;background:#16191e;color:#fff}
button{width:100%;font-size:17px;padding:13px;border:0;border-radius:10px;background:#fff;color:#000;font-weight:600}
.msg{padding:12px;border-radius:10px;background:#1c2a20;margin:14px 0}ul{padding-left:18px}small{color:#889;word-break:break-all}em{color:#3c9;font-style:normal;font-size:13px}
</style></head><body>
<h1>给电视加片源</h1><p>粘贴影视仓 / TVBox 配置地址（JSON、加密配置、多仓地址都行），电视会先下载验证。</p>
${msg?.let { "<div class=msg>${esc(it)}</div>" } ?: ""}
<form method="post"><label>配置地址<input name="url" type="url" inputmode="url" placeholder="http://…" required autofocus></label>
<label>名字（可不填）<input name="name" placeholder="比如：家里的源"></label><button>添加到电视</button></form>
<h1 style="margin-top:28px;font-size:16px">电视上已有</h1><ul>${items.ifEmpty { "<p>还没有</p>" }}</ul>
</body></html>"""
    }
}
