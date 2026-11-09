package dev.personal.tv

import android.app.Application
import android.os.Build
import android.webkit.WebView
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Douban
import dev.personal.tv.data.Store
import dev.personal.tv.source.Net
import dev.personal.tv.source.Sources

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        dev.personal.tv.audio.AudioFx.init(this)
        val proc = if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName
        // 两个进程都可能用 WebView（插件、嗅探），Android 9+ 要求各用各的数据目录
        if (Build.VERSION.SDK_INT >= 28 && proc != packageName) {
            runCatching { WebView.setDataDirectorySuffix(proc.substringAfter(':')) }
        }
        // 片库和本地记录只在主进程用，插件进程不加载
        if (proc == packageName) {
            dev.personal.tv.data.Prefs.init(this)
            dev.personal.tv.source.LanToken.get(this) // 插件进程启动前先生成好
            Douban.init(this)
            dev.personal.tv.data.Stills.init(this)
            Store.init(this)
            Catalog.init(this)
            Sources.init(this)
            dev.personal.tv.source.Live.init(this)
            dev.personal.tv.data.SearchIndex.init(this)
        }
    }

    /** 豆瓣图床不带 Referer 返回 418 */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            Net.client.newBuilder().addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host.endsWith("doubanio.com")) {
                    chain.proceed(req.newBuilder().header("Referer", Douban.REFERER).header("User-Agent", Douban.UA).build())
                } else chain.proceed(req)
            }.build()
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("posters")).maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(200)
        .build()
}
