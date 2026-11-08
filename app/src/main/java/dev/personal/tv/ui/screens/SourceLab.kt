package dev.personal.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.source.Lab
import dev.personal.tv.source.SiteKind
import dev.personal.tv.source.Vod
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.components.Chip
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 源调试：加载影视仓配置 → 选站点 → 搜索 / 首页 → 详情 → 选集播放。
 * 只是用来验证真实配置能不能跑通，后面会被正式的「多源合并」替换掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceLab() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberScopeSafe()
    val mem = nav.mem("lab")
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 22.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("源调试", 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            T(Lab.busy?.let { "⋯ $it" } ?: Lab.log.firstOrNull().orEmpty(), 10.5.sp, if (Lab.busy != null) Palette.Star else Palette.T2, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---- A：配置 + 站点 ----
            Column(Modifier.width(230.dp).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Lab.presets.forEachIndexed { i, (name, url) ->
                        Chip(
                            name, Lab.configName == name,
                            onClick = { scope.launch { Lab.loadConfig(name, url) } },
                            modifier = Modifier.remembered(mem, "cfg:$name", isDefault = i == 0)
                                .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                            size = 11.sp, height = 26.dp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val sites = Lab.config?.sites.orEmpty()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(sites, key = { _, s -> s.key }) { _, s ->
                        val sel = Lab.site?.key == s.key
                        Focusable(
                            onClick = { Lab.site = s; Lab.results = emptyList(); Lab.vod = null },
                            modifier = Modifier.fillMaxWidth().remembered(mem, "site:${s.key}"),
                            corner = 7.dp, scale = 1.02f, ring = false,
                        ) { f ->
                            Row(
                                Modifier.fillMaxWidth().height(28.dp)
                                    .background(when { f -> Color.White; sel -> Palette.Ghost; else -> Color.Transparent })
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val fg = when { f -> Palette.Bg; !s.kind.supported -> Palette.T3; else -> Palette.T1 }
                                T(s.name, 11.sp, fg, if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                T(s.kind.label + if (s.kind == SiteKind.JAR) " " + s.api.removePrefix("csp_") else "", 8.5.sp, fg.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
            // ---- B：关键词 + 结果 ----
            Column(Modifier.width(250.dp).fillMaxHeight()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Lab.keywords.forEach { k ->
                        Chip(
                            k, false,
                            onClick = { if (Lab.site != null) scope.launch { Lab.query(ctx, k) } else nav.toast("先在左边选一个站点") },
                            modifier = Modifier.remembered(mem, "kw:$k"),
                            size = 10.sp, height = 22.dp, idleBg = Palette.Ghost6,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(Lab.results, key = { i, v -> "$i:${v.id}" }) { i, v ->
                        ResultRow(v, Lab.vod?.id == v.id, Modifier.remembered(mem, "res:$i")) { scope.launch { Lab.open(ctx, v) } }
                    }
                }
            }
            // ---- C：详情 + 选集 ----
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                val v = Lab.vod
                if (v == null) {
                    T("选一条结果看详情", 11.sp, Palette.T3)
                    Spacer(Modifier.height(16.dp))
                    Lab.log.drop(1).take(12).forEach { T(it, 9.5.sp, Palette.T3, lines = 2, modifier = Modifier.padding(bottom = 4.dp)) }
                    return@Column
                }
                T(v.name, 16.sp, weight = FontWeight.Bold)
                T(listOf(v.year, v.area, v.typeName, v.remarks).filter { it.isNotBlank() }.joinToString(" · "), 10.sp, Palette.T2, modifier = Modifier.padding(top = 3.dp))
                if (v.content.isNotBlank()) T(v.content, 9.5.sp, Palette.T3, lines = 2, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    v.flags.forEachIndexed { i, fl ->
                        Chip(
                            "${fl.name} ${fl.episodes.size}", Lab.flagIdx == i,
                            onClick = { Lab.flagIdx = i },
                            modifier = Modifier.remembered(mem, "flag:$i"),
                            size = 9.5.sp, height = 22.dp, selectedBg = Palette.Douban, selectedFg = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val flag = v.flags.getOrNull(Lab.flagIdx)
                if (flag != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        flag.episodes.take(120).forEachIndexed { i, ep ->
                            Focusable(
                                onClick = {
                                    scope.launch {
                                        val p = Lab.resolve(ctx, flag.name, ep) ?: return@launch
                                        Lab.probe(p)
                                        Lab.playRequest = "${v.name} · ${ep.name}" to p
                                    }
                                },
                                modifier = Modifier.remembered(mem, "ep:${Lab.flagIdx}:$i"),
                                corner = 6.dp, scale = 1.06f, ring = false,
                            ) { f ->
                                Box(
                                    Modifier.height(26.dp).background(if (f) Color.White else Palette.Ghost6, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) { T(ep.name, 10.sp, if (f) Palette.Bg else Palette.T1) }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Lab.playRequest) {
        val (title, p) = Lab.playRequest ?: return@LaunchedEffect
        Lab.playRequest = null
        nav.playUrl(title, p.url, p.headers, p.parse, p.format)
    }
    LaunchedEffect(Unit) {
        if (mem.lastKey == null) { delay(80); runCatching { first.requestFocus() } }
    }
}

@Composable
private fun ResultRow(v: Vod, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.fillMaxWidth(), corner = 7.dp, scale = 1.02f, ring = false) { f ->
        Column(
            Modifier.fillMaxWidth()
                .background(when { f -> Color.White; selected -> Palette.Ghost; else -> Palette.Ghost4 })
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            T(v.name, 11.5.sp, if (f) Palette.Bg else Palette.T1, FontWeight.SemiBold)
            val sub = listOf(v.remarks, v.year, v.typeName).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) T(sub, 9.sp, if (f) Palette.Bg.copy(alpha = 0.6f) else Palette.T3)
        }
    }
}

/** 页面离开后协程别跟着取消：配置加载、jar 初始化比较慢，回来还能看到结果 */
@Composable
private fun rememberScopeSafe() = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main) }
