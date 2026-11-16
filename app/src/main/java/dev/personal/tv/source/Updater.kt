package dev.personal.tv.source

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自动更新：看 GitHub 上 SStudioTV 的最新 release，比装着的版本新就提示，下载 APK、交给系统安装器覆盖安装。
 * 覆盖安装（同一把签名）不动应用数据：片源、记录、设置都在。
 *
 * 只请求 GitHub 和加速镜像，请求里只有版本号（User-Agent），不带任何个人信息。
 * 国内直连 GitHub 常常很慢，下载先试直连，慢了换镜像。
 */
object Updater {
    private const val TAG = "Updater"
    private const val REPO = "SizeSStudio/SStudioTV"
    private const val EVERY = 12 * 3600_000L
    /** 下载加速镜像：前缀 + 原 GitHub 地址 */
    private val MIRRORS = listOf("", "https://ghfast.top/", "https://gh-proxy.com/")

    enum class St { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, READY, FAILED }

    var state by mutableStateOf(St.IDLE); private set
    var latest by mutableStateOf<String?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var note by mutableStateOf<String?>(null); private set
    /** 装不了（设备没有安装器）时给手机扫的下载地址 */
    var qr by mutableStateOf<String?>(null); private set

    var current = ""; private set
    private var currentCode = 0L
    private var apkUrl: String? = null
    private var apk: File? = null
    private lateinit var app: Context
    /** 去开了「允许安装应用」：回到 App 时接着装 */
    private var resumeInstall = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ua get() = "SStudioTV/$current"

    fun init(ctx: Context) {
        app = ctx.applicationContext
        runCatching {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            current = pi.versionName.orEmpty()
            currentCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        // 装好新版以后，上次下的包没用了
        File(app.cacheDir, "update").listFiles()?.forEach { it.delete() }
        val sp = app.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("at", 0) > EVERY) scope.launch {
            sp.edit().putLong("at", System.currentTimeMillis()).apply()
            if (check() && latest != sp.getString("told", null)) {
                sp.edit().putString("told", latest).apply()
                withContext(Dispatchers.Main) { dev.personal.tv.ui.Navigator.current?.toast("有新版本 $latest，到「设置 → 检查更新」里一键升级") }
            }
        }
    }

    /** 设置里那一行的说明 */
    val status: String get() = when (state) {
        St.IDLE -> "当前 $current · 按一下检查新版本"
        St.CHECKING -> "正在检查…"
        St.LATEST -> "当前 $current 已经是最新版"
        St.AVAILABLE -> "有新版本 $latest（当前 $current）· 按一下下载"
        St.DOWNLOADING -> "正在下载 $latest · ${(progress * 100).toInt()}%"
        St.READY -> "$latest 已下载 · 按一下安装，片源和记录都保留"
        St.FAILED -> note ?: "出错了，按一下重试"
    }

    /** 设置里按那一行：按当前状态检查 / 下载 / 安装 */
    fun act(ctx: Context) {
        when (state) {
            St.IDLE, St.LATEST -> scope.launch { check() }
            St.FAILED -> scope.launch { if (apkUrl != null && latest != null) download() else check() }
            St.AVAILABLE -> scope.launch { download() }
            St.READY -> install(ctx)
            St.CHECKING, St.DOWNLOADING -> {}
        }
    }

    /** 返回：有没有新版本 */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        if (state == St.CHECKING || state == St.DOWNLOADING) return@withContext state == St.AVAILABLE
        state = St.CHECKING; note = null; qr = null
        val r = runCatching { fromApi() }.onFailure { Log.w(TAG, "api", it) }.getOrNull()
            ?: MIRRORS.firstNotNullOfOrNull { m -> runCatching { fromRedirect(m) }.onFailure { Log.w(TAG, "redirect $m", it) }.getOrNull() }
        if (r == null) {
            state = St.FAILED; note = "连不上 GitHub，过会儿再试"
            return@withContext false
        }
        val (tag, url) = r
        latest = tag.removePrefix("v")
        apkUrl = url
        Log.i(TAG, "最新 $latest · 当前 $current · $url")
        val newer = newer(latest!!, current)
        state = if (newer) St.AVAILABLE else St.LATEST
        newer
    }

    /** 版本号逐段比数字：0.9.10 > 0.9.9 */
    internal fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun call(url: String, redirects: Boolean = true) = Net.client.newBuilder()
        .followRedirects(redirects).followSslRedirects(redirects).callTimeout(20, TimeUnit.SECONDS).build()
        .newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute()

    private fun fromApi(): Pair<String, String> = call("https://api.github.com/repos/$REPO/releases/latest").use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        val o = JsonParser.parseString(r.body!!.string()).asJsonObject
        val tag = o.get("tag_name").asString
        val asset = o.getAsJsonArray("assets").map { it.asJsonObject }
            .firstOrNull { it.get("name").asString.endsWith(".apk") } ?: error("没有 apk")
        tag to asset.get("browser_download_url").asString
    }

    /** api.github.com 不通时：releases/latest 会跳到 …/tag/vX.Y.Z，APK 名字按发布的惯例拼 */
    private fun fromRedirect(mirror: String): Pair<String, String> = call("${mirror}https://github.com/$REPO/releases/latest", redirects = false).use { r ->
        val loc = r.header("Location") ?: error("HTTP ${r.code} 没跳转")
        val tag = loc.substringAfter("/releases/tag/", "").substringBefore('?').takeIf { it.isNotBlank() } ?: error("跳到了 $loc")
        tag to "https://github.com/$REPO/releases/download/$tag/SStudioTV-${tag.removePrefix("v")}.apk"
    }

    private suspend fun download() = withContext(Dispatchers.IO) {
        val url = apkUrl ?: return@withContext
        state = St.DOWNLOADING; progress = 0f; note = null; qr = null
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val f = File(dir, "SStudioTV-$latest.apk")
        var why = ""
        for ((i, m) in MIRRORS.withIndex()) {
            val last = i == MIRRORS.lastIndex
            val ok = runCatching { fetch(m + url, f, giveUpIfSlow = !last) }.onFailure { why = it.message.orEmpty(); Log.w(TAG, "download via '$m'", it) }.getOrDefault(false)
            if (ok) {
                val err = verify(f)
                if (err == null) { apk = f; state = St.READY; return@withContext }
                why = err
            }
        }
        f.delete()
        state = St.FAILED
        note = "下载失败（${why.take(40)}）· 按一下重试"
        qr = "https://github.com/$REPO/releases/latest"
    }

    /** 下到 [f]。[giveUpIfSlow]：前 12 秒连 1 MB 都没下到就换下一个镜像 */
    private fun fetch(url: String, f: File, giveUpIfSlow: Boolean): Boolean {
        val c = Net.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("空响应")
            val total = body.contentLength()
            if (total in 1 until 1_000_000) error("文件太小，不是安装包")
            val t0 = System.currentTimeMillis()
            var got = 0L
            f.outputStream().use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) progress = got.toFloat() / total
                        if (giveUpIfSlow && got < 1_000_000 && System.currentTimeMillis() - t0 > 12_000) { Log.i(TAG, "太慢，换镜像"); return false }
                    }
                }
            }
            return total <= 0 || got == total
        }
    }

    /** 下下来的包要是我们自己、版本更新；签名系统安装时会再核对 */
    private fun verify(f: File): String? {
        @Suppress("DEPRECATION")
        val pi = app.packageManager.getPackageArchiveInfo(f.path, 0) ?: return "安装包损坏"
        if (pi.packageName != app.packageName) return "不是 SStudioTV 的安装包"
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        if (code <= currentCode) return "下到的版本不比现在新"
        return null
    }

    /** MainActivity.onResume：从「允许安装应用」设置页回来，允许了就接着装 */
    fun onResume(ctx: Context) {
        if (!resumeInstall || state != St.READY) return
        resumeInstall = false
        if (Build.VERSION.SDK_INT >= 26 && ctx.packageManager.canRequestPackageInstalls()) install(ctx)
    }

    private fun install(ctx: Context) {
        val f = apk?.takeIf { it.exists() } ?: run { state = St.AVAILABLE; return }
        // Android 8+：先要允许本应用安装应用。设置页回来后再按一次
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val ok = runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).newTask(ctx))
            }.isSuccess
            if (ok) { resumeInstall = true; return }
            // 有的电视没有这个设置页：直接试安装，不行再说
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).newTask(ctx)
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            state = St.FAILED
            note = "这台设备不能直接安装，用手机扫码下载新版"
            qr = "https://github.com/$REPO/releases/latest"
        }
    }

    private fun Intent.newTask(ctx: Context) = apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
