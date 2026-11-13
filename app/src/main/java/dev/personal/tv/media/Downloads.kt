package dev.personal.tv.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Title
import dev.personal.tv.data.gson
import dev.personal.tv.source.PlayLine
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.screens.PlaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * 手机离线下载（飞机上看）。
 * 选好线路和集数 → 先让站点 / 插件给出播放地址（跟播放时一样解析）→ 交给 Media3 的 DownloadManager 在后台下，
 * 存进 [MediaCache.downloads]。播放时这一集下好了就直接放本地的，不用联网、不用找线路。
 * 地址过期（下到一半 403）会自动重新解析一次。
 */
@OptIn(UnstableApi::class)
object Downloads {
    private const val TAG = "Downloads"
    const val CHANNEL = "downloads"

    /** 每一集下载带着的信息（存在 DownloadRequest.data 里） */
    data class Meta(
        val titleId: String,
        val titleName: String,
        /** 在这条线路里是第几个（1 开始） */
        val epIdx: Int,
        val epName: String,
        val epUrl: String,
        val siteKey: String,
        val flag: String,
        val lineName: String,
        val headers: Map<String, String>,
        val format: String?,
        val title: Title? = null,
    ) {
        val lineKey get() = "$siteKey|$flag"
    }

    /** 列表里的一项：已经交给下载器的，或者还在解析地址的（[download] = null） */
    class Item(val id: String, val meta: Meta, val download: Download?, val note: String? = null) {
        val state get() = download?.state ?: -1
        val done get() = state == Download.STATE_COMPLETED
        val percent get() = download?.percentDownloaded?.takeIf { it >= 0 } ?: 0f
        val bytes get() = download?.bytesDownloaded ?: 0L
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val executor = Executors.newFixedThreadPool(3)
    private var inited = false

    /** 交给下载器的，按加入顺序 */
    var downloads by mutableStateOf<List<Download>>(emptyList())
        private set
    /** 还在解析地址 / 解析失败的 */
    val pending = mutableStateListOf<Item>()

    val manager: DownloadManager by lazy {
        inited = true
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW))
        }
        DownloadManager(app, androidx.media3.exoplayer.offline.DefaultDownloadIndex(MediaCache.database), HeaderFactory).apply {
            maxParallelDownloads = 2
            requirements = requirementsNow()
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(m: DownloadManager) = refresh()
                override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) {
                    refresh()
                    if (d.state == Download.STATE_FAILED) autoRetry(d)
                }
                override fun onDownloadRemoved(m: DownloadManager, d: Download) = refresh()
                override fun onRequirementsStateChanged(m: DownloadManager, r: Requirements, notMet: Int) = refresh()
            })
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** 第一次用（打开下载页、点下载、播放时查本地）才建，电视上不用下载就不碰 */
    private var ticking = false

    private fun ensure() {
        manager
        if (ticking) return
        ticking = true
        refresh()
        // 下载中每秒刷新一下进度
        scope.launch {
            while (true) {
                delay(1000)
                if (downloads.any { it.state == Download.STATE_DOWNLOADING }) refresh()
            }
        }
    }

    fun requirementsNow() = Requirements(if (Prefs.dlMetered) Requirements.NETWORK else Requirements.NETWORK_UNMETERED)

    /** 设置里改了「用流量也下载」 */
    fun applyRequirements() {
        if (!inited) return
        DownloadService.sendSetRequirements(app, DlService::class.java, requirementsNow(), false)
    }

    /** 下载器有没有因为网络要求停着（只连了流量） */
    val waitingForWifi get() = inited && manager.notMetRequirements != 0

    private fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    manager.downloadIndex.getDownloads().use { c ->
                        buildList { while (c.moveToNext()) add(c.download) }
                    }
                }.getOrDefault(emptyList())
            }
            downloads = list.sortedBy { it.startTimeMs }
            // 片库里没有的（很久以前存的、被清掉了）补回去，离线时也能打开播放页
            list.forEach { d -> meta(d.request)?.title?.let { t -> if (Catalog.get(t.id) == null) Catalog.putSource(listOf(t)) } }
        }
    }

    fun meta(r: DownloadRequest): Meta? = runCatching { gson.fromJson(String(r.data), Meta::class.java) }.getOrNull()

    /** 全部（下载中 + 解析中），按片分组用 */
    fun all(): List<Item> {
        ensure()
        return downloads.mapNotNull { d -> meta(d.request)?.let { Item(d.request.id, it, d) } } + pending
    }

    /** 这部片已经下好的集（播放时用；在 IO 线程查，不依赖界面刷新） */
    suspend fun completed(titleId: String): List<Pair<Meta, Download>> = withContext(Dispatchers.IO) {
        ensureOnMain()
        runCatching {
            manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { c ->
                buildList { while (c.moveToNext()) { val d = c.download; meta(d.request)?.takeIf { it.titleId == titleId }?.let { add(it to d) } } }
            }
        }.getOrDefault(emptyList()).sortedBy { it.first.epIdx }
    }

    private suspend fun ensureOnMain() = withContext(Dispatchers.Main) { ensure() }

    fun idOf(titleId: String, lineKey: String, epIdx: Int) = "$titleId|$lineKey|$epIdx"

    /** 这一集的状态（选集面板上打标记） */
    fun stateOf(titleId: String, lineKey: String, epIdx: Int): Item? {
        val id = idOf(titleId, lineKey, epIdx)
        return all().firstOrNull { it.id == id }
    }

    private val resolveLock = Mutex()

    /** 加一批：一集一集解析地址，解析好就交给下载器 */
    fun add(t: Title, line: PlayLine, eps: List<Int>) {
        ensure()
        val todo = eps.filter { i ->
            val s = stateOf(t.id, line.key, i)
            s == null || s.download == null && s.note?.startsWith("解析失败") == true || s.state == Download.STATE_FAILED
        }
        for (i in todo) {
            val e = line.episodes.getOrNull(i - 1) ?: continue
            val m = Meta(t.id, t.name, i, e.name, e.url, line.siteKey, line.flag, "${line.siteName} · ${line.flag}", emptyMap(), null, t)
            pending.removeAll { it.id == idOf(t.id, line.key, i) }
            pending += Item(idOf(t.id, line.key, i), m, null, "等待解析")
        }
        scope.launch { resolveLock.withLock { drain() } }
    }

    /** 失败的重新解析一次 */
    fun retry(it: Item) {
        ensure()
        val m = it.meta
        if (it.download != null) DownloadService.sendRemoveDownload(app, DlService::class.java, it.id, false)
        pending.removeAll { p -> p.id == it.id }
        pending += Item(it.id, m, null, "等待解析")
        scope.launch { resolveLock.withLock { drain() } }
    }

    private val retried = HashSet<String>()

    /** 下载失败（多半是地址过期）：自动重新解析一次 */
    private fun autoRetry(d: Download) {
        val id = d.request.id
        if (!retried.add(id)) return
        val m = meta(d.request) ?: return
        Log.i(TAG, "retry $id")
        retry(Item(id, m, d))
    }

    private suspend fun drain() {
        while (true) {
            val i = pending.indexOfFirst { it.note == "等待解析" }
            if (i < 0) return
            val it = pending[i]
            pending[i] = Item(it.id, it.meta, null, "正在解析地址…")
            val m = it.meta
            val r = runCatching {
                val c = Sources.config()
                withTimeoutOrNull(60_000) { PlaySession.resolveInfo(app, c, m.siteKey, m.flag, m.epUrl) }
            }
            val info = r.getOrNull()
            val idx = pending.indexOfFirst { p -> p.id == it.id }
            if (idx < 0) continue // 解析时被删了
            if (info == null) {
                pending[idx] = Item(it.id, m, null, "解析失败：" + (r.exceptionOrNull()?.message?.take(40) ?: "站点没给出地址"))
                continue
            }
            val fmt = withContext(Dispatchers.IO) { PlaySession.formatOf(info) }
            if (fmt == "html") {
                pending[idx] = Item(it.id, m, null, "解析失败：这条线路只有网页，换一条线路下")
                continue
            }
            val meta = m.copy(headers = info.headers, format = fmt)
            val req = DownloadRequest.Builder(it.id, Uri.parse(info.url))
                .setMimeType(when (fmt) { "hls" -> MimeTypes.APPLICATION_M3U8; "dash" -> MimeTypes.APPLICATION_MPD; else -> null })
                .setData(gson.toJson(meta).toByteArray())
                .build()
            DownloadService.sendAddDownload(app, DlService::class.java, req, false)
            pending.removeAt(idx)
            delay(300)
            refresh()
        }
    }

    fun remove(it: Item) {
        pending.removeAll { p -> p.id == it.id }
        if (it.download != null) DownloadService.sendRemoveDownload(app, DlService::class.java, it.id, false)
    }

    fun removeTitle(titleId: String) = all().filter { it.meta.titleId == titleId }.forEach { remove(it) }

    fun pause(it: Item) = DownloadService.sendSetStopReason(app, DlService::class.java, it.id, 1, false)
    fun resume(it: Item) = DownloadService.sendSetStopReason(app, DlService::class.java, it.id, Download.STOP_REASON_NONE, false)

    /** 每一集用自己的请求头（Referer、UA）下载 */
    private object HeaderFactory : DownloaderFactory {
        override fun createDownloader(r: DownloadRequest): Downloader {
            val h = meta(r)?.headers.orEmpty()
            return DefaultDownloaderFactory(MediaCache.factory(h, MediaCache.downloads, background = true), executor).createDownloader(r)
        }
    }

    fun notification(ctx: Context, list: List<Download>, notMet: Int): Notification {
        val running = list.count { it.state == Download.STATE_DOWNLOADING }
        val msg = when {
            notMet != 0 -> "等 Wi‑Fi"
            running > 0 -> "正在下载 $running 集"
            else -> null
        }
        return DownloadNotificationHelper(ctx, CHANNEL)
            .buildProgressNotification(ctx, android.R.drawable.stat_sys_download, null, msg, list, notMet)
    }
}

/** 后台下载服务：退到后台、锁屏也接着下，通知栏显示进度 */
@OptIn(UnstableApi::class)
class DlService : DownloadService(1001, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL) {
    override fun getDownloadManager(): DownloadManager = Downloads.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        Downloads.notification(this, downloads, notMetRequirements)
}
