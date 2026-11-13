package dev.personal.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.audio.AudioFx
import dev.personal.tv.data.Prefs
import dev.personal.tv.data.SearchIndex
import dev.personal.tv.source.Lines
import dev.personal.tv.source.PanLogin
import dev.personal.tv.source.SiteCfg
import dev.personal.tv.source.SiteKind
import dev.personal.tv.source.Sources
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.Toggle
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * 设置：片源、播放、线路偏好（每个站点 默认 / 优先 / 不用）。
 */
@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val mem = nav.mem("settings")
    val first = remember { FocusRequester() }
    var sites by remember { mutableStateOf<List<SiteCfg>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Sources.url) {
        if (Sources.empty) { sites = null; err = "还没有片源，先到「片源」里加一个"; return@LaunchedEffect }
        try {
            sites = Sources.config().sites
            err = null
        } catch (e: Throwable) { err = "片源配置加载失败：${e.message?.take(60)}" }
    }

    Row(
        Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 36.dp, bottom = Dimens.SafeV),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        // ---------------- 左：常规 ----------------
        Column(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("设置", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp, start = 12.dp))
            Section("片源")
            Row(
                if (Sources.empty) "片源 · 还没有" else "片源 · ${Sources.name}",
                sites?.let { s -> "${s.count { it.kind.supported && it.searchable && it.notVideo == null }} 个影视站能搜 · 添加、切换、删除" } ?: err ?: "加载中…",
                Modifier.focusRequester(first).remembered(mem, "sources", isDefault = true),
                onClick = { nav.openSources() },
            ) { fg -> T("›", 16.sp, fg) }
            if (!Sources.empty) Row(
                "网盘登录",
                PanLogin.loggedIn().takeIf { it.isNotEmpty() }?.let { "已登录 ${it.joinToString("、")}" } ?: "夸克、UC、百度、115… 每家分开扫码登录",
                Modifier.remembered(mem, "pans"),
                onClick = { nav.openPans() },
            ) { fg -> T("›", 16.sp, fg) }
            Section("播放")
            Row("自动换线路", "解析不出、播放出错或卡住 20 秒，自动换下一条", Modifier.remembered(mem, "auto"), onClick = { Prefs.autoLine = !Prefs.autoLine }) { fg ->
                Toggle(Prefs.autoLine, onLight = fg == Palette.Bg)
            }
            Row("夜间音量", "压缩响度差：爆炸声不炸，对白不小", Modifier.remembered(mem, "night"), onClick = { AudioFx.night = !AudioFx.night }) { fg ->
                Toggle(AudioFx.night, onLight = fg == Palette.Bg)
            }
            Section("显示")
            Row("海报大小 · ${Prefs.posterLabel}", "首页、找片、搜索、想看的海报都跟着变", Modifier.remembered(mem, "poster"), onClick = { Prefs.posterSize = (Prefs.posterSize + 1) % 3 }) { fg ->
                T(listOf("小", "中", "大").joinToString("  ") { if (it == Prefs.posterLabel) "●$it" else it }, 11.sp, fg)
            }
            Section("片库")
            Row(
                "搜索索引 · ${SearchIndex.size} 部",
                SearchIndex.progress?.let { "正在建索引 $it 组，建完前也能搜" } ?: "豆瓣片名，首字母搜索在这里找；每 7 天自动更新",
                Modifier.remembered(mem, "index"), onClick = {},
            ) { }
        }

        // ---------------- 右：线路偏好 ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            T("线路偏好", 15.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 12.dp))
            T(
                "OK 键切换：默认 → 优先 → 不用。播放时先试「优先」的站点（按序号），「不用」的站点不搜也不播。",
                10.sp, Palette.T3, lines = 2, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 10.dp),
            )
            val list = sites
            when {
                list == null -> T(err ?: "正在加载「${Sources.name}」…", 11.sp, Palette.T3, modifier = Modifier.padding(start = 12.dp))
                else -> {
                    // 能用的在前；同类里保持配置里的顺序
                    val ordered = list.sortedBy { if (!(it.kind.supported && it.searchable)) 2 else if (it.notVideo != null) 1 else 0 }
                    LazyColumn(Modifier.weight(1f)) {
                        items(ordered, key = { it.key }) { s -> SiteRow(s, Modifier.remembered(mem, "site:${s.key}")) }
                    }
                    Row(Modifier.padding(top = 8.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Hint("OK", "切换", 9.5.sp)
                        Hint("≡", "优先的往前挪", 9.5.sp)
                        T("优先 ${Prefs.preferred.size} · 不用 ${Prefs.blocked.size}", 9.5.sp, Palette.T3)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (mem.lastKey == null) { delay(80); runCatching { first.requestFocus() } } }
}

@Composable
private fun SiteRow(s: SiteCfg, modifier: Modifier) {
    val nav = LocalNav.current
    val usable = s.kind.supported && s.searchable
    val pref = Prefs.pref(s.key)
    val rank = Prefs.rank(s.key)
    Row(
        s.name,
        when {
            !s.kind.supported -> "${s.kind.label} · 暂不支持"
            !s.searchable -> "${s.kind.label} · 站点不支持搜索，合并线路时用不上"
            s.notVideo != null && Prefs.rank(s.key) == null -> "${s.notVideo} · 看着不是影视站，找线路时不搜它；设成「优先」就会搜"
            else -> s.kind.label + if (s.kind == SiteKind.JAR) " · " + s.api.removePrefix("csp_") else ""
        },
        modifier,
        dim = !usable,
        onClick = {
            if (!usable) { nav.toast("这个站点用不上，不用设置"); return@Row }
            val p = Prefs.cycle(s.key)
            // 不用 / 恢复会改变要搜的站点，已经搜过的线路作废
            if (p == Prefs.SitePref.BLOCK || p == Prefs.SitePref.DEFAULT) Lines.clear()
        },
        onMenu = { if (rank != null && rank > 0) Prefs.moveUp(s.key) },
    ) { fg ->
        if (usable) {
            val (label, bg) = when (pref) {
                Prefs.SitePref.PREFER -> "优先 ${rank!! + 1}" to Palette.Douban
                Prefs.SitePref.BLOCK -> "不用" to Palette.Bad
                Prefs.SitePref.DEFAULT -> "默认" to Color.Transparent
            }
            T(
                label, 10.sp, if (bg == Color.Transparent) fg.copy(alpha = 0.55f) else Color.White, FontWeight.SemiBold,
                Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    T(text, 10.sp, Palette.T3, FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Row(
    title: String, sub: String, modifier: Modifier, dim: Boolean = false,
    onClick: () -> Unit, onMenu: (() -> Unit)? = null, trailing: @Composable (Color) -> Unit,
) {
    Focusable(onClick, modifier.fillMaxWidth().padding(vertical = 1.dp), corner = 9.dp, scale = 1.02f, ring = false, onMenu = onMenu) { f ->
        val fg = if (f) Palette.Bg else Palette.T1
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().height(46.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T(title, 12.5.sp, if (dim && !f) Palette.T3 else fg, FontWeight.SemiBold)
                T(sub, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
            }
            Box(contentAlignment = Alignment.CenterEnd) { trailing(fg) }
        }
    }
}
