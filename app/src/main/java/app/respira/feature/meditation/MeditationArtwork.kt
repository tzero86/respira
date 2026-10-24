package app.respira.feature.meditation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.respira.ui.theme.RespiraColors

@Composable
internal fun TrackArtwork(
    meditation: MeditationTrack,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    iconSize: Dp = 56.dp,
) {
    val cover = rememberArtwork(meditation.artwork)
    val accent = meditation.accent
    val accentAlt = meditation.accentAlt
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.52f),
                        accentAlt.copy(alpha = 0.30f),
                        RespiraColors.Surface,
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.45f),
                        ),
                    ),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val glowCenter = Offset(size.width * 0.28f, size.height * 0.22f)
                val glowRadius = size.minDimension * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = glowCenter,
                        radius = glowRadius,
                    ),
                    radius = glowRadius,
                    center = glowCenter,
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.14f),
                    radius = size.minDimension * 0.36f,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = size.minDimension * 0.46f,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            Icon(
                painter = painterResource(meditation.icon),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = RespiraColors.Mist.copy(alpha = 0.92f),
            )
        }
    }
}

/**
 * Small square cover used by list rows. Falls back to the gradient badge so
 * bundled and device tracks keep their icon identity.
 */
@Composable
internal fun TrackThumbnail(
    meditation: MeditationTrack,
    size: Dp = 48.dp,
) {
    val cover = rememberArtwork(meditation.artwork)
    if (cover == null) {
        androidx.compose.material3.Icon(
            painter = painterResource(meditation.icon),
            contentDescription = null,
            modifier = Modifier.size(size * 0.55f),
            tint = meditation.accent,
        )
        return
    }
    Image(
        bitmap = cover,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(size * 0.3f)),
        contentScale = ContentScale.Crop,
    )
}
