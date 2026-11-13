package dev.personal.tv.ui.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import dev.personal.tv.data.Catalog
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.media.Downloads
import dev.personal.tv.source.Eps
import dev.personal.tv.source.Lines
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.theme.Palette

/** 「1.2 GB」「320 MB」 */
fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b shr 20} MB"
    b > 0 -> "${(b shr 10).coerceAtLeast(1)} KB"
    else -> "0 MB"
}

/** 一集在列表里显示的名字：「第 3 集」或者「11.01 上」 */
private fun epLabel(m: Downloads.Meta) =
    Eps.text(m.epName, Eps.number(m.epName, m.titleName) ?: m.epIdx, m.titleName)

/** 安卓 13 起通知要权限：没有就问一次（不给也能下，只是看不到通知栏进度） */
@Composable
private fun rememberAskNotify(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

/**
 * 详情页「下载」：选线路、选集，在后台下载。
 * 线路默认用上次播的那条（能播的最稳），直链线路排前面（不用解析，下载地址不容易过期）。
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(t: Title, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val askNotify = rememberAskNotify()
    val set = Lines.get(t.id)
    val lines = remember(set?.lines?.size) {
        set?.let { Lines.ordered(it, Store.progress[t.id]?.line, emptyList()) }.orEmpty().filter { it.episodes.isNotEmpty() }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = PC.Card) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Tx("下载《${t.name}》", 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Tx(
                (if (Prefs.dlMetered) "后台下载" else "连上 Wi‑Fi 时后台下载") + "，下好的在「我的 › 下载」，没网也能看",
                12.5.sp, Palette.T3, lines = 2, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
            )
            if (lines.isEmpty()) {
                Empty("还没有线路", "等详情页找到线路再下载")
                return@Column
            }
            var li by remember { mutableIntStateOf(0) }
            val line = lines[li.coerceIn(0, lines.lastIndex)]
            Tx("线路", 13.sp, Palette.T2, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
            PillRow(lines.map { "${it.siteName} · ${it.flag}" + if (it.direct) " · 直链" else "" }, li) { li = it }

            val sel = remember(line.key) { mutableStateListOf<Int>().apply { if (line.episodes.size == 1) add(1) } }
            val all = Downloads.all()
            val byId = all.associateBy { it.id }
            val numeric = Eps.numeric(line.episodes, t.name, t.isVariety)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Tx("选集 · ${line.episodes.size}", 13.sp, Palette.T2, modifier = Modifier.weight(1f))
                if (line.episodes.size > 1) {
                    val free = (1..line.episodes.size).filter { byId[Downloads.idOf(t.id, line.key, it)]?.let { s -> s.download == null && s.note?.startsWith("解析失败") == true || s.state == Download.STATE_FAILED } ?: true }
                    val allOn = free.isNotEmpty() && sel.containsAll(free)
                    Tx(
                        if (allOn) "清空" else "全选", 13.sp, Palette.T1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { sel.clear(); if (!allOn) sel.addAll(free) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(if (numeric) 62.dp else 150.dp),
                Modifier.fillMaxWidth().heightIn(max = 340.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(line.episodes) { i, e ->
                    val idx = i + 1
                    val s = byId[Downloads.idOf(t.id, line.key, idx)]
                    val on = idx in sel
                    val mark = when {
                        s == null -> null
                        s.done -> "✓"
                        s.state == Download.STATE_FAILED || s.note?.startsWith("解析失败") == true -> "!"
                        s.download != null -> "${s.percent.toInt()}%"
                        else -> "…"
                    }
                    val locked = mark != null && mark != "!"
                    val label = if (numeric) (Eps.number(e.name, t.name) ?: idx).toString() else Eps.text(e.name, idx, t.name, false)
                    Column(
                        Modifier.height(48.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Palette.T1 else Color.White.copy(alpha = if (locked) 0.03f else 0.07f))
                            .clickable(enabled = !locked) { if (on) sel.remove(idx) else sel.add(idx) }
                            .padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val fg = if (on) PC.Bg else if (locked) Palette.T3 else Palette.T1
                        Tx(label, 14.sp, fg, if (on) FontWeight.SemiBold else FontWeight.Normal, align = TextAlign.Center)
                        if (mark != null) Tx(mark, 10.sp, if (mark == "!") Palette.Douban else if (mark == "✓") Palette.Star else Palette.T3)
                    }
                }
            }
            BigButton(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), primary = true, enabled = sel.isNotEmpty(),
                onClick = {
                    askNotify()
                    Downloads.add(t, line, sel.sorted())
                    nav.toast(if (Downloads.waitingForWifi) "已加入下载，连上 Wi‑Fi 后开始" else "已加入下载 ${sel.size} 集")
                    onDismiss()
                },
            ) { fg -> Tx(if (sel.isEmpty()) "选要下载的集" else "下载 ${sel.size} 集", 15.sp, fg, FontWeight.Bold) }
        }
    }
}

/** 「我的 › 下载」：按片分组，看进度、暂停、删除；下好的点一下就播（没网也行） */
@OptIn(UnstableApi::class)
@Composable
fun PhoneDownloads() {
    val nav = LocalNav.current
    val items = Downloads.all()
    val total = items.sumOf { it.bytes }
    val groups = items.groupBy { it.meta.titleId }.toList()
    var askDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    SubPage("下载", if (items.isEmpty()) null else "${items.count { it.done }}/${items.size} 集下好 · 占用 ${sizeText(total)}") {
        if (items.isEmpty()) {
            Empty("还没有下载", "在片子的详情页点「下载」，选好线路和集数，后台下载；下好了没网也能看（飞机上、地铁里）")
            return@SubPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (Downloads.waitingForWifi) item("wifi") {
                Card(Modifier.padding(top = 8.dp)) {
                    SettingRow("等 Wi‑Fi", "现在只连了流量，连上 Wi‑Fi 自动开始。要现在就下，打开「用流量也下载」", onClick = {
                        Prefs.dlMetered = true; Downloads.applyRequirements()
                    }) { Tx("用流量下", 13.sp, Palette.Douban, FontWeight.SemiBold) }
                }
            }
            groups.forEach { (id, list) ->
                val name = list.first().meta.titleName
                item("h$id") {
                    Row(Modifier.fillMaxWidth().padding(start = PC.Edge, end = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { if (Catalog.get(id) != null) nav.openDetail(id) }) {
                            Tx(name, 16.sp, weight = FontWeight.Bold)
                            Tx("${list.count { it.done }}/${list.size} 集 · ${sizeText(list.sumOf { it.bytes })}", 12.sp, Palette.T3)
                        }
                        Tx("全部删除", 13.sp, Palette.T2, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { askDelete = id to name }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                item("c$id") {
                    Card {
                        list.sortedBy { Eps.number(it.meta.epName, it.meta.titleName) ?: it.meta.epIdx }.forEachIndexed { i, it ->
                            if (i > 0) Divider()
                            EpisodeRow(it) { nav.play(it.meta.titleId, it.meta.epIdx, it.meta.epName) }
                        }
                    }
                }
            }
        }
    }
    askDelete?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            containerColor = PC.Card2,
            title = { Text("删除《$name》的下载？") },
            text = { Text("下好的和没下完的都删掉，空间马上腾出来") },
            confirmButton = { TextButton({ Downloads.removeTitle(id); askDelete = null }) { Text("删除", color = Palette.Douban) } },
            dismissButton = { TextButton({ askDelete = null }) { Text("取消", color = Palette.T2) } },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun EpisodeRow(it: Downloads.Item, play: () -> Unit) {
    val failed = it.state == Download.STATE_FAILED || it.download == null && it.note?.startsWith("解析失败") == true
    val status = when {
        it.done -> "已下载 · ${sizeText(it.bytes)}"
        it.download == null -> it.note ?: "等待解析"
        failed -> "下载失败（地址可能过期了），点重试"
        it.state == Download.STATE_STOPPED -> "已暂停 · ${it.percent.toInt()}%"
        it.state == Download.STATE_QUEUED -> if (Downloads.waitingForWifi) "等 Wi‑Fi" else "排队中"
        it.state == Download.STATE_DOWNLOADING -> "${it.percent.toInt()}% · ${sizeText(it.bytes)}"
        it.state == Download.STATE_REMOVING -> "正在删除…"
        else -> "${it.percent.toInt()}%"
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = it.done, onClick = play).padding(start = PC.Edge, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tx(epLabel(it.meta), 15.sp, if (it.done) Palette.T1 else Palette.T2, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Tx(it.meta.lineName, 11.sp, Palette.T3, modifier = Modifier.weight(1f, fill = false))
            }
            Tx(status, 12.sp, if (failed) Palette.Douban else Palette.T3, lines = 2, modifier = Modifier.padding(top = 2.dp))
            if (!it.done && it.download != null && !failed) {
                ThinProgress(it.percent / 100f, Modifier.padding(top = 6.dp, end = 10.dp).clip(RoundedCornerShape(2.dp)), Palette.Douban)
            }
        }
        val action: Pair<String, () -> Unit>? = when {
            it.done -> "播放" to play
            failed -> "重试" to { Downloads.retry(it) }
            it.download == null -> null
            it.state == Download.STATE_STOPPED -> "继续" to { Downloads.resume(it) }
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED -> "暂停" to { Downloads.pause(it) }
            else -> null
        }
        action?.let { (label, f) -> SmallAction(label, if (it.done) Palette.T1 else Palette.T2, f) }
        SmallAction("删除", Palette.T3) { Downloads.remove(it) }
    }
}

@Composable
private fun SmallAction(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Tx(text, 13.sp, color, FontWeight.SemiBold)
    }
}
