package dev.personal.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.personal.tv.ui.theme.EaseOut
import dev.personal.tv.ui.theme.Palette
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// 焦点记忆：返回上一页时，焦点回到离开时那张卡片上
// ---------------------------------------------------------------------------

/**
 * 每个页面（或首页的每个标签）一份。
 * lastKey 记录最后获得焦点的元素；pending = true 表示「下一次进入时需要把焦点放回去」。
 */
class FocusMemory {
    var lastKey: String? = null
    var pending: Boolean = true
        set(v) { field = v; if (v) restored = false }
    /** 本轮恢复中，记住的那个元素已经拿到过焦点（兜底元素就别再抢了） */
    var restored: Boolean = false
}

/**
 * 让一个可聚焦元素参与焦点记忆。
 * - 回到页面时，key 等于 lastKey 的元素自己抢焦点；
 * - 找不到（第一次进入，或者那张卡片已经不在了）时，由 isDefault 的元素兜底。
 */
fun Modifier.remembered(mem: FocusMemory, key: String, isDefault: Boolean = false): Modifier = composed {
    val req = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!mem.pending) return@LaunchedEffect
        val mine = mem.lastKey == key
        if (!mine && !isDefault) return@LaunchedEffect
        if (!mine && mem.lastKey != null) delay(160) // 先让记住的那个元素抢
        // 刚启动时窗口拿到焦点会把焦点交给第一个可聚焦元素（标签栏），所以要确认真的拿到了，没拿到就再要一次
        var hadAt = 0L
        repeat(10) {
            val now = withFrameNanos { it }
            if (!mine && mem.restored) return@LaunchedEffect
            if (!focused) {
                // 拿到过之后又丢了：250ms 内多半是窗口抢走的，再要回来；再晚就是用户自己按的，不抢
                if (hadAt != 0L && now - hadAt > 250_000_000L) { mem.pending = false; return@LaunchedEffect }
                runCatching { req.requestFocus() }
            }
            val t = withFrameNanos { it }
            if (focused) { if (hadAt == 0L) hadAt = t; if (mine) mem.restored = true }
            delay(60)
        }
        mem.pending = false
    }
    this.focusRequester(req).onFocusChanged {
        focused = it.isFocused
        // 恢复期间窗口会先把焦点塞给标签栏，这一下不能算数，否则记住的位置就被冲掉了
        if (it.isFocused && !(mem.pending && key.startsWith("tab:"))) mem.lastKey = key
    }
}

// ---------------------------------------------------------------------------
// 遥控器点击：OK 键抬起时触发，长按 OK 或按菜单键触发 onMenu
// ---------------------------------------------------------------------------

private fun KeyEvent.isConfirm() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * 不用 clickable：clickable 在触摸模式下不可聚焦，电视模拟器上用鼠标点过一次就会导致焦点丢失。
 * 只在本元素上见过按下才响应抬起，避免上一页的 OK 抬起误触发下一页的按钮。
 */
fun Modifier.dpadClick(onClick: () -> Unit, onMenu: (() -> Unit)? = null): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var menuDown by remember { mutableStateOf(false) }
    this
        .onKeyEvent { e ->
            when {
                e.isConfirm() && e.type == KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) { down = true; longFired = false }
                    else if (e.nativeKeyEvent.repeatCount == 1 && onMenu != null && down) { longFired = true; onMenu() }
                    true
                }
                e.isConfirm() && e.type == KeyEventType.KeyUp -> {
                    val fire = down && !longFired
                    down = false; longFired = false
                    if (fire) onClick()
                    true
                }
                e.key == Key.Menu && onMenu != null -> {
                    if (e.type == KeyEventType.KeyDown) menuDown = true
                    else if (e.type == KeyEventType.KeyUp && menuDown) { menuDown = false; onMenu() }
                    true
                }
                else -> false
            }
        }
        .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }, onLongPress = { onMenu?.invoke() }) }
}

// ---------------------------------------------------------------------------
// 焦点样式：放大 1.08 + 3px 深色间隙 + 4px 白圈（设计稿 px），180ms 缓出
// ---------------------------------------------------------------------------

/**
 * 统一的可聚焦容器。content 拿到 focused 自己决定配色；ring = false 的用于按钮/胶囊（聚焦变白底）。
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    scale: Float = 1.08f,
    ring: Boolean = true,
    onMenu: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    clip: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (focused) 1f else 0f, tween(180, easing = EaseOut), label = "focus")
    val density = LocalDensity.current
    val gap = with(density) { 1.5.dp.toPx() }
    val w = with(density) { 2.dp.toPx() }
    val r = with(density) { corner.toPx() }
    val shape = remember(corner) { RoundedCornerShape(corner) }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) onFocus?.invoke()
                }
            }
            .graphicsLayer {
                val s = 1f + (scale - 1f) * p
                scaleX = s; scaleY = s
            }
            .drawWithContent {
                drawContent()
                if (ring && p > 0.01f) {
                    val o1 = gap / 2
                    drawRoundRect(
                        Palette.Bg.copy(alpha = p), Offset(-o1, -o1), Size(size.width + 2 * o1, size.height + 2 * o1),
                        CornerRadius(r + o1), style = Stroke(gap),
                    )
                    val o2 = gap + w / 2
                    drawRoundRect(
                        Color.White.copy(alpha = p), Offset(-o2, -o2), Size(size.width + 2 * o2, size.height + 2 * o2),
                        CornerRadius(r + o2), style = Stroke(w),
                    )
                }
            }
            .dpadClick(onClick, onMenu)
            .focusable()
            .then(if (clip) Modifier.clip(shape) else Modifier),
    ) { content(focused) }
}

// ---------------------------------------------------------------------------
// 滚动跟随：焦点元素离边缘至少留 pad，不贴边、也不乱跳
// ---------------------------------------------------------------------------

class EdgeSpec(private val pad: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val p = pad.coerceAtMost((containerSize - size).coerceAtLeast(0f) / 2)
        val lead = offset - p
        val trail = offset + size - (containerSize - p)
        return when {
            lead < 0 -> lead
            trail > 0 -> trail
            else -> 0f
        }
    }
}

/** 网格按行翻：焦点行一旦超出可见区，就把它对齐到顶部 */
class TopAlignSpec(private val pad: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lead = offset - pad
        return if (lead < 0 || offset + size > containerSize) lead else 0f
    }
}

@Composable
fun TopAlignScroll(pad: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { pad.toPx() }
    val spec = remember(px) { TopAlignSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** 用于详情页这种「整屏切换」的容器：由代码显式滚动，焦点移动本身不触发滚动 */
object NoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@Composable
fun EdgeScroll(pad: Dp = 48.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { pad.toPx() }
    val spec = remember(px) { EdgeSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@Composable
fun NoScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec, content = content)
}
