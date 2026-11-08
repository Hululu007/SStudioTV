package dev.personal.tv.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首字母搜索用的本地片库索引。
 * 豆瓣接口不认拼音首字母，只能先把片名拉到本地：选片接口每个条件最多翻 500 部，
 * 按「类型 × 年代 × 地区」拆成几十个条件拉，合起来一万多部，基本覆盖豆瓣上有人看的片。
 * 第一次启动后台建（几分钟，每拉完一组就存一次，边建边能搜），之后每 7 天在后台更新一遍。
 */
object SearchIndex {
    private const val TAG = "SearchIndex"

    /** 精简条目：只存搜索和显示海报卡片要的字段 */
    class Entry(
        val id: String, val name: String, val ini: String, val year: Int, val dtype: String, val kind: Kind,
        val score: Float?, val count: Int, val poster: String?, val region: String, val genres: List<String>,
    ) {
        fun toTitle() = Title(
            id = id, name = name, year = year, region = region, kind = kind, genres = genres, score = score,
            poster = poster, dtype = dtype, ratingCount = count,
        )
    }

    private class Saved(val entries: List<Entry>?, val done: List<String>?, val builtAt: Long)

    @Volatile private var entries: List<Entry> = emptyList()
    private val doneQueries = HashSet<String>()
    private var builtAt = 0L
    private lateinit var file: File

    /** 已索引多少部 */
    var size by mutableIntStateOf(0)
        private set
    /** 正在建索引时的进度 「12/47」，null = 没在建 */
    var progress by mutableStateOf<String?>(null)
        private set

    /** 拉哪些：type, tags；每组翻到 500 部为止 */
    private val QUERIES: List<Pair<String, String>> = buildList {
        val years = listOf("2026", "2025", "2024", "2023", "2022", "2021", "2020", "2010年代", "2000年代", "90年代", "80年代")
        years.forEach { add("tv" to "电视剧,$it") }
        listOf("2010年代", "2000年代", "90年代").forEach { add("tv" to "电视剧,华语,$it") }
        listOf("2026", "2025", "2024", "2023", "2020年代", "2010年代").forEach { add("tv" to "综艺,$it") }
        listOf("2020年代", "2010年代", "2000年代").forEach { add("tv" to "动画,$it") }
        add("tv" to "纪录片")
        (years + "70年代").forEach { add("movie" to "电影,$it") }
        listOf("2020年代", "2010年代", "2000年代", "90年代").forEach { add("movie" to "电影,华语,$it") }
        add("movie" to "电影,动画")
        add("movie" to "电影,纪录片")
    }

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "search-index.json")
        appScope.launch {
            runCatching {
                // 第一次装：先用包里带的种子索引（出包前在模拟器上建好的），装完马上就能搜全量，再在后台慢慢更新
                // （APK 本身会压缩它；别存成 .gz，打包时会被解开改名）
                val text = if (file.exists()) file.readText() else runCatching {
                    ctx.assets.open("search-seed.json").bufferedReader().use { it.readText() }
                }.onFailure { Log.w(TAG, "seed", it) }.getOrNull()
                if (text != null) {
                    val s = gson.fromJson(text, Saved::class.java)
                    entries = s.entries.orEmpty().filter { it.name != null && it.ini != null && it.genres != null }
                    entries.forEach { Pinyin.prime(it.name, it.ini) }
                    s.done?.let { doneQueries.addAll(it) }
                    builtAt = s.builtAt
                    withContext(Dispatchers.Main) { size = entries.size }
                    Log.i(TAG, "loaded ${entries.size} (${if (file.exists()) "file" else "seed"})")
                }
            }.onFailure { Log.w(TAG, "load", it) }
            // 冷启动先让首页、找片把网络用完，再慢慢建
            delay(20_000)
            val stale = System.currentTimeMillis() - builtAt > 7L * 24 * 3600_000
            if (stale) { doneQueries.clear() }
            if (doneQueries.size < QUERIES.size) build()
        }
    }

    private suspend fun build() {
        val byId = LinkedHashMap<String, Entry>()
        entries.forEach { byId[it.id] = it }
        val todo = QUERIES.filter { "${it.first}:${it.second}" !in doneQueries }
        Log.i(TAG, "build ${todo.size} groups, have ${byId.size}")
        todo.forEachIndexed { i, (type, tags) ->
            withContext(Dispatchers.Main) { progress = "${QUERIES.size - todo.size + i}/${QUERIES.size}" }
            var ok = true
            for (start in 0 until 500 step 100) {
                val page = try {
                    Douban.recommend(type, tags.split(','), "T", start, 100, cache = false)
                } catch (e: Throwable) {
                    Log.w(TAG, "$tags @$start: ${e.message}")
                    ok = false
                    break
                }
                page.forEach { t -> byId[t.id] = entry(t) }
                if (page.size < 100) break
                delay(400) // 别把豆瓣拉烦了
            }
            if (ok) doneQueries += "$type:$tags"
            entries = byId.values.toList()
            withContext(Dispatchers.Main) { size = entries.size }
            save()
        }
        if (doneQueries.size >= QUERIES.size) builtAt = System.currentTimeMillis()
        save()
        withContext(Dispatchers.Main) { progress = null }
        Log.i(TAG, "built ${entries.size}")
    }

    private fun entry(t: Title) = Entry(
        t.id, t.name, Pinyin.initials(t.name), t.year, t.dtype, t.kind, t.score, t.ratingCount, t.poster, t.region, t.genres,
    )

    private fun save() = runCatching {
        saveJson(file, Saved(entries, doneQueries.toList(), builtAt))
    }.onFailure { Log.w(TAG, "save", it) }

    /**
     * 首字母前缀或片名包含。按「首字母完全一致 → 评价人数」排，人数多的通常就是想找的那部。
     * 在后台线程调。
     */
    fun search(q: String, limit: Int = 60): List<Entry> {
        val key = q.uppercase().replace(" ", "")
        if (key.isEmpty()) return emptyList()
        // 首字母也认中间一段：「后宫·甄嬛传」按 ZHZ 也能找到，排在开头就对上的后面
        fun rank(e: Entry) = when {
            e.ini == key || e.name == q -> 0
            e.ini.startsWith(key) || e.name.startsWith(q, ignoreCase = true) -> 1
            else -> 2
        }
        return entries.asSequence()
            .filter { it.ini.startsWith(key) || it.name.contains(q, ignoreCase = true) || (key.length >= 2 && it.ini.contains(key)) }
            .sortedWith(compareBy<Entry> { rank(it) }.thenByDescending { it.count })
            .take(limit).toList()
    }
}
