package app.respira.feature.ambience

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.respira.R
import app.respira.audio.AmbienceLayer
import app.respira.ui.components.GlassCard
import app.respira.ui.components.IconBadge
import app.respira.ui.theme.RespiraColors
import kotlin.math.roundToInt

private data class LayerStyle(@DrawableRes val icon: Int, val accent: Color)

private val layerStyles = mapOf(
    "rain" to LayerStyle(R.drawable.ic_cloud_rain, RespiraColors.Aqua),
    "rain_heavy" to LayerStyle(R.drawable.ic_cloud_rain, RespiraColors.Indigo),
    "thunder" to LayerStyle(R.drawable.ic_cloud_lightning, RespiraColors.Violet),
    "wind" to LayerStyle(R.drawable.ic_wind, RespiraColors.Aqua),
    "stream" to LayerStyle(R.drawable.ic_droplets, RespiraColors.Aqua),
    "ocean" to LayerStyle(R.drawable.ic_droplets, RespiraColors.Indigo),
    "fire" to LayerStyle(R.drawable.ic_flame, RespiraColors.Amber),
    "forest" to LayerStyle(R.drawable.ic_moon, RespiraColors.Violet),
    "birds" to LayerStyle(R.drawable.ic_bird, RespiraColors.Amber),
    "drone" to LayerStyle(R.drawable.ic_activity, RespiraColors.Rose),
)

private val fallbackLayerStyle = LayerStyle(R.drawable.ic_wind, RespiraColors.Aqua)

private val lastVolumesSaver = Saver<MutableMap<String, Float>, String>(
    save = { volumes ->
        volumes.entries.joinToString("|") { (id, volume) -> "$id:$volume" }
    },
    restore = { saved ->
        mutableMapOf<String, Float>().apply {
            saved.split('|').forEach { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator > 0) {
                    entry.substring(separator + 1).toFloatOrNull()?.let { volume ->
                        put(entry.substring(0, separator), volume)
                    }
                }
            }
        }
    },
)

@Composable
internal fun rememberLastLayerVolumes(): MutableMap<String, Float> =
    rememberSaveable(saver = lastVolumesSaver) { mutableMapOf<String, Float>() }

@Composable
internal fun LayerTile(
    layer: AmbienceLayer,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = layerStyles[layer.id] ?: fallbackLayerStyle
    val active = volume > 0.001f
    val name = stringResource(layer.nameRes)
    val hint = stringResource(layer.hintRes)

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        borderColor = if (active) style.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f),
        topTint = if (active) style.accent else Color.White,
        onClick = onToggle,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = style.icon,
                tint = if (active) style.accent else RespiraColors.SecondaryText,
                size = 34.dp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (active) {
                    stringResource(R.string.mixer_percent, (volume * 100).roundToInt().coerceAtLeast(1))
                } else {
                    stringResource(R.string.mixer_off)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (active) style.accent else RespiraColors.TertiaryText,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = if (active) RespiraColors.Mist else RespiraColors.SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = RespiraColors.TertiaryText,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        AmbienceSlider(
            value = volume,
            onValueChange = onVolumeChange,
            label = stringResource(R.string.mixer_layer_volume, name),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AmbienceSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = { onValueChange(it.coerceIn(0f, 1f)) },
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        colors = SliderDefaults.colors(
            thumbColor = RespiraColors.Aqua,
            activeTrackColor = RespiraColors.Aqua,
            inactiveTrackColor = Color.White.copy(alpha = 0.12f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        thumb = {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(RespiraColors.Aqua)
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape),
            )
        },
        track = { sliderState ->
            val fraction = sliderState.coercedValueAsFraction.coerceAtLeast(0.001f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(RespiraColors.Aqua),
                )
            }
        },
        steps = 0,
        valueRange = 0f..1f,
    )
}

@Composable
internal fun MixerPlayButton(
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionDescription = stringResource(
        if (playing) R.string.mixer_pause_mix else R.string.mixer_play_mix,
    )

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) RespiraColors.Aqua else RespiraColors.SurfaceHigh)
            .then(
                if (enabled) {
                    Modifier.border(1.dp, RespiraColors.Aqua.copy(alpha = 0.45f), CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = actionDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) RespiraColors.Ink else RespiraColors.TertiaryText,
        )
    }
}
