package dev.personal.tv.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 横版剧照的地址，按豆瓣 id 记：首页大背景、继续观看卡片要横图，豆瓣海报是竖的。
 * 值为空串 = 查过了，没有横的（用竖海报的摆法）。存在 filesDir/stills.json，一部片只查一次。
 * 界面读 [get]：还没查过就排队去查，查到后界面自动刷新。
 */
object Stills {
    private const val TAG = "Stills"
    private val map = mutableStateMapOf<String, String>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 一次最多查两部，别和搜索、详情抢豆瓣的请求额度 */
    private val gate = Semaphore(2)
    private var file: File? = null

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "stills.json")
        file = f
        runCatching {
            if (f.exists()) {
                val m: Map<String, String> = gson.fromJson(f.readText(), object : TypeToken<Map<String, String>>() {}.type)
                map.putAll(m)
            }
        }.onFailure { Log.w(TAG, "load", it) }
    }

    /** 剧照地址；没有横图或者还在查返回 null */
    fun get(t: Title): String? {
        val v = map[t.id]
        if (v == null) request(t)
        return v?.ifEmpty { null }
    }

    private fun request(t: Title) {
        if (file == null || !pending.add(t.id)) return
        appScope.launch(Dispatchers.IO) {
            try {
                // 焦点快速划过时不查：停留一会儿还在才查
                delay(150)
                val url = gate.withPermit { Douban.still(t.id, t.dtype) }
                map[t.id] = url.orEmpty()
                save()
            } catch (e: Throwable) {
                // 网络失败、被限流：这次不记，下次显示时再查
                Log.d(TAG, "${t.name}: ${e.message}")
            } finally {
                pending.remove(t.id)
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching { saveJson(f, HashMap(map)) }.onFailure { Log.w(TAG, "save", it) }
    }
}
