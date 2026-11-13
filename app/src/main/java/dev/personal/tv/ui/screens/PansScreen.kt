package dev.personal.tv.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import dev.personal.tv.source.PanLogin
import dev.personal.tv.source.PluginPortal
import dev.personal.tv.source.Sources
import dev.personal.tv.spider.PluginSettingsActivity
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 右栏的扫码状态：[image] 是二维码内容（链接，或插件直接给的 data:image 图） */
private data class Scan(val image: String?, val msg: String, val failed: Boolean = false)

/**
 * 网盘登录：左边每家网盘一行（登录状态），右边是选中那家的登录二维码。
 * 能扫码的直接出那家的二维码，用那家的 App 扫；迅雷、移动、123 要输手机号 / 密码，出配置中心对应一栏的地址给手机扫。
 * 最后一行「全部插件设置」是整个配置中心（播放选项、弹幕、备份…）。
 */
@Composable
fun PansScreen() {
    val nav = LocalNav.current
    val mem = nav.mem("pans")
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    var base by remember { mutableStateOf<PluginPortal.Base?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(PanLogin.last) }
    /** 插件没有 ?do=pan 接口、但有自带的设置中心站点（摸鱼儿）：网盘登录要进那里 */
    var alt by remember { mutableStateOf<PluginPortal.Settings?>(null) }
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
    // 进来读一次，之后每 4 秒读一次：手机上输验证码登录的，这边也跟着变
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        while (true) { refresh(b); delay(4000) }
    }

    /** 选中的网盘；null = 「全部插件设置」 */
    var sel by remember { mutableStateOf<PanLogin.Pan?>(PanLogin.all.first()) }
    /** 已登录的也要重新扫码 */
    var rescan by remember { mutableStateOf<String?>(null) }
    var seq by remember { mutableIntStateOf(0) }
    var scan by remember { mutableStateOf<Scan?>(null) }

    // 选中能扫码、没登录的网盘：停一下（遥控器快速划过不出码）再向插件要二维码，然后每 1.5 秒问一次扫了没有
    LaunchedEffect(sel, base, seq) {
        scan = null
        val p = sel ?: return@LaunchedEffect
        val b = base ?: return@LaunchedEffect
        val provider = p.qr ?: return@LaunchedEffect
        if (p.on(status) && !p.expired(status) && rescan != p.key) return@LaunchedEffect
        delay(450)
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
                    rescan = null
                    refresh(b)
                    scan = null
                    break
                }
                if (r.get("terminal")?.asBoolean == true) {
                    over = true
                    scan = Scan(null, (r.str("msg") ?: "二维码过期了") + "，按 OK 重新生成", true)
                    break
                }
                r.str("msg")?.let { scan = scan?.copy(msg = it) }
            }
        } finally {
            // 划走了：告诉插件别再等这个码
            if (!over) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { PluginPortal.api(b, "loginCancel", "provider" to provider, "taskId" to task) }
            }
        }
    }

    alt?.let { SettingsEntry(it); return }

    // ≡ 退出登录要连按两次
    var armed by remember { mutableStateOf<String?>(null) }
    var armedAt by remember { mutableLongStateOf(0L) }

    Row(
        Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 36.dp, bottom = Dimens.SafeV),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // ---------------- 左：各家网盘 ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            T("网盘登录", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            T(
                "网盘类线路要先登录对应的网盘才能播，没登录会提示「请先去配置中心登录」。登录信息只存在这台电视的插件里。",
                11.sp, Palette.T2, lines = 2, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 12.dp),
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(PanLogin.all, key = { it.key }) { p ->
                    val on = p.on(status) && !p.expired(status)
                    val st = p.status(status)
                    PanRow(
                        // 右边的标签已经写了登录没有；插件给了更细的说法（「有效」「VIP」之类）才写进副标题
                        p.name, if (st.isEmpty() || st in setOf("未登录", "已登录", "已失效")) p.how else "$st · ${p.how}",
                        Modifier.remembered(mem, p.key, isDefault = p == PanLogin.all.first())
                            .then(if (p == PanLogin.all.first()) Modifier.focusRequester(first) else Modifier),
                        onFocus = { if (sel != p) { sel = p; rescan = null } },
                        onClick = {
                            when {
                                base == null -> nav.toast(err ?: "插件还没准备好")
                                p.qr == null -> nav.toast("用手机扫右边的码，在打开的页面里登录")
                                on && rescan != p.key -> { rescan = p.key; seq++ }
                                else -> seq++
                            }
                        },
                        onMenu = {
                            val b = base ?: return@PanRow
                            if (!p.on(status)) { nav.toast("${p.name} 还没登录"); return@PanRow }
                            val now = System.currentTimeMillis()
                            if (armed == p.key && now - armedAt < 3000) {
                                armed = null
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { PluginPortal.api(b, p.clear) } }
                                    nav.toast(if (p.clear == "clearUc") "已退出 UC 网盘（Cookie 和 TV Token 都清了）" else "已退出${p.name}")
                                    refresh(b)
                                    rescan = null; seq++ // 退出了，直接出码好重新登录
                                }
                            } else { armed = p.key; armedAt = now; nav.toast("再按一次 ≡ 退出${p.name}") }
                        },
                    ) { fg ->
                        if (status != null) {
                            val (label, bg) = when {
                                p.expired(status) -> "已失效" to Palette.Bad
                                on -> "已登录" to Palette.Douban
                                else -> "未登录" to Color.Transparent
                            }
                            T(
                                label, 10.sp, if (bg == Color.Transparent) fg.copy(alpha = 0.55f) else Color.White, FontWeight.SemiBold,
                                Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                item {
                    PanRow(
                        "全部插件设置", "播放选项、网盘优先顺序、弹幕、备份等，用手机扫码打开插件自带的设置页",
                        Modifier.padding(top = 8.dp).remembered(mem, "all"),
                        onFocus = { sel = null; rescan = null },
                        onClick = { nav.toast("用手机扫右边的码") },
                        onMenu = null,
                    ) { fg -> T("›", 16.sp, fg) }
                }
            }
            Row(Modifier.padding(top = 8.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Hint("OK", "重新出二维码", 9.5.sp)
                Hint("≡", "连按两次退出登录", 9.5.sp)
                val who = PanLogin.loggedIn(status)
                if (who.isNotEmpty()) T("已登录 ${who.joinToString("、")}", 9.5.sp, Palette.T3)
            }
        }

        // ---------------- 右：二维码 ----------------
        Column(
            Modifier.width(330.dp).fillMaxHeight().background(Palette.Ghost6, RoundedCornerShape(14.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val b = base
            val p = sel
            when {
                b == null -> Panel("网盘登录", null, err ?: "正在唤醒插件…", "")
                p == null -> Panel(
                    "全部插件设置", PluginPortal.pageUrl(b), "手机和电视连同一个 Wi-Fi，扫码打开插件自带的设置页",
                    "只有带这串口令的地址能从手机打开。",
                )
                p.qr == null -> Panel(
                    p.name, PluginPortal.pageUrl(b, p.page),
                    "${p.how}：手机和电视连同一个 Wi-Fi，扫码打开配置中心的「${p.name}」一栏",
                    "当前：${p.status(status).ifEmpty { "读取中" }}。在手机上登录完，左边的状态几秒内会跟着变。",
                    lanMissing = b.lan == null,
                )
                p.on(status) && !p.expired(status) && rescan != p.key -> {
                    T(p.name, 15.sp, weight = FontWeight.Bold)
                    Box(Modifier.padding(top = 40.dp).size(200.dp), contentAlignment = Alignment.Center) {
                        T(p.status(status), 22.sp, Palette.Douban, FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    T("按 OK 重新扫码（换账号或登录过期时用）；连按两次 ≡ 退出登录。", 9.5.sp, Palette.T3, lines = 3)
                }
                else -> {
                    T(p.name, 15.sp, weight = FontWeight.Bold)
                    T(p.how, 10.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                    val s = scan
                    Box(
                        Modifier.size(200.dp).background(if (s?.image != null) Color.White else Palette.Ghost6, RoundedCornerShape(10.dp)).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            s?.image != null -> QrImage(s.image, Modifier.fillMaxSize())
                            s?.failed == true -> T("按 OK 重试", 12.sp, Palette.T2)
                            else -> T("…", 20.sp, Palette.T3)
                        }
                    }
                    T(s?.msg ?: "", 10.5.sp, if (s?.failed == true) Palette.Bad else Palette.T2, lines = 3, modifier = Modifier.padding(top = 14.dp))
                    Spacer(Modifier.weight(1f))
                    T("扫完在手机上点确认，这里会自动变成「已登录」。二维码几分钟后过期，过期了按 OK 再出一个。", 9.5.sp, Palette.T3, lines = 3)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (mem.lastKey == null) { delay(80); runCatching { first.requestFocus() } } }
}

/** 插件自带设置中心的入口：网盘扫码登录、账号都在里面，点进去插件自己弹二维码 */
@Composable
private fun SettingsEntry(st: PluginPortal.Settings) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 36.dp, bottom = Dimens.SafeV)) {
        T("网盘登录", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        T(
            "当前片源的插件把网盘登录放在它自带的「${st.site.name.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()}」里：进去选对应网盘的「扫码登录」，插件会弹出二维码，用那家网盘的 App 扫。登录信息只存在这台电视的插件里。",
            11.sp, Palette.T2, lines = 3, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 18.dp).width(560.dp),
        )
        PanRow(
            "打开插件设置中心", "夸克、UC、阿里、百度、115、天翼… 各家网盘的登录，以及播放、弹幕等插件选项",
            Modifier.width(420.dp).focusRequester(fr),
            onFocus = {}, onClick = { PluginSettingsActivity.open(ctx, st.jar, st.md5, st.site) }, onMenu = null,
        ) { fg -> T("›", 16.sp, fg) }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
}

@Composable
private fun ColumnScope.Panel(title: String, url: String?, sub: String, foot: String, lanMissing: Boolean = false) {
    T(title, 15.sp, weight = FontWeight.Bold)
    T(sub, 10.sp, Palette.T3, lines = 3, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
    if (url != null || lanMissing) {
        Box(
            Modifier.size(200.dp).background(if (url != null) Color.White else Palette.Ghost6, RoundedCornerShape(10.dp)).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) Qr(url, Modifier.fillMaxSize()) else T("电视没连上局域网，手机打不开", 11.sp, Palette.Bad, lines = 2)
        }
        if (url != null) T(url, 9.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 14.dp))
    }
    Spacer(Modifier.weight(1f))
    if (foot.isNotEmpty()) T(foot, 9.5.sp, Palette.T3, lines = 3)
}

/** 插件给的二维码：大多是链接（自己画），UC TV Token 直接给 data:image/png;base64 图 */
@Composable
internal fun QrImage(s: String, modifier: Modifier) {
    if (!s.startsWith("data:image")) { Qr(s, modifier); return }
    val bmp = remember(s) {
        runCatching {
            val bytes = Base64.decode(s.substringAfter(","), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, modifier) else T("二维码图片读不出来", 11.sp, Palette.Bg)
}

@Composable
private fun PanRow(
    title: String, sub: String, modifier: Modifier,
    onFocus: () -> Unit, onClick: () -> Unit, onMenu: (() -> Unit)?, trailing: @Composable (Color) -> Unit,
) {
    Focusable(onClick, modifier.fillMaxWidth(), corner = 9.dp, scale = 1.02f, ring = false, onMenu = onMenu, onFocus = onFocus) { f ->
        val fg = if (f) Palette.Bg else Palette.T1
        Row(
            Modifier.fillMaxWidth().height(46.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T(title, 12.5.sp, fg, FontWeight.SemiBold)
                T(sub, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
            }
            Box(contentAlignment = Alignment.CenterEnd) { trailing(fg) }
        }
    }
}

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
