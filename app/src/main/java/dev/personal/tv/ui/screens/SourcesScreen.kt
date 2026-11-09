package dev.personal.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.personal.tv.source.PluginPortal
import dev.personal.tv.source.Remote
import dev.personal.tv.source.Sources
import dev.personal.tv.source.TvConfig
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 片源管理：已保存的配置（OK 切换、≡ 删除）+ 手机扫码添加 / 电视上用输入法添加。
 * App 不自带片源，第一次用要在这里加一个影视仓 / TVBox 配置地址。
 */
@Composable
fun SourcesScreen() {
    val nav = LocalNav.current
    val mem = nav.mem("sources")
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    val first = remember { FocusRequester() }
    val field = remember { FocusRequester() }

    var remoteUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        scope.launch { remoteUrl = withContext(Dispatchers.IO) { Remote.start() } }
        onDispose { Thread { Remote.stop() }.start() }
    }
    // 手机加了新的：提示一下
    var lastSize by remember { mutableStateOf(Sources.list.size) }
    LaunchedEffect(Sources.list.size) {
        if (Sources.list.size > lastSize) nav.toast("已添加：${Sources.list.last().name}")
        lastSize = Sources.list.size
    }

    // 右栏：焦点在「插件配置中心」上时换成插件配置页的二维码
    val ctx = LocalContext.current
    var pluginPanel by remember { mutableStateOf(false) }
    var portal by remember(Sources.url) { mutableStateOf<PluginPortal.State?>(null) }
    fun showPortal() {
        pluginPanel = true
        if (portal == null || portal is PluginPortal.State.Missing) {
            portal = PluginPortal.State.Loading
            scope.launch { portal = withContext(Dispatchers.IO) { runCatching { PluginPortal.open(ctx) }.getOrElse { PluginPortal.State.Missing("打不开：${it.message?.take(40)}") } } }
        }
    }

    // 当前片源的概况
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Sources.url) {
        info = null
        if (Sources.empty) return@LaunchedEffect
        info = try { summary(Sources.config()) } catch (e: Throwable) { "加载失败：${e.message?.take(60)}" }
    }

    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    BackHandler(enabled = typing) { typing = false; kb?.hide() }
    LaunchedEffect(typing) { if (typing) { delay(80); runCatching { field.requestFocus() }; kb?.show() } }
    fun submit() {
        val u = input.trim()
        if (u.isEmpty() || adding) return
        adding = true
        kb?.hide()
        scope.launch {
            try {
                val got = Sources.add(null, u)
                nav.toast("已添加 ${got.size} 个：" + got.joinToString("、") { it.name })
                input = ""; typing = false
            } catch (e: Throwable) {
                nav.toast("加不上：${e.message?.take(60)}")
            } finally { adding = false }
        }
    }

    // ≡ 删除要连按两次
    var armed by remember { mutableStateOf<String?>(null) }
    var armedAt by remember { mutableLongStateOf(0L) }

    Row(
        Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 36.dp, bottom = Dimens.SafeV),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // ---------------- 左：已有的片源 ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            T("片源", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            T(
                if (Sources.empty) "还没有片源。SStudioTV 不自带任何片源，用右边的方法加一个影视仓 / TVBox 配置地址。"
                else "当前「${Sources.name}」${info?.let { " · $it" } ?: " · 加载中…"}",
                11.sp, Palette.T2, lines = 2, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 14.dp),
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(Sources.list, key = { it.url }) { e ->
                    val cur = e.url == Sources.url
                    Focusable(
                        onClick = {
                            if (!cur) { Sources.select(e); nav.toast("已切换到「${e.name}」，线路会重新找") }
                        },
                        modifier = Modifier.fillMaxWidth().remembered(mem, "src:${e.url}", isDefault = cur)
                            .then(if (cur) Modifier.focusRequester(first) else Modifier),
                        corner = 9.dp, scale = 1.02f, ring = false,
                        onFocus = { pluginPanel = false },
                        onMenu = {
                            val now = System.currentTimeMillis()
                            if (armed == e.url && now - armedAt < 3000) {
                                Sources.remove(e); armed = null; nav.toast("已删除「${e.name}」")
                            } else { armed = e.url; armedAt = now; nav.toast("再按一次 ≡ 删除「${e.name}」") }
                        },
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth().height(50.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(e.name, 13.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold)
                                T(e.url, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
                            }
                            if (cur) T(
                                "当前", 10.sp, Color.White, FontWeight.SemiBold,
                                Modifier.background(Palette.Douban, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                item {
                    Focusable(
                        onClick = { if (!typing) typing = true else { runCatching { field.requestFocus() }; kb?.show() } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).remembered(mem, "type", isDefault = Sources.empty)
                            .then(if (Sources.empty) Modifier.focusRequester(first) else Modifier),
                        corner = 9.dp, scale = 1.02f, ring = false, onFocus = { pluginPanel = false },
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth().height(50.dp)
                                .background(if (f) Color.White else Palette.Ghost6).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (typing) {
                                BasicTextField(
                                    value = input, onValueChange = { input = it.take(300) },
                                    modifier = Modifier.weight(1f).focusRequester(field),
                                    singleLine = true,
                                    textStyle = TextStyle(color = if (f) Palette.Bg else Color.White, fontSize = 14.sp),
                                    cursorBrush = SolidColor(if (f) Palette.Bg else Color.White),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                    decorationBox = { inner ->
                                        Box { if (input.isEmpty()) T("http://…  输完按确定", 13.sp, Palette.T3); inner() }
                                    },
                                )
                                T(if (adding) "正在验证…" else "", 10.sp, Palette.T3)
                            } else {
                                Column(Modifier.weight(1f)) {
                                    T("＋ 用输入法输入地址", 13.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold)
                                    T("遥控器打字比较慢，推荐右边手机扫码", 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
                                }
                            }
                        }
                    }
                }
                if (!Sources.empty) item {
                    Focusable(
                        onClick = { portal = null; showPortal() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).remembered(mem, "portal"),
                        corner = 9.dp, scale = 1.02f, ring = false, onFocus = { showPortal() },
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T("插件配置中心 · 网盘登录", 12.sp, if (f) Palette.Bg else Palette.T2, FontWeight.SemiBold)
                                T("夸克、UC 等网盘站点要先在这里登录，右边扫码用手机操作", 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
                            }
                            T("›", 16.sp, if (f) Palette.Bg else Palette.T3)
                        }
                    }
                }
                item {
                    Focusable(
                        onClick = { nav.openLab() },
                        modifier = Modifier.fillMaxWidth().remembered(mem, "lab"),
                        corner = 9.dp, scale = 1.02f, ring = false, onFocus = { pluginPanel = false },
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T("源调试", 12.sp, if (f) Palette.Bg else Palette.T2, FontWeight.SemiBold)
                                T("逐个站点试搜索、详情、解析，排查哪个站点坏了", 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
                            }
                            T("›", 16.sp, if (f) Palette.Bg else Palette.T3)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Hint("OK", "切换", 9.5.sp)
                Hint("≡", "连按两次删除", 9.5.sp)
            }
        }

        // ---------------- 右：手机扫码 ----------------
        Column(
            Modifier.width(330.dp).fillMaxHeight().background(Palette.Ghost6, RoundedCornerShape(14.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pluginPanel) { PortalPanel(portal); return@Column }
            T("手机扫码添加", 15.sp, weight = FontWeight.Bold)
            T("手机和电视连同一个 Wi-Fi，扫码后粘贴配置地址", 10.sp, Palette.T3, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            val u = remoteUrl
            Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                if (u != null) Qr(u, Modifier.fillMaxSize()) else T("…", 20.sp, Palette.Bg)
            }
            T(u ?: "电视没连上局域网，只能用左边的输入法添加", 10.5.sp, if (u != null) Palette.T2 else Palette.Bad, lines = 2, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.weight(1f))
            T(
                "支持：普通 JSON 配置、加密配置、多仓地址（会展开成多个片源）。添加前电视会先下载验证一遍。",
                9.5.sp, Palette.T3, lines = 3,
            )
        }
    }
    LaunchedEffect(Unit) { if (mem.lastKey == null) { delay(80); runCatching { first.requestFocus() } } }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PortalPanel(st: PluginPortal.State?) {
    T("插件配置中心", 15.sp, weight = FontWeight.Bold)
    T("手机和电视连同一个 Wi-Fi，扫码打开插件自带的设置页", 10.sp, Palette.T3, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
    val u = (st as? PluginPortal.State.Ready)?.url
    Box(Modifier.size(200.dp).background(if (u != null) Color.White else Palette.Ghost6, RoundedCornerShape(10.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
        when (st) {
            is PluginPortal.State.Ready -> Qr(st.url, Modifier.fillMaxSize())
            is PluginPortal.State.Missing -> T(st.why, 11.sp, Palette.T2, lines = 3)
            else -> T("正在唤醒插件…", 11.sp, Palette.T3)
        }
    }
    if (u != null) T(u, 9.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 14.dp))
    Spacer(Modifier.weight(1f))
    T(
        "网盘类站点（名字带 4K 的多半是）要先登录夸克 / UC，否则播放会提示「请先去配置中心登录」。登录信息由插件存在电视上；只有带这串口令的地址能从手机打开。",
        9.5.sp, Palette.T3, lines = 5,
    )
}

private fun summary(c: TvConfig): String {
    val usable = c.sites.count { it.kind.supported && it.searchable && it.notVideo == null }
    return buildString {
        append("${c.sites.size} 个站点（$usable 个影视站能搜）")
        if (c.lives.isNotEmpty()) append(" · ${c.lives.size} 个直播源")
        if (c.parses.isNotEmpty()) append(" · ${c.parses.size} 个解析")
    }
}

/** 二维码：zxing 出矩阵，直接画方块 */
@Composable
fun Qr(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
