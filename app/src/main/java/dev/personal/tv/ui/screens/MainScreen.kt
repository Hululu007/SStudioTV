package dev.personal.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.Tab
import dev.personal.tv.ui.components.Clock
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.components.remembered
import dev.personal.tv.ui.theme.Dimens
import dev.personal.tv.ui.theme.Palette

/** 首页 / 找片 / 想看 / 直播 几个标签共用的外壳：顶栏 + 内容 */
@Composable
fun MainScreen() {
    val nav = LocalNav.current
    val tabReqs = remember { Tab.entries.associateWith { FocusRequester() } }
    BackHandler(enabled = nav.tab != Tab.HOME) {
        nav.selectTab(Tab.HOME)
        runCatching { tabReqs.getValue(Tab.HOME).requestFocus() }
    }
    val holder = rememberSaveableStateHolder()
    val mem = nav.mem("main#${nav.tab.name}")
    Box(Modifier.fillMaxSize()) {
        holder.SaveableStateProvider(nav.tab.name) {
            when (nav.tab) {
                Tab.HOME -> HomeScreen(mem)
                // 找片 = 豆瓣找片 + 片源里各站点（见 SitesScreen）
                Tab.FIND -> SitesScreen(mem)
                Tab.WISH -> WishScreen(mem)
                Tab.LIVE -> LiveScreen(mem)
            }
        }
        TopBar(mem, tabReqs)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(mem: FocusMemory, tabReqs: Map<Tab, FocusRequester>) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.SafeH, end = Dimens.SafeH, top = 22.dp).height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .focusProperties { enter = { tabReqs.getValue(nav.tab) } }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Focusable(
                onClick = { nav.openSearch() },
                modifier = Modifier.padding(end = 8.dp).remembered(mem, "tab:search"),
                corner = 14.dp, scale = 1.1f, ring = false,
            ) { f ->
                Box(
                    Modifier.size(28.dp).background(if (f) Color.White else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Search, "搜索", Modifier.size(15.dp), tint = if (f) Palette.Bg else Color.White) }
            }
            Tab.entries.forEach { t ->
                val selected = nav.tab == t
                Focusable(
                    onClick = { nav.selectTab(t, focusContent = true) },
                    modifier = Modifier.focusRequester(tabReqs.getValue(t)).remembered(mem, "tab:${t.name}"),
                    corner = 14.dp, scale = 1.05f, ring = false,
                    onFocus = { nav.selectTab(t) },
                ) { f ->
                    Box(
                        Modifier.height(28.dp).background(if (f) Color.White else Color.Transparent)
                            .drawBehind {
                                if (selected && !f) {
                                    val inset = 12.dp.toPx()
                                    drawRoundRect(
                                        Color.White, Offset(inset, size.height - 4.dp.toPx()),
                                        Size(size.width - 2 * inset, 2.dp.toPx()), CornerRadius(1.dp.toPx()),
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        T(
                            t.label, 12.5.sp,
                            when { f -> Palette.Bg; selected -> Palette.T1; else -> Palette.T2 },
                            if (selected || f) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Clock()
        Spacer(Modifier.width(11.dp))
        Focusable(
            onClick = { nav.openSettings() },
            modifier = Modifier.remembered(mem, "tab:settings"),
            corner = 14.dp, scale = 1.1f, ring = false,
        ) { f ->
            Box(
                Modifier.size(28.dp).background(if (f) Color.White else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, "设置", Modifier.size(15.dp), tint = if (f) Palette.Bg else Palette.T3) }
        }
    }
}
