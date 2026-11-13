package dev.personal.tv.media

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.personal.tv.source.Net
import java.io.File

/**
 * 视频的磁盘缓存，只在主进程用。
 * - [play]：播放和预缓存（当前这集后面的部分、下一集）。满了删最久没用的，上限按剩余空间算。
 * - [downloads]：手机离线下载，不自动删，用户在「下载」页删。
 * 后台下载（预缓存、离线下载）走 [priority]：播放器缺缓冲时先停，等它缓冲够了再接着下，不抢网速（[playerHungry]）。
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private lateinit var app: Context
    val priority = PriorityTaskManager()
    private val db by lazy { StandaloneDatabaseProvider(app) }

    val play: SimpleCache by lazy { SimpleCache(File(app.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(playCap()), db) }
    val downloads: SimpleCache by lazy { SimpleCache(downloadDir(), NoOpCacheEvictor(), db) }
    val database get() = db

    fun init(ctx: Context) { app = ctx.applicationContext }

    private val hungry = HashSet<Any>()

    /**
     * 播放器缺缓冲（[on]）时让后台下载停下，缓冲够了再接着下。
     * 不用 ExoPlayer 自带的「加载中就占优先级」：它边播边补缓冲，几乎一直在加载，后台就永远轮不到。
     */
    @Synchronized
    fun playerHungry(who: Any, on: Boolean) {
        if (on) { if (hungry.add(who)) priority.add(C.PRIORITY_PLAYBACK) }
        else if (hungry.remove(who)) priority.remove(C.PRIORITY_PLAYBACK)
    }

    /** 播放缓存上限：剩余空间的三分之一，300 MB ~ 3 GB（电视盒子空间小） */
    private fun playCap(): Long = (app.cacheDir.usableSpace / 3).coerceIn(300L shl 20, 3L shl 30)

    /** 下载放在 App 自己的外部目录（不用存储权限，卸载时一起删）；没有就放内部 */
    private fun downloadDir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads")

    /** 下载占了多少空间 */
    fun downloadBytes(): Long = runCatching { downloads.cacheSpace }.getOrDefault(0L)

    /** 现在是不是按流量计费的网络（手机流量、热点）：这时不预缓存 */
    fun metered(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36"

    fun upstream(headers: Map<String, String>, meter: TransferListener? = null): OkHttpDataSource.Factory {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        return OkHttpDataSource.Factory(Net.client).apply { meter?.let { setTransferListener(it) } }
            .setUserAgent(ua ?: UA)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
    }

    /**
     * 带缓存的数据源。[background] = 后台下载用：播放器缺缓冲时让路（[priority]）。
     * 缓存坏了（写满、文件被删）就直接走网络，不影响播放。
     */
    fun factory(headers: Map<String, String>, cache: SimpleCache, meter: TransferListener? = null, background: Boolean = false): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream(headers, meter))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .apply { if (background) setUpstreamPriorityTaskManager(priority).setUpstreamPriority(C.PRIORITY_DOWNLOAD) }
}
