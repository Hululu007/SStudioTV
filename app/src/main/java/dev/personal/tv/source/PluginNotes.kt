package dev.personal.tv.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * 插件进程里被静音的 Toast（见 [dev.personal.tv.spider.QuietToasts]）转到这里，由界面决定显不显示：
 * - 跟登录有关的（请先登录、Cookie 失效、扫码…）：照样提示，并记下来，设置里「网盘登录」那一行显示最近一条，回头也查得到；
 * - 正在解析播放（用户自己点的线路）时，插件说什么都提示；
 * - 其它（后台搜线路时的「此资源失效」、广告）不显示。
 * 同一句话 10 分钟内只提示一次。
 */
object PluginNotes {
    const val ACTION = "dev.personal.tv.PLUGIN_TOAST"
    private val LOGIN = Regex("登录|登陆|扫码|cookie|token|授权|未登|账号|过期", RegexOption.IGNORE_CASE)
    private const val KEEP = 3 * 24 * 3600_000L

    data class Note(val at: Long, val text: String)

    /** 最近一条跟登录有关的插件提示 */
    var lastLogin by mutableStateOf<Note?>(null); private set
    private val shown = HashMap<String, Long>()
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("plugin_notes", Context.MODE_PRIVATE).also { sp = it }
        val at = p.getLong("at", 0)
        p.getString("text", null)?.let { if (System.currentTimeMillis() - at < KEEP) lastLogin = Note(at, it) }
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { i.getStringExtra("text")?.let(::onToast) }
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    internal fun isLogin(text: String) = LOGIN.containsMatchIn(text)

    private fun onToast(raw: String) {
        val t = raw.trim().replace(Regex("\\s+"), " ").take(80)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        val login = isLogin(t)
        android.util.Log.i("PluginNotes", "插件提示：$t · 登录=$login 播放中=${Lines.resolving}")
        if (login) {
            lastLogin = Note(now, t)
            sp?.edit()?.putLong("at", now)?.putString("text", t)?.apply()
        }
        if (!login && !Lines.resolving) return
        if ((shown[t] ?: 0) > now - 10 * 60_000) return
        shown[t] = now
        dev.personal.tv.ui.Navigator.current?.toast(if (login) "插件提示：$t · 到「设置 → 网盘登录」处理" else "插件提示：$t")
    }

    /** 设置里「网盘登录」那一行的说明：最近 3 天有登录提示就显示它 */
    fun loginHint(): String? {
        val n = lastLogin?.takeIf { System.currentTimeMillis() - it.at < KEEP } ?: return null
        val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(n.at))
        return "插件提示「${n.text.take(30)}」· $time"
    }

    /** 进过网盘登录页（处理过了）就不再挂着 */
    fun clearLogin() {
        lastLogin = null
        sp?.edit()?.remove("text")?.apply()
    }
}
