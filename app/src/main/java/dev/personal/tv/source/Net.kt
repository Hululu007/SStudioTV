package dev.personal.tv.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Net {
    const val UA = "okhttp/4.12.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 同步 GET，调用方自己切到 IO 线程。中文域名由 HttpUrl 转成 punycode */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Long = 0): String {
        val req = Request.Builder().url(url.toHttpUrl()).header("User-Agent", UA)
        headers.forEach { (k, v) -> req.header(k, v) }
        val c = if (timeoutSec > 0) client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build() else client
        c.newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} · $url")
            return r.body?.string().orEmpty()
        }
    }

    fun bytes(url: String): ByteArray {
        val req = Request.Builder().url(url.toHttpUrl()).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} · $url")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }
}
