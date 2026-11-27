package dev.personal.tv.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.personal.tv.data.Title
import dev.personal.tv.link.ControlRequest
import dev.personal.tv.link.DiscoveredTv
import dev.personal.tv.link.LinkQr
import dev.personal.tv.link.PlaybackState
import dev.personal.tv.link.SourcesResponse
import dev.personal.tv.link.TvLinkClient
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Navigator
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 供详情页、播放页调用的一键投屏接力播 */
fun castTitleToTv(
    scope: CoroutineScope,
    nav: Navigator,
    title: Title,
    ep: Int = 1,
    epName: String? = null,
    posMs: Long = -1L,
    onSent: (() -> Unit)? = null,
) {
    if (TvLinkClient.activeTv == null) {
        nav.toast("先连接一台局域网电视")
        nav.openTvRemote()
        return
    }
    scope.launch {
        TvLinkClient.cast(title, ep, epName, posMs).fold(
            { msg ->
                onSent?.invoke()
                nav.toast(msg)
            },
            { e -> nav.toast("投屏失败：${e.message?.take(56)}") },
        )
    }
}

/** 手机端电视联动主页面：自动发现配对、遥控播放、选集/换线、搜片发电视、双向同步、管理电视片源 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhoneTvRemote() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = TvLinkClient.activeTv
    val st = TvLinkClient.status

    var showDevices by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showLines by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        TvLinkClient.startDiscovery(ctx)
        onDispose { TvLinkClient.stopDiscovery() }
    }

    LaunchedEffect(tv?.deviceId) {
        if (tv == null) return@LaunchedEffect
        while (true) {
            TvLinkClient.refreshStatus()
            delay(1500)
        }
    }

    fun sendCmd(req: ControlRequest, quiet: Boolean = false) {
        scope.launch {
            TvLinkClient.control(req).fold(
                { msg ->
                    if (!quiet && msg.isNotEmpty()) nav.toast(msg)
                    TvLinkClient.refreshStatus()
                },
                { e -> nav.toast(e.message?.take(56) ?: "操作失败") },
            )
        }
    }

    SubPage(
        title = tv?.name ?: "连接电视",
        sub = when {
            tv == null -> "同一 Wi‑Fi 下自动发现电视"
            TvLinkClient.online -> "在线 · ${tv.host}:${tv.port}"
            else -> "离线或电视未打开 · ${tv.host}:${tv.port}"
        },
        actions = {
            if (tv != null) {
                SmallChip("换电视") { showDevices = true }
                Spacer(Modifier.width(8.dp))
            }
        },
    ) {
        if (tv == null) {
            ConnectPanel(
                onPaired = { paired ->
                    nav.toast("已配对 ${paired.name}")
                },
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. 正在播放 / 播控卡片
                val pb = st?.playback
                if (pb != null) {
                    NowPlayingCard(
                        pb = pb,
                        onTogglePlay = { sendCmd(ControlRequest("toggle_play"), quiet = true) },
                        onSeekBy = { sec -> sendCmd(ControlRequest("seek_by", deltaSec = sec), quiet = true) },
                        onSeekTo = { ms -> sendCmd(ControlRequest("seek_to", posMs = ms), quiet = true) },
                        onPrevEp = { sendCmd(ControlRequest("prev_ep")) },
                        onNextEp = { sendCmd(ControlRequest("next_ep")) },
                        onSpeed = { sp -> sendCmd(ControlRequest("set_speed", speed = sp)) },
                        onRetry = { sendCmd(ControlRequest("retry")) },
                        onEpisodes = { showEpisodes = true },
                        onLines = {
                            sendCmd(ControlRequest("discover_lines"), quiet = true)
                            showLines = true
                        },
                    )
                } else {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (TvLinkClient.online) Palette.Douban else Palette.T3),
                                )
                                Tx(
                                    if (TvLinkClient.online) "电视当前没有在播放影片" else "连不上电视",
                                    15.sp,
                                    weight = FontWeight.SemiBold,
                                )
                            }
                            Tx(
                                if (TvLinkClient.online) "在手机任意影片详情或播放页点「投电视」，或在下方搜片直接发给电视播放。"
                                else "请确认电视已打开 SStudioTV，且与手机连接同一 Wi‑Fi。",
                                12.5.sp,
                                Palette.T3,
                                lines = 3,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }

                // 2. 音量 & 导航按键
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tx("遥控按键", 14.sp, weight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            st?.volume?.let { v ->
                                Tx(if (v.muted) "已静音" else "音量 ${v.level}/${v.max}", 12.sp, Palette.T3)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteBtn("音量 −", Modifier.weight(1f)) { sendCmd(ControlRequest("vol_down"), quiet = true) }
                            RemoteBtn(if (st?.volume?.muted == true) "取消静音" else "静音", Modifier.weight(1f)) { sendCmd(ControlRequest("vol_mute"), quiet = true) }
                            RemoteBtn("音量 +", Modifier.weight(1f)) { sendCmd(ControlRequest("vol_up"), quiet = true) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteBtn("返回上一页", Modifier.weight(1f)) { sendCmd(ControlRequest("back"), quiet = true) }
                            RemoteBtn("回到电视首页", Modifier.weight(1f)) { sendCmd(ControlRequest("home"), quiet = true) }
                        }
                    }
                }

                // 3. 手机打字发片到电视
                SendSearchCard(
                    onSend = { q -> sendCmd(ControlRequest("search", query = q)) },
                )

                // 4. 同步与电视片源
                Card {
                    SettingRow(
                        if (TvLinkClient.syncing) "正在双向同步…" else "双向同步想看与观看进度",
                        TvLinkClient.lastSyncSummary?.let { "上次同步：$it" }
                            ?: "在局域网内合并手机和电视的观看进度、想看列表，不经过云端",
                        onClick = {
                            if (TvLinkClient.syncing) return@SettingRow
                            scope.launch {
                                TvLinkClient.syncNow().fold(
                                    { r ->
                                        val tip = if (r.progressUpdated == 0 && r.wishChanged == 0) "已同步，两边都是最新"
                                        else "同步完成：进度 ${r.progressUpdated} · 想看 ${r.wishChanged}"
                                        nav.toast(tip)
                                    },
                                    { e -> nav.toast("同步失败：${e.message?.take(56)}") },
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp), tint = Palette.T2)
                    }
                    Divider()
                    SettingRow(
                        "管理电视片源",
                        "查看、切换或给电视添加影视仓 / TVBox 配置地址",
                        onClick = { showSources = true },
                    ) { Tx("›", 20.sp, Palette.T3) }
                }
            }
        }
    }

    if (showDevices) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showDevices = false }, sheetState = sheetState, containerColor = PC.Card) {
            Box(Modifier.heightIn(max = 580.dp)) {
                ConnectPanel(
                    onPaired = { paired ->
                        nav.toast("已切换到 ${paired.name}")
                        showDevices = false
                    },
                    onSelected = { showDevices = false },
                )
            }
        }
    }

    if (showSources && tv != null) {
        TvSourcesSheet(onDismiss = { showSources = false })
    }

    if (showEpisodes && st?.playback != null) {
        val pb = st.playback
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showEpisodes = false }, sheetState = sheetState, containerColor = PC.Card) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = PC.Edge, vertical = 8.dp)) {
                Tx("选集 · ${pb.titleName}", 16.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                FlowRow(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pb.episodes.forEach { ep ->
                        val active = (pb.epName != null && pb.epName == ep.name) || (pb.epName == null && pb.ep == ep.index)
                        Pill(ep.name, active) {
                            showEpisodes = false
                            sendCmd(ControlRequest("select_ep", ep = ep.index, epName = ep.name))
                        }
                    }
                }
            }
        }
    }

    if (showLines && st?.playback != null) {
        val pb = st.playback
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showLines = false }, sheetState = sheetState, containerColor = PC.Card) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = PC.Edge, vertical = 8.dp)) {
                Tx("切换电视线路", 16.sp, weight = FontWeight.Bold)
                Tx("电视正在用它自己的片源（含 JAR / 网盘 4K 线路）", 12.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                if (pb.lines.isEmpty()) {
                    Tx("正在搜线路，稍等 1–2 秒再看…", 13.sp, Palette.T3, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(pb.lines, key = { it.key }) { l ->
                            val cur = l.key == pb.lineKey
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (cur) Palette.Douban.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable {
                                        showLines = false
                                        sendCmd(ControlRequest("switch_line", lineKey = l.key))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Tx("${l.siteName} · ${l.flag}", 14.sp, if (cur) Palette.Douban else Palette.T1, FontWeight.Medium)
                                    Tx("${l.epCount} 集" + (if (l.direct) " · 直链" else "") + (if (l.failed) " · 之前播不了" else ""), 11.5.sp, Palette.T3)
                                }
                                if (cur) Tag("当前", Palette.Douban)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    pb: PlaybackState,
    onTogglePlay: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevEp: () -> Unit,
    onNextEp: () -> Unit,
    onSpeed: (Float) -> Unit,
    onRetry: () -> Unit,
    onEpisodes: () -> Unit,
    onLines: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val frac = if (dragging) dragFrac else if (pb.durMs > 0) (pb.posMs.toFloat() / pb.durMs).coerceIn(0f, 1f) else 0f

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Tx(pb.titleName, 17.sp, weight = FontWeight.Bold)
                    val subtitle = buildString {
                        if (pb.episodeCount > 1 || !pb.epName.isNullOrBlank()) append(pb.epText)
                        if (!pb.lineName.isNullOrBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(pb.lineName)
                        }
                    }
                    if (subtitle.isNotEmpty()) Tx(subtitle, 12.sp, Palette.T2, modifier = Modifier.padding(top = 2.dp))
                }
                if (pb.fail != null) {
                    SmallChip("重试", onClick = onRetry)
                }
            }

            if (pb.fail != null) {
                Tx(pb.fail, 12.5.sp, Palette.Bad, lines = 2)
            } else if (pb.status != null) {
                Tx(pb.status, 12.5.sp, Palette.Star)
            }

            // 进度条
            Column {
                Slider(
                    value = frac,
                    enabled = pb.durMs > 0,
                    onValueChange = {
                        dragging = true
                        dragFrac = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        if (pb.durMs > 0) onSeekTo((dragFrac * pb.durMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Palette.Douban,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
                Row(Modifier.fillMaxWidth()) {
                    val shownPos = if (dragging && pb.durMs > 0) (dragFrac * pb.durMs).toLong() else pb.posMs
                    Tx(formatDuration(shownPos), 11.5.sp, Palette.T3)
                    Spacer(Modifier.weight(1f))
                    Tx(formatDuration(pb.durMs), 11.5.sp, Palette.T3)
                }
            }

            // 主播控
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteBtn("−15s", Modifier.weight(1f)) { onSeekBy(-15) }
                BigButton(Modifier.weight(1.4f), primary = true, height = 44.dp, onClick = onTogglePlay) { fg ->
                    Tx(if (pb.playing) "暂停" else "播放", 15.sp, fg, FontWeight.Bold)
                }
                RemoteBtn("+15s", Modifier.weight(1f)) { onSeekBy(15) }
            }

            // 集数 & 线路
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteBtn("上一集", Modifier.weight(1f), enabled = pb.ep > 1, onClick = onPrevEp)
                RemoteBtn("下一集", Modifier.weight(1f), enabled = pb.nextName != null, onClick = onNextEp)
                RemoteBtn("选集", Modifier.weight(1f), enabled = pb.episodes.size > 1, onClick = onEpisodes)
                RemoteBtn("换线路", Modifier.weight(1f), onClick = onLines)
            }

            // 倍速
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tx("倍速", 12.sp, Palette.T3)
                listOf(1f, 1.25f, 1.5f, 2f).forEach { sp ->
                    val on = kotlin.math.abs(pb.speed - sp) < 0.05f
                    Pill("${if (sp == sp.toInt().toFloat()) sp.toInt() else sp}x", on) { onSpeed(sp) }
                }
            }
        }
    }
}

@Composable
private fun SendSearchCard(onSend: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val kb = LocalSoftwareKeyboardController.current
    fun submit() {
        val text = q.trim()
        if (text.isEmpty()) return
        kb?.hide()
        onSend(text)
        q = ""
    }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tx("手机打字发给电视", 14.sp, weight = FontWeight.SemiBold)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PC.Card2)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = Palette.T3)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Tx("输入片名，直接让电视搜并打开…", 13.5.sp, Palette.T3)
                    BasicTextField(
                        value = q,
                        onValueChange = { q = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.T1, fontSize = 14.sp),
                        cursorBrush = SolidColor(Palette.Douban),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (q.isNotBlank()) {
                    SmallChip("发送", accent = true, onClick = ::submit)
                }
            }
        }
    }
}

@Composable
private fun ConnectPanel(
    onPaired: (dev.personal.tv.link.PairedTv) -> Unit,
    onSelected: (() -> Unit)? = null,
) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current

    var addressInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var selectedDiscovered by remember { mutableStateOf<DiscoveredTv?>(null) }

    fun submitPair() {
        if (pairing) return
        val addr = addressInput.trim()
        val code = codeInput.trim()
        if (addr.isEmpty()) {
            nav.toast("请先选一台电视，或输入电视局域网地址")
            return
        }
        kb?.hide()
        pairing = true
        scope.launch {
            TvLinkClient.pairWithInput(addr, code.takeIf { it.isNotEmpty() }).fold(
                { tv ->
                    addressInput = ""
                    codeInput = ""
                    selectedDiscovered = null
                    onPaired(tv)
                },
                { e -> nav.toast(e.message?.take(64) ?: "配对失败") },
            )
            pairing = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (TvLinkClient.pairedTvs.isNotEmpty()) {
            Group("已配对的电视")
            Card {
                TvLinkClient.pairedTvs.forEachIndexed { idx, tv ->
                    if (idx > 0) Divider()
                    val active = tv.deviceId == TvLinkClient.activeTv?.deviceId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                TvLinkClient.selectTv(tv.deviceId)
                                onSelected?.invoke()
                            }
                            .padding(horizontal = PC.Edge, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tx(tv.name, 15.sp, weight = FontWeight.Medium)
                                if (active) Tag("当前", Palette.Douban)
                            }
                            Tx("${tv.host}:${tv.port}", 12.sp, Palette.T3, modifier = Modifier.padding(top = 2.dp))
                        }
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { TvLinkClient.unpair(tv.deviceId) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Delete, "移除", Modifier.size(18.dp), tint = Palette.T3)
                        }
                    }
                }
            }
        }

        Group(if (TvLinkClient.discovering) "局域网自动发现（搜索中…）" else "局域网发现的电视")
        Card {
            val found = TvLinkClient.discoveredTvs
            if (found.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Tx("暂未发现电视", 14.sp, Palette.T2)
                    Tx("请确保电视已打开 SStudioTV，且与手机在同一个 Wi‑Fi 下；也可以在下方手动填电视 IP。", 12.sp, Palette.T3, lines = 3, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                found.forEachIndexed { idx, d ->
                    if (idx > 0) Divider()
                    val picked = selectedDiscovered?.host == d.host && selectedDiscovered?.port == d.port
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (picked) Palette.Douban.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable {
                                selectedDiscovered = d
                                addressInput = "${d.host}:${d.port}"
                            }
                            .padding(horizontal = PC.Edge, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Tx(d.name, 15.sp, weight = FontWeight.Medium)
                            Tx("${d.host}:${d.port}" + (if (d.version.isNotEmpty()) " · v${d.version}" else ""), 12.sp, Palette.T3)
                        }
                        Tag(if (picked) "已选中" else "选择", if (picked) Palette.Douban else Palette.T2)
                    }
                }
            }
        }

        Group("配对电视")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tx("在电视「设置 → 手机联动」里查看 6 位配对码或地址。", 12.sp, Palette.T3, lines = 2)
                InputBox(
                    value = addressInput,
                    onValueChange = {
                        addressInput = it
                        // 粘贴 sstudiotv://pair?... 链接时自动提取配对码
                        LinkQr.parseInput(it)?.code?.let { parsedCode ->
                            if (codeInput.isBlank()) codeInput = parsedCode
                        }
                    },
                    placeholder = "电视地址（如 192.168.1.10:9981）或配对链接",
                    keyboardType = KeyboardType.Uri,
                )
                InputBox(
                    value = codeInput,
                    onValueChange = { codeInput = it.filter { c -> c.isDigit() || c == ' ' }.take(7) },
                    placeholder = "电视上显示的 6 位配对码",
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    onDone = ::submitPair,
                )
                BigButton(Modifier.fillMaxWidth(), primary = true, height = 42.dp, onClick = ::submitPair) { fg ->
                    Tx(if (pairing) "正在配对…" else "连接并配对", 14.5.sp, fg, FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvSourcesSheet(onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    var data by remember { mutableStateOf<SourcesResponse?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            TvLinkClient.listTvSources().fold(
                { data = it; err = null },
                { err = it.message ?: "获取失败" },
            )
        }
    }
    LaunchedEffect(Unit) { reload() }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = PC.Card) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PC.Edge)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tx("管理电视片源", 17.sp, weight = FontWeight.Bold)
            Tx("直接给电视添加影视仓 / TVBox 配置地址，或切换电视当前使用的片源。", 12.sp, Palette.T3, lines = 2)
            InputBox(urlInput, { urlInput = it }, "配置地址（http://… 或多仓地址）", KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    InputBox(nameInput, { nameInput = it }, "名字（可选）")
                }
                BigButton(height = 42.dp, primary = true, onClick = {
                    val u = urlInput.trim()
                    if (u.isEmpty() || busy) return@BigButton
                    kb?.hide()
                    busy = true
                    scope.launch {
                        TvLinkClient.addTvSource(u, nameInput.trim().takeIf { it.isNotEmpty() }, select = false).fold(
                            { res ->
                                data = res
                                urlInput = ""
                                nameInput = ""
                                nav.toast("已添加到电视（${res.added?.size ?: 1} 个）")
                            },
                            { e -> nav.toast("添加失败：${e.message?.take(56)}") },
                        )
                        busy = false
                    }
                }) { fg -> Tx(if (busy) "添加中…" else "添加到电视", 13.5.sp, fg, FontWeight.SemiBold) }
            }

            if (err != null) {
                Tx(err!!, 12.5.sp, Palette.Bad)
            } else if (data == null) {
                Tx("正在读取电视片源…", 13.sp, Palette.T3, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                val list = data!!.list
                if (list.isEmpty()) {
                    Tx("电视上还没有片源", 13.sp, Palette.T3, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(list, key = { it.url }) { e ->
                            val cur = e.url == data!!.currentUrl
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (cur) Palette.Douban.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable {
                                        if (cur || busy) return@clickable
                                        scope.launch {
                                            TvLinkClient.selectTvSource(e.url).fold(
                                                { data = it; nav.toast("电视已切换到「${e.name}」") },
                                                { ex -> nav.toast("切换失败：${ex.message?.take(48)}") },
                                            )
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Tx(e.name, 14.sp, if (cur) Palette.Douban else Palette.T1, FontWeight.Medium)
                                        if (cur) Tag("当前", Palette.Douban)
                                    }
                                    Tx(e.url, 11.sp, Palette.T3)
                                }
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            scope.launch {
                                                TvLinkClient.removeTvSource(e.url).fold(
                                                    { data = it; nav.toast("已从电视删除「${e.name}」") },
                                                    { ex -> nav.toast("删除失败：${ex.message?.take(48)}") },
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Delete, "删除", Modifier.size(17.dp), tint = Palette.T3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBtn(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) PC.Card2 else PC.Card2.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Tx(label, 13.sp, if (enabled) Palette.T1 else Palette.T3, FontWeight.Medium)
    }
}

@Composable
private fun SmallChip(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (accent) Palette.Douban else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Tx(label, 12.5.sp, if (accent) Color.White else Palette.T1, FontWeight.SemiBold)
    }
}

@Composable
private fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PC.Card2)
            .border(0.5.dp, PC.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Tx(placeholder, 13.5.sp, Palette.T3)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.T1, fontSize = 14.sp),
            cursorBrush = SolidColor(Palette.Douban),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val sec = ms / 1000L
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
