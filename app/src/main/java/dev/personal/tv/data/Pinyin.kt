package dev.personal.tv.data

import android.os.Build
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 片名 → 拼音首字母（庆余年 → QYN），给遥控器首字母搜索用。
 * Android 10+ 用系统 ICU 的 Han-Latin；更老的系统按 GB2312 一级汉字的拼音区间查（二级汉字查不到就跳过）。
 * 字母和数字原样保留，标点、空格丢掉。
 */
object Pinyin {
    private val cache = ConcurrentHashMap<String, String>()

    /** 已知的首字母直接放进缓存（片库索引里存着），免得在主线程上逐字转拼音 */
    fun prime(name: String, ini: String) { cache.putIfAbsent(name, ini) }

    fun initials(s: String): String = cache.getOrPut(s) {
        buildString {
            for (c in s) {
                when {
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> append(c.uppercaseChar())
                    c in '０'..'９' -> append('0' + (c - '０'))
                    c.code in 0x4E00..0x9FFF -> han(c)?.let { append(it) }
                }
            }
        }
    }

    private val icu: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 29) runCatching { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull() else null
    }

    private fun han(c: Char): Char? {
        if (Build.VERSION.SDK_INT >= 29) {
            val t = icu as? android.icu.text.Transliterator
            if (t != null) {
                val r = synchronized(t) { t.transliterate(c.toString()) }
                r.firstOrNull { it in 'a'..'z' || it in 'A'..'Z' }?.let { return it.uppercaseChar() }
            }
        }
        return gb(c)
    }

    // GB2312 一级汉字按拼音排序：每个声母第一个字的区位码
    private val BOUNDS = intArrayOf(
        1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106, 3212, 3472,
        3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558, 4684, 4925, 5249,
    )
    private const val LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ"
    private val gb2312: Charset? by lazy { runCatching { Charset.forName("GB2312") }.getOrNull() }

    private fun gb(c: Char): Char? {
        val b = runCatching { c.toString().toByteArray(gb2312 ?: return null) }.getOrNull() ?: return null
        if (b.size < 2) return null
        val code = ((b[0].toInt() and 0xff) - 160) * 100 + ((b[1].toInt() and 0xff) - 160)
        if (code !in 1601 until 5590) return null
        for (i in LETTERS.indices.reversed()) if (code >= BOUNDS[i]) return LETTERS[i]
        return null
    }
}
