package app.respira.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.respira.ui.theme.RespiraColors

/**
 * Full-bleed night sky with slow aurora drift. Every screen sits on top of this;
 * screens themselves stay transparent so the atmosphere is continuous.
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(26000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )
    val swell by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "swell",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(RespiraColors.Ink, RespiraColors.Night, RespiraColors.Deep),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.radialGradient(
                    colors = listOf(RespiraColors.Aqua.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(w * (0.16f + 0.16f * drift), h * 0.06f),
                    radius = w * 0.92f * swell,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(RespiraColors.Indigo.copy(alpha = 0.17f), Color.Transparent),
                    center = Offset(w * (0.94f - 0.20f * drift), h * (0.40f + 0.07f * drift)),
                    radius = w * 0.95f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(RespiraColors.Violet.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * 0.5f, h * 1.05f),
                    radius = w * 0.85f,
                ),
            )
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, RespiraColors.Ink.copy(alpha = 0.55f)),
                    startY = h * 0.58f,
                    endY = h,
                ),
            )
        }
        content()
    }
}
