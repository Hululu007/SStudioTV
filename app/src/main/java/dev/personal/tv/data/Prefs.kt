package dev.personal.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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

    private var _precache by mutableStateOf(true)
    /** 预缓存：播起来以后在后台把这一集后面的部分和下一集下到缓存里（用流量时不下） */
    var precache: Boolean
        get() = _precache
        set(v) { _precache = v; sp?.edit()?.putBoolean("precache", v)?.apply() }

    private var _dlMetered by mutableStateOf(false)
    /** 离线下载用手机流量也下（默认只用 Wi‑Fi） */
    var dlMetered: Boolean
        get() = _dlMetered
        set(v) { _dlMetered = v; sp?.edit()?.putBoolean("dlMetered", v)?.apply() }

    /**
     * 文字、海报大小（无极调节，1 = 原来的大小）。电视和手机分开存：电视离得远，默认放大；手机有系统字号，默认不动。
     * 文字是整体的字号倍数（[dev.personal.tv.ui.theme.TvScale] / PhoneTheme 里乘到 fontScale 上）；海报改网格列数和首页海报宽度。
     */
    private var phone = false
    private var _textScale by mutableFloatStateOf(TV_TEXT)
    private var _posterScale by mutableFloatStateOf(TV_POSTER)
    var textScale: Float
        get() = _textScale
        set(v) { _textScale = snap(v, TEXT_RANGE); sp?.edit()?.putFloat(key("text"), _textScale)?.apply() }
    var posterScale: Float
        get() = _posterScale
        set(v) { _posterScale = snap(v, POSTER_RANGE); sp?.edit()?.putFloat(key("poster"), _posterScale)?.apply() }
    val textDefault get() = if (phone) 1f else TV_TEXT
    val posterDefault get() = if (phone) 1f else TV_POSTER
    /** 网格列数：[base] 是原来（倍数 1）的列数，海报放大就少几列 */
    fun cols(base: Int) = Math.round(base / posterScale).coerceIn(2, base + 2)
    fun pct(v: Float) = "${Math.round(v * 100)}%"

    private fun key(k: String) = (if (phone) "ph_" else "tv_") + k + "Scale"
    private fun snap(v: Float, r: ClosedFloatingPointRange<Float>) = (Math.round(v * 20) / 20f).coerceIn(r)
    val TEXT_RANGE = 0.85f..1.8f
    val POSTER_RANGE = 0.75f..1.8f
    private const val TV_TEXT = 1.25f
    private const val TV_POSTER = 1.2f

    /** 优先的站点，越靠前越优先 */
    val preferred = mutableStateListOf<String>()
    /** 不用的站点 */
    val blocked = mutableStateListOf<String>()

    /** 搜索记录：从搜索结果里打开过的片（遥控器搜的是拼音首字母，记片名比记「JJC」有用），新的在前 */
    data class Searched(val id: String, val name: String)
    val searched = mutableStateListOf<Searched>()

    fun addSearched(id: String, name: String) {
        searched.removeAll { it.id == id }
        searched.add(0, Searched(id, name))
        while (searched.size > 12) searched.removeAt(searched.lastIndex)
        saveSearched()
    }

    fun removeSearched(id: String) { searched.removeAll { it.id == id }; saveSearched() }

    fun clearSearched() { searched.clear(); saveSearched() }

    private fun saveSearched() {
        sp?.edit()?.putString("searched", searched.joinToString("\n") { "${it.id}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" })?.apply()
    }

    enum class SitePref(val label: String) { DEFAULT("默认"), PREFER("优先"), BLOCK("不用") }

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sp = p
        _autoLine = p.getBoolean("autoLine", true)
        _precache = p.getBoolean("precache", true)
        _dlMetered = p.getBoolean("dlMetered", false)
        phone = dev.personal.tv.ui.phone.UiMode.phone(ctx)
        // 老版本的三档海报大小（小 / 中 / 大）折算成倍数
        val oldPoster = p.getInt("posterSize", -1).takeIf { it >= 0 && !phone }?.let { listOf(0.85f, TV_POSTER, 1.5f)[it.coerceIn(0, 2)] }
        _textScale = p.getFloat(key("text"), textDefault).coerceIn(TEXT_RANGE)
        _posterScale = p.getFloat(key("poster"), oldPoster ?: posterDefault).coerceIn(POSTER_RANGE)
        preferred.addAll(p.getString("preferred", "").orEmpty().split('\n').filter { it.isNotEmpty() })
        blocked.addAll(p.getString("blocked", "").orEmpty().split('\n').filter { it.isNotEmpty() })
        searched.addAll(p.getString("searched", "").orEmpty().split('\n').mapNotNull { l ->
            l.split('\t', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { Searched(it[0], it[1]) }
        })
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
