package dev.personal.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import dev.personal.tv.data.ArtStyle
import dev.personal.tv.data.Title
import dev.personal.tv.ui.theme.Serif
import kotlin.math.max

/**
 * 没有海报（或还没加载出来、加载失败）时用的渐变，和设计稿里的示意图一致。
 */
fun DrawScope.drawArt(art: ArtStyle, silhouette: Boolean = true) {
    drawRect(Brush.verticalGradient(0f to art.top, 0.55f to art.mid, 1f to art.bottom))
    art.glows.forEach { g ->
        val c = Offset(size.width * g.x, size.height * g.y)
        val r = g.r * max(size.width, size.height)
        drawCircle(
            brush = Brush.radialGradient(listOf(g.color, g.color.copy(alpha = 0f)), center = c, radius = r),
            radius = r,
            center = c,
        )
    }
    if (silhouette) {
        val bandTop = size.height * 0.6f
        val bandH = size.height * 0.4f
        val path = Path()
        SIL.forEachIndexed { i, (x, y) ->
            val px = size.width * x / 100f
            val py = bandTop + bandH * y / 100f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(path, art.sil)
    }
}

private val SIL = listOf(
    0f to 62f, 7f to 56f, 11f to 28f, 12.5f to 28f, 14f to 54f, 24f to 50f, 31f to 22f, 32f to 22f,
    33.5f to 47f, 47f to 44f, 55f to 60f, 66f to 38f, 74f to 56f, 86f to 46f, 100f to 54f, 100f to 100f, 0f to 100f,
)

fun Modifier.art(art: ArtStyle, silhouette: Boolean = true) = drawBehind { drawArt(art, silhouette) }

/** 海报：渐变 + 竖排感的衬线片名 */
@Composable
fun PosterArt(art: ArtStyle, title: String, titleSize: TextUnit, modifier: Modifier = Modifier, bottomPadding: Dp = 10.dp) {
    Box(modifier.art(art)) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomPadding),
            style = TextStyle(
                fontFamily = Serif,
                fontWeight = FontWeight.Black,
                fontSize = titleSize,
                letterSpacing = 0.3.em,
                textAlign = TextAlign.Center,
                color = Color.White,
                shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 14f),
            ),
            maxLines = 1,
        )
    }
}

/** 片子的海报：有豆瓣海报就用海报，加载出来之前和失败时显示渐变 + 片名 */
@Composable
fun PosterArt(t: Title, titleSize: TextUnit, modifier: Modifier = Modifier, bottomPadding: Dp = 10.dp) {
    var ok by remember(t.poster) { mutableStateOf(false) }
    Box(modifier) {
        if (!ok) PosterArt(t.art, t.name, titleSize, Modifier.fillMaxSize(), bottomPadding)
        if (t.poster != null) {
            AsyncImage(
                model = t.poster, contentDescription = t.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { ok = true }, onError = { ok = false },
            )
        }
    }
}

/**
 * 横版画面（首页大背景、继续观看卡片）：有横版剧照用剧照；没有（或还在查）时不裁海报——
 * 底下铺一张缩得很小再放大的海报（自然就是糊的，老系统也有效果），上面靠 [posterAlign] 摆完整的竖海报。
 * 竖海报裁成横图会只剩中间一条，脸和片名都被切掉。
 */
@Composable
fun Landscape(
    t: Title,
    modifier: Modifier = Modifier,
    posterAlign: Alignment = Alignment.CenterEnd,
    stillAlign: Alignment = Alignment.Center,
    posterInset: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val still = dev.personal.tv.data.Stills.get(t)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.art(t.art, silhouette = false).clipToBounds()) {
        androidx.compose.animation.Crossfade(still, animationSpec = androidx.compose.animation.core.tween(350), label = "still") { s ->
            Box(Modifier.fillMaxSize()) {
                if (s != null) {
                    AsyncImage(
                        model = s, contentDescription = null, contentScale = ContentScale.Crop,
                        alignment = stillAlign, modifier = Modifier.fillMaxSize(),
                    )
                } else if (t.poster != null) {
                    val tiny = remember(t.poster) {
                        coil.request.ImageRequest.Builder(ctx).data(t.poster).size(16, 24)
                            .memoryCacheKey("tiny:${t.poster}").build()
                    }
                    AsyncImage(
                        model = tiny, contentDescription = null, contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.7f },
                    )
                    AsyncImage(
                        model = t.poster, contentDescription = null, contentScale = ContentScale.Fit,
                        alignment = posterAlign, modifier = Modifier.fillMaxSize().padding(posterInset),
                    )
                }
            }
        }
    }
}

/** 模糊背景（详情页、播放器加载时）：海报裁成横图，上面再盖各页面自己的遮罩 */
@Composable
fun Backdrop(t: Title, modifier: Modifier = Modifier, silhouette: Boolean = true, alignment: Alignment = Alignment.TopCenter) {
    Box(modifier.art(t.art, silhouette)) {
        if (t.poster != null) {
            AsyncImage(
                model = t.poster, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = alignment, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
