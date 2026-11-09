package dev.personal.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.personal.tv.data.Store
import dev.personal.tv.data.Title
import dev.personal.tv.ui.theme.Palette
import dev.personal.tv.ui.theme.Serif
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// 文字
// ---------------------------------------------------------------------------

@Composable
fun T(
    text: String,
    size: TextUnit,
    color: Color = Palette.T1,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    serif: Boolean = false,
    spacing: TextUnit = TextUnit.Unspecified,
    lines: Int = 1,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontSize = size,
            color = color,
            fontWeight = weight,
            fontFamily = if (serif) Serif else FontFamily.Default,
            letterSpacing = spacing,
            lineHeight = lineHeight,
        ),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 片名大字：衬线、加粗、字距拉开。
 * 一行放不下先缩字号（最小到六成），还放不下再折成两行——长片名（「3年A班：从现在起，大家都是人质」）不再被省略号吃掉。
 * 量好之前不画，免得先闪一下大字。
 */
@Composable
fun DisplayTitle(text: String, size: TextUnit, modifier: Modifier = Modifier, spacing: Float = 0.06f) {
    var scale by remember(text, size) { mutableStateOf(1f) }
    var lines by remember(text, size) { mutableStateOf(1) }
    var ready by remember(text, size) { mutableStateOf(false) }
    val fs = size * scale
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        style = TextStyle(
            fontSize = fs, color = Palette.T1, fontWeight = FontWeight.ExtraBold, fontFamily = Serif,
            letterSpacing = spacing.em, lineHeight = fs * 1.15f,
        ),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r ->
            when {
                !r.hasVisualOverflow -> ready = true
                scale > 0.62f -> scale *= 0.88f
                lines == 1 -> lines = 2
                else -> ready = true
            }
        },
    )
}

@Composable
fun Dot() = T("·", 12.sp, Palette.T3, modifier = Modifier.padding(horizontal = 7.dp))

// ---------------------------------------------------------------------------
// 豆瓣评分
// ---------------------------------------------------------------------------

@Composable
fun DoubanTag(height: Dp = 15.dp, fontSize: TextUnit = 9.sp) {
    Box(
        Modifier.height(height).background(Palette.Douban, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { T("豆瓣", fontSize, Color.White, FontWeight.Bold, spacing = 0.04.em) }
}

@Composable
fun Stars(score: Float, size: TextUnit) {
    Box {
        T("★★★★★", size, Color.White.copy(alpha = 0.18f), spacing = 0.1.em)
        T(
            "★★★★★", size, Palette.Star, spacing = 0.1.em,
            modifier = Modifier.drawWithContent { clipRect(right = this.size.width * score / 10f) { this@drawWithContent.drawContent() } },
        )
    }
}

@Composable
fun Score(t: Title, size: TextUnit, star: Boolean = false) {
    val s = t.scoreText
    if (s == null) T("暂无评分", size * 0.85f, Palette.T3)
    else T(if (star) "★ $s" else s, size, Palette.Star, FontWeight.Bold)
}

// ---------------------------------------------------------------------------
// 遥控器按键提示
// ---------------------------------------------------------------------------

@Composable
fun KeyCap(key: String) {
    Box(
        Modifier.defaultMinSize(minWidth = 17.dp).height(15.dp)
            .border(0.75.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.5.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { T(key, 7.5.sp, Palette.T2, FontWeight.SemiBold) }
}

@Composable
fun Hint(key: String, text: String, size: TextUnit = 10.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KeyCap(key)
        Spacer(Modifier.width(4.dp))
        T(text, size, Palette.T3)
    }
}

// ---------------------------------------------------------------------------
// 海报
// ---------------------------------------------------------------------------

/** 竖版海报卡片本体（不含下方文字），没线路时调暗并标「暂无线路」 */
@Composable
fun PosterCard(
    t: Title,
    titleSize: TextUnit,
    corner: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    showWish: Boolean = false,
    badge: String? = null,
) {
    val dim = t.lines == 0
    Focusable(onClick, modifier.aspectRatio(2f / 3f), corner = corner, onMenu = onMenu, onFocus = onFocus) {
        PosterArt(
            t, titleSize,
            Modifier.fillMaxSize().then(
                if (dim) Modifier.graphicsLayer { alpha = 0.45f } else Modifier,
            ),
            bottomPadding = 9.dp,
        )
        if (dim) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            T(
                "暂无线路", 8.5.sp, Color.White.copy(alpha = 0.8f), FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (showWish && t.id in Store.wish) {
            Box(
                Modifier.padding(5.dp).size(15.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Favorite, null, Modifier.size(9.dp), tint = Palette.Star) }
        }
        if (badge != null) {
            T(
                badge, 8.sp, Palette.T1, FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.5.dp),
            )
        }
    }
}

/** 海报 + 片名评分 + 年份地区，找片 / 想看 / 搜索的网格单元 */
@Composable
fun PosterCell(
    t: Title,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    sub: String = "${t.year} · ${t.region}",
    starScore: Boolean = false,
    badge: String? = null,
    /** 挂在可聚焦的海报本体上（焦点记忆、FocusRequester 之类），外层 Column 拿不到焦点 */
    focusModifier: Modifier = Modifier,
) {
    Column(modifier) {
        PosterCard(
            t, titleSize = if (t.name.length > 4) 11.sp else 12.sp, corner = 7.dp,
            modifier = focusModifier.fillMaxWidth(), onClick = onClick, onMenu = onMenu, onFocus = onFocus, showWish = true, badge = badge,
        )
        val a = if (t.lines == 0) 0.45f else 1f
        // 片名单独占两行（一行放不下的长片名、带副标题的不再被「…」吃掉），评分挪到下一行。
        // 两行的高度固定留出来，同一排的格子下面的字才对得齐
        val nameH = with(androidx.compose.ui.platform.LocalDensity.current) { (14.sp * 2).toDp() }
        Column(
            // 聚焦放大 + 白圈大约外扩 8dp，文字要让开
            Modifier.fillMaxWidth().padding(top = 10.dp).graphicsLayer { alpha = a },
        ) {
            T(t.name, 10.5.sp, weight = FontWeight.SemiBold, lines = 2, lineHeight = 14.sp, modifier = Modifier.heightIn(min = nameH))
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Score(t, 8.5.sp, star = starScore)
                if (sub.isNotBlank()) T(sub, 8.sp, Palette.T3)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 按钮、胶囊、分段
// ---------------------------------------------------------------------------

/** 胶囊按钮：未聚焦半透明，聚焦白底黑字 */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    primary: Boolean = false,
    onFocus: (() -> Unit)? = null,
    content: @Composable RowScope.(fg: Color) -> Unit,
) {
    Focusable(onClick, modifier, corner = height / 2, scale = 1.05f, ring = false, onFocus = onFocus) { f ->
        val bg = when {
            f -> Color.White
            primary -> Color.White.copy(alpha = 0.9f)
            else -> Palette.Ghost
        }
        val fg = if (f || primary) Palette.Bg else Palette.T1
        Row(
            Modifier.height(height).background(bg).padding(horizontal = height * 0.47f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content(fg) }
    }
}

/** 选项胶囊：selected 表示当前选中，聚焦时白底 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: TextUnit = 10.sp,
    height: Dp = 20.dp,
    onFocus: (() -> Unit)? = null,
    selectedBg: Color = Color.White.copy(alpha = 0.16f),
    idleBg: Color = Color.Transparent,
    dropdown: Boolean = false,
    selectedFg: Color = if (selectedBg.alpha > 0.5f) Palette.Bg else Palette.T1,
) {
    Focusable(onClick, modifier, corner = height / 2, scale = 1.05f, ring = false, onFocus = onFocus) { f ->
        val bg = when { f -> Color.White; selected -> selectedBg; else -> idleBg }
        val fg = when { f -> Palette.Bg; selected -> selectedFg; else -> Palette.T2 }
        Row(
            Modifier.height(height).background(bg).padding(horizontal = height * 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(text, size, fg, if (selected || f) FontWeight.SemiBold else FontWeight.Normal)
            if (dropdown) T(" ▾", size * 0.8f, fg.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier, onLight: Boolean = false) {
    val off = if (onLight) Palette.Bg.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.18f)
    Box(
        modifier.size(28.dp, 16.dp).background(if (on) Palette.Douban else off, RoundedCornerShape(8.dp)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.padding(2.dp).size(12.dp).background(Color.White, CircleShape)) }
}

// ---------------------------------------------------------------------------
// 杂项
// ---------------------------------------------------------------------------

/** 虚线框 */
fun Modifier.dashedBorder(color: Color, corner: Dp, width: Dp = 1.dp) = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color, cornerRadius = CornerRadius(corner.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@Composable
fun SectionTitle(text: String, sub: String? = null, size: TextUnit = 14.sp, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, size, weight = FontWeight.SemiBold)
        if (sub != null) T(sub, 10.sp, Palette.T3, modifier = Modifier.padding(start = 8.dp))
        trailing()
    }
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    T(SimpleDateFormat("HH:mm", Locale.CHINA).format(now), 12.sp, Palette.T2)
}

fun fmtTime(sec: Int): String {
    val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 12.4 万 */
fun fmtWan(n: Int): String = if (n >= 10_000) "%.1f 万".format(n / 10_000f) else "$n"

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(Color.White))
    }
}
