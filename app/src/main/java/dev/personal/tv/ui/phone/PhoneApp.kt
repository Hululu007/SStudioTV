package dev.personal.tv.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Title
import dev.personal.tv.ui.LocalNav
import dev.personal.tv.ui.MenuItem
import dev.personal.tv.ui.Navigator
import dev.personal.tv.ui.Route
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/** 手机的底部标签 */
enum class PTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    FIND("找片", Icons.AutoMirrored.Filled.List),
    LIVE("直播", Icons.Filled.PlayArrow),
    WISH("想看", Icons.Filled.Favorite),
    MINE("我的", Icons.Filled.Person),
}

/**
 * 手机界面的外壳：页面栈和电视共用 [Navigator]（详情、播放、搜索的路由一样），界面按手机重排。
 * 主页面是底部五个标签；长按海报弹底部菜单（[Navigator.posterMenu]）；提示在底部浮出来。
 */
@Composable
fun PhoneApp() {
    val nav = remember { Navigator().also { Navigator.current = it } }
    val holder = rememberSaveableStateHolder()
    var tab by rememberSaveable { mutableStateOf(PTab.HOME) }
    CompositionLocalProvider(LocalNav provides nav) {
        BackHandler(enabled = nav.stack.size > 1) { nav.back()?.let { holder.removeState(it.key) } }
        // 主页面上按返回：不在首页先回首页
        BackHandler(enabled = nav.stack.size == 1 && tab != PTab.HOME) { tab = PTab.HOME }
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = nav.stack.last(), animationSpec = tween(180), label = "route") { r ->
                holder.SaveableStateProvider(r.key) {
                    when (r) {
                        Route.Main -> PhoneMain(tab) { tab = it }
                        Route.Search -> PhoneSearch()
                        is Route.Detail -> PhoneDetail(r)
                        is Route.Player -> PhonePlayer(r)
                        Route.Sources -> PhoneSources()
                        Route.Pans -> PhonePans()
                        Route.Settings -> SubPage("设置") { PhoneMine(header = false) }
                        is Route.LivePlay -> PhoneLivePlayer(r)
                        else -> SubPage("手机版没有这一页") { Empty("这一页只有电视界面有", "到「我的 → 界面」切换成电视界面再用") }
                    }
                }
            }
            PhoneToast(nav)
        }
        nav.menu?.let { (t, items) -> MenuSheet(t, items, nav) }
    }
}

/** 主页面：上面是当前标签的内容，下面是标签栏 */
@Composable
private fun PhoneMain(tab: PTab, select: (PTab) -> Unit) {
    val holder = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    PTab.HOME -> PhoneHome { select(it) }
                    PTab.FIND -> PhoneFind()
                    PTab.LIVE -> PhoneLive()
                    PTab.WISH -> PhoneWish()
                    PTab.MINE -> PhoneMine()
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(PC.Card).navigationBarsPadding().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PTab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { select(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, t.label, Modifier.size(22.dp), tint = if (on) Palette.T1 else Palette.T3)
                    Tx(t.label, 10.5.sp, if (on) Palette.T1 else Palette.T3, if (on) FontWeight.SemiBold else FontWeight.Normal, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** 二级页面：顶栏（返回 + 标题）+ 内容 */
@Composable
fun SubPage(title: String, sub: String? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        TopBar(title, sub, onBack = { nav.back() }, actions = actions)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
fun TopBar(title: String, sub: String? = null, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Palette.T1)
            }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 4.dp)) {
            Tx(title, 18.sp, weight = FontWeight.Bold)
            if (sub != null) Tx(sub, 11.5.sp, Palette.T3)
        }
        actions()
    }
}

@Composable
private fun PhoneToast(nav: Navigator) {
    val seq = nav.toastSeq
    LaunchedEffect(seq) { if (nav.toast != null) { delay(2600); nav.clearToast() } }
    var last by remember { mutableStateOf("") }
    nav.toast?.let { last = it }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 80.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = nav.toast != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            Tx(
                last, 13.5.sp, Palette.T1, lines = 3,
                modifier = Modifier.background(Color(0xF0262A31), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** 长按海报的菜单：从底下拉出来 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(t: Title, items: List<MenuItem>, nav: Navigator) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = nav::closeMenu, sheetState = state, containerColor = PC.Card) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Tx(t.name, 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Tx(t.summary, 12.5.sp, Palette.T3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp))
            items.forEach { item ->
                Tx(
                    item.label, 15.5.sp,
                    modifier = Modifier.fillMaxWidth().clickable { nav.closeMenu(); item.action() }.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** 拿到当前 Activity（改方向、亮度、全屏用） */
@Composable
fun currentActivity() = LocalContext.current.findActivity()
