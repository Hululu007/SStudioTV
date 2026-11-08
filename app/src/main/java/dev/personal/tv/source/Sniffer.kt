package dev.personal.tv.source

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 网页嗅探：后台开一个看不见的 WebView 加载网页，盯着它发出的请求，第一个像视频的地址就是结果。
 * WebView 只用来抓地址，不显示给人看。
 */
object Sniffer {
    private const val TAG = "Sniffer"
    private val main = Handler(Looper.getMainLooper())

    /** 视频地址特征。(?!http) 防止把「解析页?url=http://…m3u8」这种网页本身当成视频 */
    private val media = Regex(
        "http((?!http).){12,}?\\.(m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)\\?.*|http((?!http).){12,}\\.(m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)|http((?!http).)*?video/tos.*|http((?!http).)*?obj/tos.*",
        RegexOption.IGNORE_CASE,
    )
    /** 整个地址里出现脚本/图片扩展名就不算；tos-cn-i 是字节的图片 CDN */
    private val notMedia = Regex("\\.(js|css|png|jpe?g|gif|webp|ico|woff2?|ttf|svg|html?)(\\?|&|$)|tos-cn-i-", RegexOption.IGNORE_CASE)

    fun isMedia(url: String) = media.matches(url) && !notMedia.containsMatchIn(url)

    private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /**
     * @param ads 配置里的广告域名，直接拦掉，页面能快一点
     * @param isVideo 插件自定义的视频判断（manualVideoCheck 的站点），可以为空
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(
        ctx: Context,
        pageUrl: String,
        headers: Map<String, String>,
        ads: List<String>,
        timeoutMs: Long = 20_000,
        isVideo: ((String) -> Boolean)? = null,
    ): PlayInfo? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            var wv: WebView? = null
            fun finish(r: PlayInfo?) {
                if (!done.compareAndSet(false, true)) return
                main.post { wv?.run { stopLoading(); destroy() }; wv = null }
                if (cont.isActive) cont.resume(r)
            }
            cont.invokeOnCancellation { finish(null) }
            main.post {
                if (done.get()) return@post
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA
                val w = WebView(ctx.applicationContext)
                wv = w
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    blockNetworkImage = true
                    userAgentString = ua
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                        val url = req.url.toString()
                        val host = req.url.host.orEmpty()
                        if (ads.any { it.isNotBlank() && (host == it || host.endsWith(".$it")) }) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        if (url != pageUrl && (isMedia(url) || isVideo?.invoke(url) == true)) {
                            val h = LinkedHashMap<String, String>()
                            req.requestHeaders.forEach { (k, v) -> if (!k.equals("Range", true)) h[k] = v }
                            if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = ua
                            if (h.keys.none { it.equals("Referer", true) }) h["Referer"] = view.url ?: pageUrl
                            CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
                            Log.i(TAG, "hit $url  ←  $pageUrl")
                            finish(PlayInfo(url, h, parse = false, jx = false))
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                        req.url.scheme !in setOf("http", "https")

                    // 解析站证书经常不规范；这个 WebView 只用来找视频地址，不提交任何数据
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.proceed()
                }
                w.loadUrl(pageUrl, headers.filterKeys { !it.equals("User-Agent", true) })
            }
        }
    }
}
