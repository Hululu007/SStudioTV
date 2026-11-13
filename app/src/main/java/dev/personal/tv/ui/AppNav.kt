package dev.personal.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.ui.components.EdgeScroll
import dev.personal.tv.ui.components.Focusable
import dev.personal.tv.ui.components.FocusMemory
import dev.personal.tv.ui.components.T
import dev.personal.tv.ui.screens.DetailScreen
import dev.personal.tv.ui.screens.MainScreen
import dev.personal.tv.ui.screens.PlayerScreen
import dev.personal.tv.ui.screens.SearchScreen
import dev.personal.tv.ui.screens.SettingsScreen
import dev.personal.tv.ui.screens.SourceLab
import dev.personal.tv.ui.screens.LabPlayer
import dev.personal.tv.ui.screens.LivePlayer
import dev.personal.tv.ui.screens.SourcesScreen
import dev.personal.tv.ui.screens.PansScreen
import dev.personal.tv.ui.theme.Palette
import dev.personal.tv.ui.theme.TvScale
import kotlinx.coroutines.delay

enum class Tab(val label: String) { HOME("首页"), FIND("找片"), WISH("想看"), LIVE("直播") }

sealed class Route(val key: String) {
    data object Main : Route("main")
    data object Search : Route("search")
    class Detail(val id: String, val similar: Boolean, n: Int) : Route("detail:$id:$n")
    class Player(val id: String, val ep: Int, val epName: String?, val openLines: Boolean, n: Int) : Route("player:$id:$n")
    data object Lab : Route("lab")
    data object Settings : Route("settings")
    data object Sources : Route("sources")
    data object Pans : Route("pans")
    data object Downloads : Route("downloads")
    class LivePlay(val group: Int, val channel: Int, n: Int) : Route("liveplay:$n")
    class LabPlay(val title: String, val url: String, val headers: Map<String, String>, val parse: Boolean, val format: String?, n: Int) : Route("labplay:$n")
}

/** 一个菜单项 */
class MenuItem(val label: String, val action: () -> Unit)

/**
 * 页面栈 + 焦点记忆 + 全局浮层（菜单、提示）。
 * 不用 navigation-compose：页面少，自己管栈更容易控制焦点回位。
 */
class Navigator {
    companion object {
        /** 当前的导航（debug 广播用） */
        var current: Navigator? = null
    }
    val stack = mutableStateListOf<Route>(Route.Main)
    var tab by mutableStateOf(Tab.HOME)
        private set
    private var seq = 0
    private val memories = HashMap<String, FocusMemory>()

    var menu by mutableStateOf<Pair<Title, List<MenuItem>>?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var toastSeq by mutableIntStateOf(0)
        private set

    fun mem(key: String): FocusMemory = memories.getOrPut(key) { FocusMemory() }

    /** 切标签。focusContent = false 时焦点留在标签栏上（标签是聚焦即切换的） */
    fun selectTab(t: Tab, focusContent: Boolean = false) {
        if (t == tab && !focusContent) return
        mem("main#${t.name}").pending = focusContent
        tab = t
    }

    fun openDetail(id: String, similar: Boolean = false) = push(Route.Detail(id, similar, ++seq))
    fun play(id: String, ep: Int, epName: String? = null, openLines: Boolean = false) = push(Route.Player(id, ep, epName, openLines, ++seq))
    fun openSearch() = push(Route.Search)
    fun openLab() = push(Route.Lab)
    fun openSettings() = push(Route.Settings)
    fun openSources() = push(Route.Sources)
    fun openPans() = push(Route.Pans)
    fun openDownloads() = push(Route.Downloads)
    fun playLive(group: Int, channel: Int) = push(Route.LivePlay(group, channel, ++seq))
    fun playUrl(title: String, url: String, headers: Map<String, String>, parse: Boolean, format: String? = null) =
        push(Route.LabPlay(title, url, headers, parse, format, ++seq))

    private fun push(r: Route) {
        memories.keys.filter { it.startsWith(r.key) }.forEach { memories.remove(it) }
        stack.add(r)
    }

    fun back(): Route? {
        if (stack.size <= 1) return null
        val gone = stack.removeAt(stack.lastIndex)
        memories.keys.filter { it.startsWith(gone.key) }.forEach { memories.remove(it) }
        // 回到的那一页：焦点放回离开时的位置
        val top = stack.last()
        val k = if (top == Route.Main) "main#${tab.name}" else top.key
        mem(k).pending = true
        return gone
    }

    fun showMenu(t: Title, items: List<MenuItem>) { menu = t to items }
    fun closeMenu() { menu = null }

    fun toast(msg: String) { toast = msg; toastSeq++ }
    fun clearToast() { toast = null }

    /** 海报上的通用菜单：打开详情 / 找类似 / 想看 / 不感兴趣 */
    fun posterMenu(t: Title, extra: List<MenuItem> = emptyList()) {
        val wished = t.id in Store.wish
        showMenu(
            t,
            listOf(
                MenuItem("打开详情") { openDetail(t.id) },
                MenuItem("找类似") { openDetail(t.id, similar = true) },
                MenuItem(if (wished) "移出想看" else "加入想看") {
                    Store.toggleWish(t.id)
                    toast(if (wished) "已移出想看" else "已加入想看 · 只存在本机")
                },
            ) + extra + MenuItem("不感兴趣") {
                Store.hide(t.id)
                toast("不会再推荐《${t.name}》")
            },
        )
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }

@Composable
fun AppNav() {
    val nav = remember { Navigator().also { Navigator.current = it } }
    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalNav provides nav) {
        BackHandler(enabled = nav.stack.size > 1) {
            nav.back()?.let { holder.removeState(it.key) }
        }
        // Compose 1.7 在电视上默认用「居中跟随」滚动，这里统一换成「离边缘留白、不多滚」
        EdgeScroll(40.dp) { Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = nav.stack.last(), animationSpec = tween(220), label = "route") { r ->
                holder.SaveableStateProvider(r.key) {
                    when (r) {
                        Route.Main -> MainScreen()
                        Route.Search -> SearchScreen()
                        is Route.Detail -> DetailScreen(r)
                        is Route.Player -> PlayerScreen(r)
                        Route.Lab -> SourceLab()
                        Route.Settings -> SettingsScreen()
                        Route.Sources -> SourcesScreen()
                        Route.Pans -> PansScreen()
                        Route.Downloads -> SettingsScreen() // 电视上不下载，没有入口
                        is Route.LivePlay -> LivePlayer(r)
                        is Route.LabPlay -> LabPlayer(r)
                    }
                }
            }
            ToastHost(nav)
        } }
        nav.menu?.let { (t, items) -> ContextMenu(t, items, nav) }
    }
}

@Composable
private fun ToastHost(nav: Navigator) {
    val seq = nav.toastSeq
    LaunchedEffect(seq) {
        if (nav.toast != null) { delay(2600); nav.clearToast() }
    }
    var last by remember { mutableStateOf("") }
    nav.toast?.let { last = it }
    Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = nav.toast != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 2 },
        ) {
            T(
                last, 11.sp, Palette.T1,
                modifier = Modifier.background(Color(0xE6181B21), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ContextMenu(t: Title, items: List<MenuItem>, nav: Navigator) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = nav::closeMenu, properties = DialogProperties(usePlatformDefaultWidth = false)) { TvScale {
        Column(
            Modifier.width(260.dp).background(Palette.Panel, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            T(t.name, 17.sp, weight = FontWeight.Bold, serif = true, modifier = Modifier.padding(start = 10.dp))
            T(t.summary, 10.sp, Palette.T3, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            items.forEachIndexed { i, item ->
                Focusable(
                    onClick = { nav.closeMenu(); item.action() },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    corner = 9.dp, scale = 1.03f, ring = false,
                ) { f ->
                    Box(
                        Modifier.fillMaxWidth().height(38.dp).background(if (f) Color.White else Color.Transparent)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        T(item.label, 12.5.sp, if (f) Palette.Bg else Palette.T1, if (f) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    } }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}
