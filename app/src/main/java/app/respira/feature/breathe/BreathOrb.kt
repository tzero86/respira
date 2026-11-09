package app.respira.feature.breathe

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.respira.ui.theme.RespiraColors

internal fun BreathPhase.accentColor(): Color = when (this) {
    BreathPhase.Inhale -> RespiraColors.Aqua
    BreathPhase.HoldIn -> RespiraColors.Violet
    BreathPhase.Exhale -> RespiraColors.Indigo
    BreathPhase.HoldOut -> RespiraColors.Amber
}

/** Live session orb, driven by the breathing engine. */
@Composable
internal fun BreathOrb(
    engine: BreathEngine,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val status = engine.status
        val idle = status == BreathStatus.Idle || status == BreathStatus.Complete
        val phase = engine.phase
        val progress = engine.phaseProgress

        val scale = when {
            idle -> 0.62f
            phase == BreathPhase.Inhale ->
                0.55f + 0.45f * FastOutSlowInEasing.transform(progress)
            phase == BreathPhase.HoldIn -> 1f
            phase == BreathPhase.Exhale ->
                1f - 0.45f * FastOutSlowInEasing.transform(progress)
            else -> 0.55f
        }

        val accent = if (idle) RespiraColors.Aqua else phase.accentColor()
        val pulse = if (idle || status == BreathStatus.Paused) {
            0f
        } else {
            (1f - engine.phaseElapsedSeconds / 0.9f).coerceIn(0f, 1f)
        }

        drawBreathOrb(scale = scale, accent = accent, pulse = pulse)
    }
}

/**
 * The resting orb shown on Home. Same drawing as the session orb, but it
 * only swells slowly, so the hero and the Breathe screen share one shape.
 */
@Composable
internal fun BreathOrbHero(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hero-orb")
    val swell by transition.animateFloat(
        initialValue = 0.70f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(5600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero-orb-swell",
    )
    Canvas(modifier) {
        drawBreathOrb(scale = swell, accent = RespiraColors.Aqua, pulse = 0f)
    }
}

/**
 * Draws the orb. [scale] is the core radius as a fraction of the maximum,
 * [pulse] (0..1) adds the expanding ring that marks a phase change.
 */
private fun DrawScope.drawBreathOrb(scale: Float, accent: Color, pulse: Float) {
    val maxRadius = size.minDimension / 2f * 0.86f
    val core = maxRadius * scale
    val c = center

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.30f),
                accent.copy(alpha = 0.08f),
                Color.Transparent,
            ),
            center = c,
            radius = maxRadius * 1.16f,
        ),
        radius = maxRadius * 1.16f,
        center = c,
    )

    drawCircle(
        color = accent.copy(alpha = 0.10f),
        radius = core * 1.12f,
        center = c,
        style = Stroke(width = 1.2f),
    )
    drawCircle(
        color = accent.copy(alpha = 0.16f),
        radius = core * 1.06f,
        center = c,
        style = Stroke(width = 1.2f),
    )
    drawCircle(
        color = accent.copy(alpha = 0.22f),
        radius = core,
        center = c,
        style = Stroke(width = 1.2f),
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.42f),
                accent.copy(alpha = 0.16f),
                accent.copy(alpha = 0.05f),
            ),
            center = Offset(c.x, c.y - core * 0.18f),
            radius = core * 1.05f,
        ),
        radius = core,
        center = c,
    )

    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            startY = c.y - core,
            endY = c.y + core,
        ),
        radius = core,
        center = c,
        style = Stroke(width = 1.4f),
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(c.x - core * 0.22f, c.y - core * 0.30f),
            radius = core * 0.52f,
        ),
        radius = core * 0.52f,
        center = Offset(c.x - core * 0.22f, c.y - core * 0.30f),
    )

    if (pulse > 0.01f) {
        val expansion = 1f - pulse
        drawCircle(
            color = accent.copy(alpha = 0.30f * pulse),
            radius = core * (1.06f + 0.32f * expansion),
            center = c,
            style = Stroke(width = 1.5f + 2.5f * pulse),
        )
    }
}
