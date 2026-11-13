package dev.personal.tv.spider

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.catvod.crawler.Spider
import dev.personal.tv.source.JarLoader
import dev.personal.tv.source.SiteCfg
import dev.personal.tv.source.Vod
import dev.personal.tv.source.VodClass
import dev.personal.tv.source.VodJson
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.phone.Divider
import dev.personal.tv.ui.phone.Empty
import dev.personal.tv.ui.phone.PC
import dev.personal.tv.ui.phone.PhoneTheme
import dev.personal.tv.ui.phone.PillRow
import dev.personal.tv.ui.phone.TopBar
import dev.personal.tv.ui.phone.Tx
import dev.personal.tv.ui.phone.UiMode
import dev.personal.tv.ui.theme.AppTheme
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件自带的设置中心（比如摸鱼儿的「设置中心」站点 csp_FishConfig）：分类是各家网盘、播放选项，点一项插件自己弹 Android 对话框
 * （扫码登录的二维码、输入框）。对话框要挂在插件所在进程的 Activity 上——插件跑在 :spider 进程，界面进程的 Activity 它看不到，
 * 所以这个页面也开在 :spider 进程里：插件通过 ActivityThread / 生命周期回调找到它，对话框就弹在它上面。
 */
class PluginSettingsActivity : ComponentActivity() {

    /** 对话框关掉后窗口重新拿到焦点，刷新一下当前分类（登录状态会变） */
    private var focusTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val jar = i.getStringExtra("jar") ?: return finish()
        val md5 = i.getStringExtra("md5")
        val key = i.getStringExtra("key") ?: return finish()
        val api = i.getStringExtra("api") ?: return finish()
        val ext = i.getStringExtra("ext").orEmpty()
        val name = i.getStringExtra("name") ?: "插件设置"
        // 手机：竖屏、触屏布局；电视：横屏、遥控器布局
        val phone = i.getBooleanExtra("phone", false)
        requestedOrientation = if (phone) UiMode.browseOrientation(this) else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContent {
            if (phone) PhoneTheme { Screen(name, true) { JarLoader.spider(applicationContext, jar, md5, key, api, ext) } }
            else AppTheme { Screen(name, false) { JarLoader.spider(applicationContext, jar, md5, key, api, ext) } }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusTick++
    }

    @Composable
    private fun Screen(title: String, phone: Boolean, load: () -> Spider) {
        val scope = rememberCoroutineScope()
        var sp by remember { mutableStateOf<Spider?>(null) }
        var err by remember { mutableStateOf<String?>(null) }
        var classes by remember { mutableStateOf<List<VodClass>>(emptyList()) }
        var home by remember { mutableStateOf<List<Vod>>(emptyList()) }
        /** 选中的分类；null = 首页推荐 */
        var cls by remember { mutableStateOf<String?>(null) }
        var items by remember { mutableStateOf<List<Vod>>(emptyList()) }
        var note by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val first = remember { FocusRequester() }
        /** 从左栏按 → 进右栏的第一项（不然会落到同一高度的「清除账号」之类上） */
        val right = remember { FocusRequester() }
        val toRight = Modifier.focusProperties { this.right = if (items.isNotEmpty()) right else FocusRequester.Default }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val s = load()
                    val p = VodJson.page(s.homeContent(true).orEmpty())
                    sp = s; classes = p.classes; home = p.list
                    if (p.list.isEmpty()) cls = p.classes.firstOrNull()?.id
                }.onFailure { err = "插件打不开：${it.message?.take(80)}"; Log.w(TAG, "load", it) }
            }
        }
        // 切分类、对话框关掉回来时重读
        LaunchedEffect(sp, cls, focusTick) {
            val s = sp ?: return@LaunchedEffect
            val c = cls
            items = if (c == null) {
                withContext(Dispatchers.IO) { runCatching { VodJson.page(s.homeContent(true).orEmpty()).list }.getOrNull() } ?: home
            } else {
                withContext(Dispatchers.IO) { runCatching { VodJson.page(s.categoryContent(c, "1", true, HashMap()).orEmpty()).list }.getOrDefault(emptyList()) }
            }
        }
        LaunchedEffect(classes) { if (!phone && (classes.isNotEmpty() || home.isNotEmpty())) { delay(80); runCatching { first.requestFocus() } } }

        fun open(v: Vod) {
            val s = sp ?: return
            if (busy) return
            busy = true
            note = "正在打开「${v.name}」…"
            scope.launch {
                // 插件在这里弹对话框（扫码、输入）；返回的详情里有说明文字的话显示出来
                val r = withContext(Dispatchers.IO) { runCatching { s.detailContent(listOf(v.id)).orEmpty() } }
                busy = false
                note = r.fold(
                    { VodJson.page(it).list.firstOrNull()?.let { d -> d.content.ifBlank { d.remarks } }.orEmpty() },
                    { "出错了：${it.message?.take(80)}" },
                )
                delay(600)
                focusTick++
            }
        }

        if (phone) {
            PhoneLayout(title, err, sp != null, home.isNotEmpty(), classes, cls, items, note, busy, { cls = it }, ::open)
            return
        }

        Row(
            Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 36.dp, bottom = Dimens.SafeV),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(Modifier.width(200.dp).fillMaxHeight()) {
                T(title, 20.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                T("插件自带的设置中心", 10.sp, Palette.T3, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (home.isNotEmpty()) item {
                        Row1("总览", null, cls == null, toRight.focusRequester(first), onFocus = { cls = null }) { cls = null }
                    }
                    items(classes, key = { it.id }) { c ->
                        Row1(c.name, null, cls == c.id, if (home.isEmpty() && c == classes.first()) toRight.focusRequester(first) else toRight, onFocus = { cls = c.id }) { cls = c.id }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    err != null -> T(err!!, 13.sp, Palette.Bad, lines = 4)
                    sp == null -> T("正在加载插件…", 13.sp, Palette.T2)
                    items.isEmpty() -> T("这一栏是空的", 12.sp, Palette.T3)
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(items, key = { it.id }) { v -> Row1(v.name, v.remarks, false, if (v == items.first()) Modifier.focusRequester(right) else Modifier) { open(v) } }
                    }
                }
                if (note.isNotBlank()) T(note, 10.5.sp, Palette.T2, lines = 4, modifier = Modifier.padding(top = 10.dp, start = 12.dp))
                Row(Modifier.padding(top = 8.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Hint("OK", "打开（插件会弹出二维码或输入框）", 9.5.sp)
                    Hint("返回", "回到 SStudioTV", 9.5.sp)
                }
            }
        }
    }

    /** 手机：顶栏 + 分类标签 + 列表，点一项插件弹对话框 */
    @Composable
    private fun PhoneLayout(
        title: String, err: String?, loaded: Boolean, hasHome: Boolean, classes: List<VodClass>, cls: String?,
        items: List<Vod>, note: String, busy: Boolean, pick: (String?) -> Unit, open: (Vod) -> Unit,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, "插件自带的设置中心", onBack = { finish() })
            val tabs = (if (hasHome) listOf<VodClass?>(null) else emptyList()) + classes
            if (tabs.size > 1) {
                PillRow(tabs.map { it?.name ?: "总览" }, tabs.indexOfFirst { it?.id == cls }.coerceAtLeast(0), Modifier.padding(bottom = 6.dp), strong = true) { pick(tabs[it]?.id) }
            }
            if (note.isNotBlank()) {
                Tx(
                    note, 13.sp, Palette.T2, lines = 5, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = PC.Edge, vertical = 6.dp).fillMaxWidth()
                        .background(PC.Card, RoundedCornerShape(10.dp)).padding(12.dp),
                )
            }
            when {
                err != null -> Empty("插件打不开", err)
                !loaded -> Empty("正在加载插件…")
                items.isEmpty() -> Empty("这一栏是空的")
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(items, key = { it.id }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { open(v) }.padding(horizontal = PC.Edge, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Tx(v.name, 15.5.sp, weight = FontWeight.Medium, lines = 2)
                                if (v.remarks.isNotBlank()) Tx(v.remarks, 12.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 2.dp))
                            }
                            Tx("›", 20.sp, Palette.T3, modifier = Modifier.padding(start = 8.dp))
                        }
                        Divider()
                    }
                }
            }
        }
    }

    @Composable
    private fun Row1(title: String, sub: String?, on: Boolean, modifier: Modifier, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
        Focusable(onClick, modifier.fillMaxWidth(), corner = 9.dp, scale = 1.02f, ring = false, onFocus = onFocus) { f ->
            val fg = if (f) Palette.Bg else if (on) Palette.Douban else Palette.T1
            Row(
                Modifier.fillMaxWidth().height(if (sub.isNullOrBlank()) 38.dp else 46.dp)
                    .background(if (f) Color.White else Color.Transparent, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T(title, 12.5.sp, fg, FontWeight.SemiBold)
                    if (!sub.isNullOrBlank()) T(sub, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
                }
                Box(contentAlignment = Alignment.CenterEnd) { T("›", 16.sp, fg) }
            }
        }
    }

    companion object {
        private const val TAG = "PluginSettings"

        fun open(ctx: Context, jar: String, md5: String?, site: SiteCfg) {
            ctx.startActivity(
                Intent(ctx, PluginSettingsActivity::class.java).putExtra("phone", UiMode.phone(ctx))
                    .putExtra("jar", jar).putExtra("md5", md5)
                    .putExtra("key", site.key).putExtra("api", site.api).putExtra("ext", site.ext)
                    .putExtra("name", site.name.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().ifEmpty { "插件设置" })
                    .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
