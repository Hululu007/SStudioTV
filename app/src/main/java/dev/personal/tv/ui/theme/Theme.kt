package dev.personal.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 设计稿按 1920×1080 px 画，电视上 1080p ≈ 960×540 dp，所以设计稿里的 px ÷ 2 = dp / sp。
 */
object Palette {
    val Bg = Color(0xFF0A0C0F)
    val T1 = Color(0xFFF3F4F6)
    val T2 = T1.copy(alpha = 0.68f)
    val T3 = T1.copy(alpha = 0.42f)
    val Star = Color(0xFFFFB13B)
    val Douban = Color(0xFF2DBE60)
    val Bad = Color(0xFFFF5A4F)
    val Bili = Color(0xFFFB7299)
    val Weibo = Color(0xFFFF8200)
    val Ghost = Color.White.copy(alpha = 0.12f)
    val Ghost8 = Color.White.copy(alpha = 0.08f)
    val Ghost6 = Color.White.copy(alpha = 0.06f)
    val Ghost4 = Color.White.copy(alpha = 0.04f)
    val Panel = Color(0xE612151A)
}

object Dimens {
    /** 安全区：左右 96px、上下 54px */
    val SafeH = 48.dp
    val SafeV = 27.dp
}

val Serif = FontFamily.Serif

/** 焦点移动等动效统一用的缓出曲线 */
val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Bg,
            surface = Palette.Bg,
            onSurface = Palette.T1,
            onBackground = Palette.T1,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides Palette.T1) {
            Box(Modifier.fillMaxSize().background(Palette.Bg)) { content() }
        }
    }
}
