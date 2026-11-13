package dev.personal.tv.ui.phone

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonObject
import dev.personal.tv.data.Prefs
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PanLogin
import dev.personal.tv.source.PluginPortal
import dev.personal.tv.source.SiteCfg
import dev.personal.tv.source.Sources
import dev.personal.tv.spider.PluginSettingsActivity
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.screens.PlayerPrefs
import dev.personal.tv.ui.screens.QrImage
import dev.personal.tv.ui.screens.summary
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 我的：片源、网盘登录、播放、线路偏好、界面切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneMine(header: Boolean = true) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val activity = currentActivity()
    var info by remember { mutableStateOf<String?>(null) }
    var sites by remember { mutableStateOf<List<SiteCfg>?>(null) }
    LaunchedEffect(Sources.url) {
        info = null; sites = null
        if (Sources.empty) return@LaunchedEffect
        try {
            val c = Sources.config()
            info = summary(c); sites = c.sites
        } catch (e: Throwable) { info = "加载失败：${e.message?.take(60)}" }
    }
    var prefsOpen by remember { mutableStateOf(false) }
    var uiAsk by remember { mutableStateOf<UiMode.Choice?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (header) Tx("我的", 22.sp, weight = FontWeight.ExtraBold, modifier = Modifier.statusBarsPadding().padding(start = PC.Edge, top = 8.dp, bottom = 8.dp))

        Group("片源")
        Card {
            SettingRow(
                if (Sources.empty) "还没有片源" else Sources.name,
                if (Sources.empty) "SStudioTV 不自带片源，加一个影视仓 / TVBox 配置地址" else info ?: "加载中…",
                onClick = { nav.openSources() },
            ) { Arrow() }
            if (!Sources.empty) {
                Divider()
                SettingRow(
                    "网盘登录",
                    PanLogin.loggedIn().takeIf { it.isNotEmpty() }?.let { "已登录 ${it.joinToString("、")}" } ?: "夸克、UC、百度、115… 网盘线路要先登录",
                    onClick = { nav.openPans() },
                ) { Arrow() }
                Divider()
                SettingRow(
                    "线路偏好",
                    "优先 ${Prefs.preferred.size} · 不用 ${Prefs.blocked.size} · 播放时先试「优先」的站点",
                    onClick = { if (sites != null) prefsOpen = true else nav.toast(info ?: "片源还在加载") },
                ) { Arrow() }
            }
        }

        Group("播放")
        Card {
            var auto by remember { mutableStateOf(PlayerPrefs.autoLine) }
            var night by remember { mutableStateOf(PlayerPrefs.nightVolume) }
            var level by remember { mutableStateOf(PlayerPrefs.levelLines) }
            var dialog by remember { mutableStateOf(PlayerPrefs.dialogBoost) }
            SettingRow("自动换线路", "解析不出、出错或卡住 20 秒，自动换下一条", onClick = { auto = !auto; PlayerPrefs.autoLine = auto }) {
                PhoneSwitch(auto) { auto = it; PlayerPrefs.autoLine = it }
            }
            Divider()
            SettingRow("夜间音量", "压缩响度差：爆炸声不炸，对白不小", onClick = { night = !night; PlayerPrefs.nightVolume = night }) {
                PhoneSwitch(night) { night = it; PlayerPrefs.nightVolume = it }
            }
            Divider()
            SettingRow("线路间音量统一", "换线路、换集时音量保持一致", onClick = { level = !level; PlayerPrefs.levelLines = level }) {
                PhoneSwitch(level) { level = it; PlayerPrefs.levelLines = it }
            }
            Divider()
            SettingRow("对白增强", "人声频段略微提亮", onClick = { dialog = !dialog; PlayerPrefs.dialogBoost = dialog }) {
                PhoneSwitch(dialog) { dialog = it; PlayerPrefs.dialogBoost = it }
            }
        }

        Group("界面")
        Card {
            val cur = remember { UiMode.choice(ctx) }
            Column(Modifier.padding(horizontal = PC.Edge, vertical = 12.dp)) {
                Tx("用哪套界面", 15.sp)
                Tx("自动：电视和盒子用遥控器界面，手机平板用触屏界面", 12.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiMode.Choice.entries.forEach { c -> Pill(c.label, c == cur) { if (c != cur) uiAsk = c } }
                }
            }
        }

        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
        Tx(
            "SStudioTV $version · 不自带片源，想看、观看记录都只存在本机",
            11.5.sp, Palette.T3, lines = 2, modifier = Modifier.padding(start = PC.Edge, end = PC.Edge, top = 20.dp),
        )
    }

    uiAsk?.let { c ->
        AlertDialog(
            onDismissRequest = { uiAsk = null },
            containerColor = PC.Card2,
            title = { Text("换成「${c.label}」？") },
            text = { Text(if (c == UiMode.Choice.TV) "电视界面是给遥控器用的，手机上会很小、要横着拿。之后想换回来：电视界面「设置」里没有这一项，需要清除应用数据。" else "界面会马上重新打开。") },
            confirmButton = {
                TextButton({
                    uiAsk = null
                    UiMode.set(ctx, c)
                    activity?.recreate()
                }) { Text("换", color = Palette.Douban) }
            },
            dismissButton = { TextButton({ uiAsk = null }) { Text("算了", color = Palette.T2) } },
        )
    }

    if (prefsOpen) sites?.let { list ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet({ prefsOpen = false }, sheetState = state, containerColor = PC.Card) {
            Tx("线路偏好", 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Tx(
                "点一下切换：默认 → 优先 → 不用。播放时先试「优先」的站点（按序号），「不用」的不搜也不播。长按优先的站点往前挪。",
                12.sp, Palette.T3, lines = 3, lineHeight = 17.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            val ordered = remember(list) { list.sortedBy { if (!(it.kind.supported && it.searchable)) 2 else if (it.notVideo != null) 1 else 0 } }
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(ordered, key = { it.key }) { s -> SitePrefRow(s) }
            }
        }
    }
}

@Composable
private fun Group(text: String) =
    Tx(text, 13.sp, Palette.T3, FontWeight.SemiBold, modifier = Modifier.padding(start = PC.Edge + 4.dp, top = 18.dp, bottom = 6.dp))

@Composable
private fun Arrow() = Tx("›", 20.sp, Palette.T3)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SitePrefRow(s: SiteCfg) {
    val nav = LocalNav.current
    val usable = s.kind.supported && s.searchable
    val pref = Prefs.pref(s.key)
    val rank = Prefs.rank(s.key)
    Row(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = {
                if (!usable) { nav.toast("这个站点用不上，不用设置"); return@combinedClickable }
                val p = Prefs.cycle(s.key)
                if (p == Prefs.SitePref.BLOCK || p == Prefs.SitePref.DEFAULT) Lines.clear()
            },
            onLongClick = { if (rank != null && rank > 0) Prefs.moveUp(s.key) },
        ).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Tx(s.name, 14.5.sp, if (usable) Palette.T1 else Palette.T3, FontWeight.Medium)
            Tx(
                when {
                    !s.kind.supported -> "${s.kind.label} · 暂不支持"
                    !s.searchable -> "${s.kind.label} · 不支持搜索，合并线路用不上"
                    s.notVideo != null && rank == null -> "${s.notVideo} · 找线路时不搜它；设成「优先」就会搜"
                    else -> s.kind.label
                },
                11.5.sp, Palette.T3, lines = 2,
            )
        }
        if (usable) {
            val (label, bg) = when (pref) {
                Prefs.SitePref.PREFER -> "优先 ${rank!! + 1}" to Palette.Douban
                Prefs.SitePref.BLOCK -> "不用" to Palette.Bad
                Prefs.SitePref.DEFAULT -> "默认" to Color.Transparent
            }
            Tx(
                label, 11.sp, if (bg == Color.Transparent) Palette.T3 else Color.White, FontWeight.SemiBold,
                Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ======================================================================
// 片源
// ======================================================================

/** 片源：列表（点了切换、垃圾桶删除），上面输地址添加 */
@Composable
fun PhoneSources() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    val clip = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var del by remember { mutableStateOf<Sources.Entry?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Sources.url) {
        info = null
        if (!Sources.empty) info = try { summary(Sources.config()) } catch (e: Throwable) { "加载失败：${e.message?.take(60)}" }
    }
    fun submit() {
        val u = input.trim()
        if (u.isEmpty() || adding) return
        adding = true; err = null
        kb?.hide()
        scope.launch {
            try {
                val got = Sources.add(null, u)
                nav.toast(if (got.isEmpty()) "已经加过了" else "已添加 ${got.size} 个：" + got.joinToString("、") { it.name })
                input = ""
            } catch (e: Throwable) {
                err = "加不上：${e.message?.take(80)}"
            } finally { adding = false }
        }
    }

    SubPage("片源", if (Sources.list.isEmpty()) null else "${Sources.list.size} 个") {
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("add") {
                Card(Modifier.padding(top = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Tx("添加片源", 15.sp, weight = FontWeight.SemiBold)
                        Tx("影视仓 / TVBox 的配置地址（多仓地址会一次加进来好几个）", 12.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (input.isEmpty()) Tx("http://…", 14.sp, Palette.T3)
                                BasicTextField(
                                    input, { input = it; err = null }, Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(color = Palette.T1, fontSize = 14.sp),
                                    cursorBrush = SolidColor(Palette.Douban),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                    maxLines = 3,
                                )
                            }
                            if (input.isEmpty()) {
                                Tx(
                                    "粘贴", 14.sp, Palette.T1, FontWeight.SemiBold,
                                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                        clip.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { input = it } ?: nav.toast("剪贴板是空的")
                                    }.padding(horizontal = 10.dp, vertical = 11.dp),
                                )
                            }
                        }
                        err?.let { Tx(it, 12.sp, Palette.Bad, lines = 3, modifier = Modifier.padding(top = 8.dp)) }
                        BigButton(Modifier.padding(top = 12.dp), primary = true, enabled = input.isNotBlank() && !adding, height = 40.dp, onClick = { submit() }) {
                            Tx(if (adding) "正在读取配置…" else "添加", 14.sp, it, FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (Sources.list.isEmpty()) item("empty") { Empty("还没有片源", "SStudioTV 不自带片源。把别人分享的影视仓 / TVBox 配置地址粘贴到上面") }
            else item("h") { Group("已添加 · 点一下切换") }
            items(Sources.list, key = { it.url }) { e ->
                val cur = e.url == Sources.url
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (!cur) { Sources.select(e); nav.toast("已切换到「${e.name}」，线路会重新找") }
                    }.background(if (cur) Color.White.copy(alpha = 0.05f) else Color.Transparent).padding(start = PC.Edge, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(if (cur) Palette.Douban else Color.Transparent, RoundedCornerShape(50)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Tx(e.name, 15.sp, if (cur) Palette.Douban else Palette.T1, if (cur) FontWeight.SemiBold else FontWeight.Normal)
                        val sub = if (cur) info ?: "加载中…" else e.url.takeIf { it != e.name }
                        if (sub != null) Tx(sub, 11.5.sp, Palette.T3, lines = 2)
                    }
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable { del = e }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Delete, "删除", Modifier.size(20.dp), tint = Palette.T3)
                    }
                }
            }
        }
    }

    del?.let { e ->
        AlertDialog(
            onDismissRequest = { del = null },
            containerColor = PC.Card2,
            title = { Text("删除「${e.name}」？") },
            text = { Text("只是从列表里拿掉，想看和观看记录不受影响。") },
            confirmButton = { TextButton({ Sources.remove(e); del = null; nav.toast("已删除「${e.name}」") }) { Text("删除", color = Palette.Bad) } },
            dismissButton = { TextButton({ del = null }) { Text("算了", color = Palette.T2) } },
        )
    }
}

// ======================================================================
// 网盘登录
// ======================================================================

private data class Scan(val image: String?, val msg: String, val failed: Boolean = false)

/**
 * 网盘登录（手机）：每家一行。能扫码的出那家的登录码——手机扫不了自己的屏幕，所以提示截屏后在那家 App 的扫一扫里选相册；
 * 要输手机号 / 密码的、以及「全部插件设置」直接在页内打开插件的配置中心。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePans() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<PluginPortal.Base?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var alt by remember { mutableStateOf<PluginPortal.Settings?>(null) }
    var status by remember { mutableStateOf(PanLogin.last) }
    LaunchedEffect(Sources.url) {
        base = null; err = null; alt = null
        PluginPortal.base(ctx).fold({ base = it }, {
            err = it.message ?: "插件打不开"
            alt = withContext(Dispatchers.IO) { runCatching { PluginPortal.settings(Sources.config()) }.getOrNull() }
        })
    }
    suspend fun refresh(b: PluginPortal.Base) {
        withContext(Dispatchers.IO) { runCatching { PluginPortal.api(b, "status") } }.getOrNull()
            ?.takeIf { it.get("code")?.asInt == 0 }?.let { status = it; PanLogin.last = it }
    }
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        while (true) { refresh(b); delay(4000) }
    }

    var web by remember { mutableStateOf<String?>(null) }
    var qrFor by remember { mutableStateOf<PanLogin.Pan?>(null) }
    var logout by remember { mutableStateOf<PanLogin.Pan?>(null) }

    web?.let { u -> PluginWeb(u) { web = null; base?.let { b -> scope.launch { refresh(b) } } }; return }

    SubPage("网盘登录") {
        val b = base
        val a = alt
        when {
            a != null -> Column(Modifier.fillMaxSize()) {
                Tx(
                    "当前片源的插件把网盘登录放在它自带的「${a.site.name.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()}」里：进去选对应网盘的「扫码登录」。",
                    13.sp, Palette.T2, lines = 4, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = PC.Edge, vertical = 8.dp),
                )
                Card { SettingRow("打开插件设置中心", "各家网盘的登录，以及播放、弹幕等插件选项", onClick = { PluginSettingsActivity.open(ctx, a.jar, a.md5, a.site) }) { Arrow() } }
            }
            b == null -> if (err != null) Empty("网盘登录打不开", err!!) else CenterNote("正在唤醒插件…")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item("tip") {
                    Tx(
                        "网盘类线路要先登录对应网盘才能播。登录信息只存在这台手机的插件里。",
                        12.5.sp, Palette.T3, lines = 2, modifier = Modifier.padding(horizontal = PC.Edge, vertical = 6.dp),
                    )
                }
                items(PanLogin.all, key = { it.key }) { p ->
                    val on = p.on(status) && !p.expired(status)
                    val st = p.status(status)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (p.qr != null && !on) qrFor = p else web = "${b.local}&action=config#${p.page}"
                        }.padding(start = PC.Edge, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Tx(p.name, 15.sp, weight = FontWeight.Medium)
                            Tx(if (st.isEmpty() || st in setOf("未登录", "已登录", "已失效")) p.how else "$st · ${p.how}", 11.5.sp, Palette.T3, lines = 2)
                        }
                        if (status != null) {
                            val (label, bg) = when {
                                p.expired(status) -> "已失效" to Palette.Bad
                                on -> "已登录" to Palette.Douban
                                else -> "未登录" to Color.Transparent
                            }
                            Tx(
                                label, 11.sp, if (bg == Color.Transparent) Palette.T3 else Color.White, FontWeight.SemiBold,
                                Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        if (p.on(status)) {
                            Tx("退出", 13.sp, Palette.T2, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { logout = p }.padding(horizontal = 10.dp, vertical = 8.dp))
                        } else Spacer(Modifier.size(8.dp))
                    }
                }
                item("all") {
                    Card(Modifier.padding(top = 12.dp)) {
                        SettingRow("全部插件设置", "播放选项、网盘优先顺序、弹幕、备份等", onClick = { web = "${b.local}&action=config" }) { Arrow() }
                    }
                }
            }
        }
    }

    qrFor?.let { p ->
        val b = base ?: return@let
        QrSheet(p, b, onDone = { qrFor = null; scope.launch { refresh(b) } }, onWeb = { qrFor = null; web = "${b.local}&action=config#${p.page}" })
    }
    logout?.let { p ->
        AlertDialog(
            onDismissRequest = { logout = null },
            containerColor = PC.Card2,
            title = { Text("退出${p.name}？") },
            text = { Text(if (p.clear == "clearUc") "UC 的 Cookie 和 TV Token 会一起清掉。" else "之后这家网盘的线路要重新登录才能播。") },
            confirmButton = {
                TextButton({
                    logout = null
                    val b = base ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { PluginPortal.api(b, p.clear) } }
                        nav.toast("已退出${p.name}")
                        refresh(b)
                    }
                }) { Text("退出", color = Palette.Bad) }
            },
            dismissButton = { TextButton({ logout = null }) { Text("算了", color = Palette.T2) } },
        )
    }
}

/** 扫码登录：向插件要码，每 1.5 秒问一次扫了没有；关掉时告诉插件别等了 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrSheet(p: PanLogin.Pan, b: PluginPortal.Base, onDone: () -> Unit, onWeb: () -> Unit) {
    val nav = LocalNav.current
    var scan by remember { mutableStateOf<Scan?>(Scan(null, "正在生成二维码…")) }
    var seq by remember { mutableIntStateOf(0) }
    LaunchedEffect(seq) {
        val provider = p.qr ?: return@LaunchedEffect
        scan = Scan(null, "正在生成二维码…")
        val start = withContext(Dispatchers.IO) { runCatching { PluginPortal.api(b, "loginStart", "provider" to provider) } }
            .getOrElse { scan = Scan(null, "生成二维码失败：${it.message?.take(40)}", true); return@LaunchedEffect }
        val task = start.str("taskId")
        val img = start.str("qrUrl")
        if (start.get("code")?.asInt != 0 || task == null || img == null) {
            scan = Scan(null, start.str("msg") ?: "生成二维码失败", true); return@LaunchedEffect
        }
        scan = Scan(img, start.str("msg") ?: p.how)
        var over = false
        try {
            while (true) {
                delay(1500)
                val r = withContext(Dispatchers.IO) {
                    runCatching { PluginPortal.api(b, "loginPoll", "provider" to provider, "taskId" to task) }.getOrNull()
                } ?: continue
                if (r.str("status") == "success") {
                    over = true
                    nav.toast("${p.name} 登录好了")
                    onDone()
                    break
                }
                if (r.get("terminal")?.asBoolean == true) {
                    over = true
                    scan = Scan(null, (r.str("msg") ?: "二维码过期了") + "，点「重新生成」", true)
                    break
                }
                r.str("msg")?.let { scan = scan?.copy(msg = it) }
            }
        } finally {
            if (!over) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { PluginPortal.api(b, "loginCancel", "provider" to provider, "taskId" to task) }
            }
        }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDone, sheetState = state, containerColor = PC.Card) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Tx(p.name, 18.sp, weight = FontWeight.Bold)
            Tx(p.how, 12.5.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp))
            val s = scan
            Box(
                Modifier.padding(top = 16.dp).size(220.dp).background(if (s?.image != null) Color.White else PC.Card2, RoundedCornerShape(12.dp)).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (s?.image != null) QrImage(s.image, Modifier.fillMaxSize()) else Tx(if (s?.failed == true) "没出来" else "…", 16.sp, Palette.T3)
            }
            Tx(s?.msg.orEmpty(), 12.5.sp, if (s?.failed == true) Palette.Bad else Palette.T2, lines = 3, modifier = Modifier.padding(top = 12.dp))
            Tx(
                "在这台手机上登录：截个屏，打开${p.name.substringBefore(" ·")}的 App →「扫一扫」→ 从相册选这张截图，确认后这里会自动关掉。用另一台手机直接扫也行。",
                12.sp, Palette.T3, lines = 4, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(Modifier.weight(1f), height = 40.dp, onClick = { seq++ }) { Tx("重新生成", 14.sp, it, FontWeight.SemiBold) }
                BigButton(Modifier.weight(1f), height = 40.dp, onClick = onWeb) { Tx("在配置中心登录", 14.sp, it, FontWeight.SemiBold) }
            }
        }
    }
}

/** 插件配置中心：页内打开（插件在本机 127.0.0.1） */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginWeb(url: String, onClose: () -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    BackHandler { view?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }
    Column(Modifier.fillMaxSize()) {
        TopBar("插件配置中心", onBack = onClose)
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    setBackgroundColor(android.graphics.Color.WHITE)
                    loadUrl(url)
                    view = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().imePadding(),
        )
    }
}

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
