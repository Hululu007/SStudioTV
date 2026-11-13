package dev.personal.tv.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.ui.components.PosterArt
import dev.personal.tv.ui.theme.Palette

/** 手机界面的颜色：底色、文字沿用电视的 [Palette]，卡片和分隔线另配 */
object PC {
    val Bg = Palette.Bg
    val Card = Color(0xFF15181D)
    val Card2 = Color(0xFF1D2128)
    val Line = Color.White.copy(alpha = 0.07f)
    val Accent = Palette.Douban
    /** 页面左右留白 */
    val Edge = 16.dp
}

/** 手机界面：Material3 暗色，正常密度（不用电视的 960×540 缩放） */
@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PC.Accent, onPrimary = Color.White,
            secondary = Palette.Star, onSecondary = Color.Black,
            background = PC.Bg, onBackground = Palette.T1,
            surface = PC.Bg, onSurface = Palette.T1, onSurfaceVariant = Palette.T2,
            surfaceVariant = PC.Card2,
            surfaceContainer = PC.Card, surfaceContainerLow = PC.Card, surfaceContainerHigh = PC.Card2,
            surfaceContainerHighest = PC.Card2, surfaceContainerLowest = PC.Bg,
            secondaryContainer = Color.White.copy(alpha = 0.14f), onSecondaryContainer = Palette.T1,
            outline = Color.White.copy(alpha = 0.2f), outlineVariant = PC.Line,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = PC.Bg, contentColor = Palette.T1) { content() }
    }
}

/** 一行字 */
@Composable
fun Tx(
    text: String,
    size: TextUnit,
    color: Color = Palette.T1,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: TextAlign? = null,
) {
    Text(
        text, modifier,
        style = TextStyle(fontSize = size, color = color, fontWeight = weight, lineHeight = lineHeight, textAlign = align ?: TextAlign.Unspecified),
        maxLines = lines, overflow = TextOverflow.Ellipsis,
    )
}

/** 区块标题：左边粗体标题 + 小字，右边可放一个「全部 ›」 */
@Composable
fun Section(title: String, sub: String? = null, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = PC.Edge).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Tx(title, 17.sp, weight = FontWeight.Bold)
        if (sub != null) Tx(sub, 12.sp, Palette.T3, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Tx("$action ›", 12.5.sp, Palette.T2, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}

/** 选项胶囊 */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dropdown: Boolean = false,
    strong: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        selected && strong -> Palette.T1
        selected -> Color.White.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val fg = if (selected && strong) PC.Bg else if (selected) Palette.T1 else Palette.T2
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .height(32.dp).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tx(text, 13.sp, fg, if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (dropdown) Tx(" ▾", 11.sp, fg.copy(alpha = 0.6f))
    }
}

/** 一排横着滑的胶囊，选中的那个滑到看得见 */
@Composable
fun PillRow(items: List<String>, selected: Int, modifier: Modifier = Modifier, strong: Boolean = false, onPick: (Int) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (selected - 1).coerceAtLeast(0))
    LazyRow(
        modifier, state = state,
        contentPadding = PaddingValues(horizontal = PC.Edge),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items) { i, s -> Pill(s, i == selected, strong = strong) { onPick(i) } }
    }
}

/** 大按钮：主按钮白底黑字，次按钮半透明 */
@Composable
fun BigButton(
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    onClick: () -> Unit,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val bg = if (primary) Palette.T1 else Color.White.copy(alpha = 0.1f)
    val fg = if (primary) PC.Bg else Palette.T1
    Row(
        modifier.height(height).clip(RoundedCornerShape(12.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) { CompositionLocalProvider(LocalContentColor provides fg) { content(fg) } }
}

/** 豆瓣星星（按分数裁一截） */
@Composable
fun StarBar(score: Float, size: TextUnit) {
    Box {
        Tx("★★★★★", size, Color.White.copy(alpha = 0.18f))
        Box(Modifier.drawWithContent { clipRect(right = this.size.width * score / 10f) { this@drawWithContent.drawContent() } }) {
            Tx("★★★★★", size, Palette.Star)
        }
    }
}

/**
 * 海报格子：海报（右下角评分）+ 片名 + 年份地区。
 * 没线路的调暗；加了想看的右上角一颗心；[badge] 放在左上角（「文件夹」之类）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterTile(
    t: Title,
    modifier: Modifier = Modifier,
    ratio: Float = 2f / 3f,
    badge: String? = null,
    sub: String? = listOf(if (t.year > 0) "${t.year}" else "", t.region).filter { it.isNotEmpty() }.joinToString(" · "),
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLong),
        ) {
            val dim = t.lines == 0
            PosterArt(t, if (t.name.length > 4) 11.sp else 13.sp, Modifier.fillMaxSize().graphicsLayer { alpha = if (dim) 0.5f else 1f }, bottomPadding = 8.dp)
            if (dim) {
                Tx(
                    "暂无线路", 10.sp, Color.White.copy(alpha = 0.85f), FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            t.scoreText?.let {
                Tx(
                    it, 11.sp, Palette.Star, FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (t.id in Store.wish) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Favorite, null, Modifier.size(11.dp), tint = Palette.Star) }
            }
            if (badge != null) {
                Tx(
                    badge, 10.sp, Palette.T1, FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Tx(t.name, 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        if (!sub.isNullOrEmpty()) Tx(sub, 11.sp, Palette.T3, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * 往左右各撑出 [h]：网格有左右留白，里面占满一行的横滑列表、标题要贴到屏幕边（标题自己再留白）。
 * 不能用负的 padding（Compose 不允许）。
 */
fun Modifier.bleed(h: Dp = PC.Edge) = layout { m, c ->
    val px = h.roundToPx()
    val w = if (c.hasBoundedWidth) c.maxWidth + 2 * px else c.maxWidth
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout((p.width - 2 * px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}

/** 细进度条 */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.T1) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(color))
    }
}

/** 空状态：一句话 + 可选按钮 */
@Composable
fun Empty(title: String, sub: String = "", modifier: Modifier = Modifier, button: String? = null, onButton: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Tx(title, 15.sp, Palette.T2, FontWeight.SemiBold, align = TextAlign.Center, lines = 2)
        if (sub.isNotEmpty()) Tx(sub, 12.5.sp, Palette.T3, lines = 4, lineHeight = 18.sp, align = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (button != null && onButton != null) {
            BigButton(Modifier.padding(top = 18.dp), height = 40.dp, onClick = onButton) { Tx(button, 14.sp, it, FontWeight.SemiBold) }
        }
    }
}

/** 设置页里的一行：标题 + 说明，右边放开关或箭头 */
@Composable
fun SettingRow(title: String, sub: String = "", modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PC.Edge, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Tx(title, 15.sp)
            if (sub.isNotEmpty()) Tx(sub, 12.sp, Palette.T3, lines = 3, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** 圆角卡片容器 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = PC.Edge).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PC.Card)) { content() }
}

/** 卡片里的分隔线 */
@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(start = PC.Edge).height(0.5.dp).background(PC.Line))

/** 描边的小标签 */
@Composable
fun Tag(text: String, color: Color = Palette.T2) {
    Tx(
        text, 10.5.sp, color,
        modifier = Modifier.border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
