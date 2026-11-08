package dev.personal.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设置页里的选项，存本机 SharedPreferences。
 * 站点偏好按站点 key 记：优先的站点按设置的先后排（先设的排前面），不用的站点不搜也不播。
 */
object Prefs {
    private var sp: SharedPreferences? = null

    private var _autoLine by mutableStateOf(true)
    /** 播不了自动换下一条线路 */
    var autoLine: Boolean
        get() = _autoLine
        set(v) { _autoLine = v; sp?.edit()?.putBoolean("autoLine", v)?.apply() }

    /** 优先的站点，越靠前越优先 */
    val preferred = mutableStateListOf<String>()
    /** 不用的站点 */
    val blocked = mutableStateListOf<String>()

    enum class SitePref(val label: String) { DEFAULT("默认"), PREFER("优先"), BLOCK("不用") }

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sp = p
        _autoLine = p.getBoolean("autoLine", true)
        preferred.addAll(p.getString("preferred", "").orEmpty().split('\n').filter { it.isNotEmpty() })
        blocked.addAll(p.getString("blocked", "").orEmpty().split('\n').filter { it.isNotEmpty() })
    }

    fun pref(siteKey: String): SitePref = when (siteKey) {
        in preferred -> SitePref.PREFER
        in blocked -> SitePref.BLOCK
        else -> SitePref.DEFAULT
    }

    /** 优先的排名（0 起），不是优先的返回 null */
    fun rank(siteKey: String): Int? = preferred.indexOf(siteKey).takeIf { it >= 0 }

    /** 默认 → 优先 → 不用 → 默认。返回新状态 */
    fun cycle(siteKey: String): SitePref {
        val next = when (pref(siteKey)) {
            SitePref.DEFAULT -> SitePref.PREFER
            SitePref.PREFER -> SitePref.BLOCK
            SitePref.BLOCK -> SitePref.DEFAULT
        }
        preferred.remove(siteKey); blocked.remove(siteKey)
        when (next) {
            SitePref.PREFER -> preferred.add(siteKey)
            SitePref.BLOCK -> blocked.add(siteKey)
            SitePref.DEFAULT -> {}
        }
        save()
        return next
    }

    /** 优先站点往前挪一位 */
    fun moveUp(siteKey: String) {
        val i = preferred.indexOf(siteKey)
        if (i > 0) { preferred.removeAt(i); preferred.add(i - 1, siteKey); save() }
    }

    fun resetSites() { preferred.clear(); blocked.clear(); save() }

    private fun save() {
        sp?.edit()?.putString("preferred", preferred.joinToString("\n"))?.putString("blocked", blocked.joinToString("\n"))?.apply()
    }
}
