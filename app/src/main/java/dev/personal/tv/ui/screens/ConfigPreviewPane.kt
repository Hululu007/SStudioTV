package dev.personal.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.source.ConfigPreview
import dev.personal.tv.source.PlayFlag
import dev.personal.tv.source.SiteCfg
import dev.personal.tv.source.Sources
import dev.personal.tv.source.TvConfig
import dev.personal.tv.source.Vod
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.Hint
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 已保存配置的只读预览：配置概况 → 站点（CMS 可点进去）→ 最近更新 → 一部片的集目录。
 * 全程不改当前片源、不进缓存；JAR/JS 站点只列出来不调用；每一步有超时，返回键取消并回到上一层。
 */
@Composable
fun ConfigPreviewPane(entry: Sources.Entry, onExit: () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val anchor = remember { FocusRequester() }

    var config by remember { mutableStateOf<TvConfig?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var site by remember { mutableStateOf<SiteCfg?>(null) }
    var shows by remember { mutableStateOf<List<Vod>?>(null) }
    var show by remember { mutableStateOf<Vod?>(null) }
    var flags by remember { mutableStateOf<List<PlayFlag>?>(null) }
    var expanded by remember(show) { mutableStateOf<PlayFlag?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var gen by remember { mutableStateOf(0) } // 每次换层 / 刷新焦点用
    val seq = remember { intArrayOf(0) }

    fun msg(e: Throwable) = if (e is TimeoutCancellationException) "超时了，站点没响应" else e.message?.take(80) ?: e.javaClass.simpleName

    /** 起一个有界任务：返回键会 cancel 它；出错写进 err 不抛 */
    fun run(label: String, block: suspend () -> Unit) {
        job?.cancel()
        val token = ++seq[0]
        busy = label; err = null
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { if (e is TimeoutCancellationException) err = msg(e) else throw e }
            catch (e: Throwable) { err = msg(e) }
            finally { if (token == seq[0]) { busy = null; gen++ } } // 被取消 / 被新任务顶掉的不动界面状态
        }
    }

    LaunchedEffect(entry.url) { run("正在下载配置…") { config = ConfigPreview.config(entry) } }
    LaunchedEffect(gen, busy) { if (busy == null) { delay(60); runCatching { anchor.requestFocus() } } }

    // 返回：取消进行中的加载；否则退一层
    BackHandler {
        when {
            busy != null -> {
                job?.cancel(); busy = null; err = null
                // 取消后回到发起前的那一层
                if (show != null && flags == null) show = null
                else if (site != null && shows == null) site = null
                else if (config == null) onExit()
                gen++
            }
            expanded != null -> { expanded = null; gen++ }
            show != null -> { show = null; flags = null; err = null; gen++ }
            site != null -> { site = null; shows = null; err = null; gen++ }
            else -> onExit()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 36.dp, bottom = Dimens.SafeV),
    ) {
        val crumbs = listOfNotNull(entry.name, site?.name, show?.name).joinToString(" › ")
        T("预览配置", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        T(crumbs, 12.sp, Palette.T2, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        T(
            "只读预览：不会切换当前片源（现在是「${Sources.name}」），也不会运行插件",
            10.sp, Palette.T3, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 12.dp),
        )

        Column(Modifier.weight(1f).fillMaxWidth()) {
            busy?.let { T("$it  （返回键取消）", 12.sp, Palette.T2, modifier = Modifier.padding(12.dp)) }
            err?.let { T("✗ $it", 12.sp, Palette.Bad, lines = 2, modifier = Modifier.padding(12.dp)) }
            val c = config
            when {
                busy != null -> {}
                c == null -> {}
                expanded != null -> {
                    val f = expanded!!
                    T("${f.name} · 全部 ${f.episodes.size} 集", 13.sp, Palette.T2)
                    LazyColumn { items(f.episodes.size) { i ->
                        Row_(i == 0, anchor) { foc -> T(f.episodes[i].name, 12.sp, if (foc) Palette.Bg else Palette.T1) }
                    } }
                }
                show != null -> {
                    val fl = flags.orEmpty()
                    if (fl.isEmpty() && err == null) T("这部片没有集目录", 12.sp, Palette.T3, modifier = Modifier.padding(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(fl.size) { i ->
                            val f = fl[i]
                            Row_(i == 0, anchor, onClick = { expanded = f; gen++ }) { foc ->
                                T("${f.name} · ${f.episodes.size} 集 · OK 查看全部", 13.sp, if (foc) Palette.Bg else Palette.T1, FontWeight.SemiBold)
                                T(
                                    f.episodes.take(12).joinToString("  ") { it.name } + if (f.episodes.size > 12) " …" else "",
                                    10.sp, if (foc) Palette.Bg.copy(alpha = 0.6f) else Palette.T3, lines = 2,
                                )
                            }
                        }
                    }
                }
                site != null -> {
                    val list = shows.orEmpty()
                    if (list.isEmpty() && err == null) T("这个站点首页没有内容", 12.sp, Palette.T3, modifier = Modifier.padding(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(list.size) { i ->
                            val v = list[i]
                            Row_(i == 0, anchor, onClick = {
                                val s = site ?: return@Row_
                                show = v; flags = null
                                run("正在取集目录…") { flags = ConfigPreview.catalog(s, v) }
                            }) { foc ->
                                T(v.name, 13.sp, if (foc) Palette.Bg else Palette.T1, FontWeight.SemiBold)
                                T(
                                    listOf(v.remarks, v.typeName, v.year).filter { it.isNotBlank() }.joinToString(" · ") +
                                        if (v.flags.isNotEmpty()) "  · ${v.flags.size} 个来源" else "",
                                    10.sp, if (foc) Palette.Bg.copy(alpha = 0.6f) else Palette.T3,
                                )
                            }
                        }
                    }
                }
                else -> {
                    val usable = c.sites.count { it.kind.supported && it.searchable && it.notVideo == null }
                    val kinds = c.sites.groupingBy { it.kind }.eachCount().entries.joinToString(" · ") { "${it.key.label} ${it.value}" }
                    T(
                        "${c.sites.size} 个站点（$kinds）· $usable 个影视站能搜 · ${c.lives.size} 个直播源 · ${c.parses.size} 个解析" +
                            if (c.spider != null) " · 带插件 jar（不会下载）" else "",
                        11.sp, Palette.T2, lines = 2, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(c.sites.size) { i ->
                            val s = c.sites[i]
                            val ok = ConfigPreview.canBrowse(s)
                            Row_(i == 0, anchor, onClick = {
                                if (!ok) { nav.toast("${s.kind.label} 站点要运行插件，预览里不执行"); return@Row_ }
                                site = s; shows = null
                                run("正在取「${s.name}」最近更新…") { shows = ConfigPreview.recent(s) }
                            }) { foc ->
                                T(s.name, 13.sp, if (foc) Palette.Bg else if (ok) Palette.T1 else Palette.T2, FontWeight.SemiBold)
                                T(
                                    buildString {
                                        append(s.kind.label)
                                        if (!s.searchable) append(" · 不参与搜索")
                                        s.notVideo?.let { append(" · 非影视（$it）") }
                                        append(if (ok) " · OK 看最近更新" else " · 仅列出")
                                    },
                                    10.sp, if (foc) Palette.Bg.copy(alpha = 0.6f) else Palette.T3,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Hint("OK", "进入", 9.5.sp)
            Hint("返回", if (busy != null) "取消" else "上一层", 9.5.sp)
        }
    }
}

/** 预览里的一行：第一行拿焦点锚点；onClick 为空就是纯展示（仍可聚焦，方便上下翻） */
@Composable
private fun Row_(first: Boolean, anchor: FocusRequester, onClick: () -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.(Boolean) -> Unit) {
    Focusable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (first) Modifier.focusRequester(anchor) else Modifier),
        corner = 9.dp, scale = 1.02f, ring = false,
    ) { f ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).background(if (f) Color.White else Color.Transparent).padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Column(Modifier.weight(1f)) { content(f) } }
    }
}
