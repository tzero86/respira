package app.respira.feature.ambience

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.R
import app.respira.audio.AmbienceCatalog
import app.respira.audio.Audio
import app.respira.audio.AudioState
import app.respira.ui.components.GhostPill
import app.respira.ui.components.GlassCard
import app.respira.ui.components.ScreenHeader
import app.respira.ui.theme.RespiraColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MixerPrefsName = "respira_mixer"
private const val KeyLayers = "layers"
private const val KeyMaster = "master"
private const val KeyTimerEnd = "sleep_timer_end_ms"
private const val DefaultLayerVolume = 0.6f

private val SleepTimerOptions = listOf<Int?>(null, 15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbienceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hub = Audio.hub
    val state by hub.state.collectAsStateWithLifecycle()

    val prefs = remember(context) {
        context.getSharedPreferences(MixerPrefsName, Context.MODE_PRIVATE)
    }
    val lastVolumes = rememberLastLayerVolumes()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var restored by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(hub) {
        val snapshot = hub.state.value
        val freshMix = snapshot.layers.isEmpty()

        if (freshMix) {
            val savedLayers = readSavedLayers(prefs)
            if (savedLayers.isNotEmpty()) {
                hub.setLayers(savedLayers)
                hub.setLayersPlaying(false)
            }
        }

        if (freshMix && abs(snapshot.masterVolume - 1f) < 0.001f) {
            val savedMaster = prefs.getFloat(KeyMaster, 1f).coerceIn(0f, 1f)
            if (abs(savedMaster - snapshot.masterVolume) > 0.001f) {
                hub.setMasterVolume(savedMaster)
            }
        }

        if (snapshot.sleepTimerEndMs == null) {
            val timerEnd = prefs.getLong(KeyTimerEnd, 0L)
            val remaining = timerEnd - System.currentTimeMillis()
            if (remaining > 0L) {
                hub.setSleepTimer(ceil(remaining / 60_000.0).toInt().coerceAtLeast(1))
            }
        }

        restored = true
    }

    LaunchedEffect(restored, state.layers, state.masterVolume, state.sleepTimerEndMs) {
        if (!restored) return@LaunchedEffect
        prefs.edit()
            .putStringSet(
                KeyLayers,
                state.layers
                    .filterValues { it > 0.001f }
                    .map { (id, volume) -> "$id:$volume" }
                    .toMutableSet(),
            )
            .putFloat(KeyMaster, state.masterVolume)
            .putLong(KeyTimerEnd, state.sleepTimerEndMs ?: 0L)
            .apply()
    }

    val selectedPresetId = remember(state.layers) {
        AmbienceCatalog.presets.firstOrNull { levelsMatch(state.layers, it.levels) }?.id
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            title = stringResource(R.string.mixer_title),
            subtitle = stringResource(R.string.mixer_subtitle),
        )
        Spacer(Modifier.height(14.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            items(
                count = AmbienceCatalog.presets.size,
                key = { index -> AmbienceCatalog.presets[index].id },
            ) { index ->
                val preset = AmbienceCatalog.presets[index]
                GhostPill(
                    text = stringResource(preset.nameRes),
                    onClick = { hub.applyPreset(preset.id) },
                    selected = preset.id == selectedPresetId,
                    modifier = Modifier.height(44.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(
                count = AmbienceCatalog.layers.size,
                key = { index -> AmbienceCatalog.layers[index].id },
            ) { index ->
                val layer = AmbienceCatalog.layers[index]
                val volume = state.layers[layer.id] ?: 0f
                LayerTile(
                    layer = layer,
                    volume = volume,
                    onVolumeChange = { newVolume ->
                        if (newVolume > 0.001f) lastVolumes[layer.id] = newVolume
                        hub.setLayerVolume(layer.id, newVolume)
                    },
                    onToggle = {
                        if (volume > 0.001f) {
                            lastVolumes[layer.id] = volume
                            hub.setLayerVolume(layer.id, 0f)
                        } else {
                            hub.setLayerVolume(layer.id, lastVolumes[layer.id] ?: DefaultLayerVolume)
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        MixerFooter(
            state = state,
            onTogglePlay = { hub.toggleLayers() },
            onMasterVolume = { hub.setMasterVolume(it) },
            onOpenTimer = { showTimerSheet = true },
            onClear = { hub.clearLayers() },
        )

        Spacer(Modifier.height(10.dp))
    }

    if (showTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTimerSheet = false },
            sheetState = sheetState,
            containerColor = RespiraColors.Deep,
            contentColor = RespiraColors.Mist,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.22f)),
                )
            },
        ) {
            SleepTimerSheet(
                endMs = state.sleepTimerEndMs,
                onSelect = { minutes ->
                    hub.setSleepTimer(minutes)
                    showTimerSheet = false
                },
            )
        }
    }
}

@Composable
private fun MixerFooter(
    state: AudioState,
    onTogglePlay: () -> Unit,
    onMasterVolume: (Float) -> Unit,
    onOpenTimer: () -> Unit,
    onClear: () -> Unit,
) {
    val remaining = rememberSleepRemaining(state.sleepTimerEndMs)
    val activeLayerCount = state.layers.count { it.value > 0.001f }
    val hasLayers = activeLayerCount > 0
    val timerLabel = remaining?.let(::formatCountdown) ?: stringResource(R.string.mixer_sleep_timer)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        borderColor = if (state.layersPlaying) {
            RespiraColors.Aqua.copy(alpha = 0.30f)
        } else {
            Color.White.copy(alpha = 0.10f)
        },
        topTint = RespiraColors.Aqua,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MixerPlayButton(
                playing = state.layersPlaying && hasLayers,
                enabled = hasLayers,
                onClick = onTogglePlay,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (hasLayers) {
                        pluralStringResource(
                            R.plurals.mixer_layer_count,
                            activeLayerCount,
                            activeLayerCount,
                        )
                    } else {
                        stringResource(R.string.mixer_silent_mix)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = RespiraColors.Mist,
                )
                Text(
                    text = when {
                        state.layersPlaying && hasLayers -> stringResource(R.string.mixer_playing_together)
                        hasLayers -> stringResource(R.string.mixer_paused)
                        else -> stringResource(R.string.mixer_empty_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.layersPlaying && hasLayers) RespiraColors.Aqua else RespiraColors.TertiaryText,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_volume_2),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = RespiraColors.SecondaryText,
            )
            Spacer(Modifier.width(8.dp))
            AmbienceSlider(
                value = state.masterVolume,
                onValueChange = onMasterVolume,
                label = stringResource(R.string.mixer_master_volume),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    R.string.mixer_percent,
                    (state.masterVolume * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = RespiraColors.SecondaryText,
                textAlign = TextAlign.End,
                modifier = Modifier.width(38.dp),
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostPill(
                text = timerLabel,
                onClick = onOpenTimer,
                selected = remaining != null,
                modifier = Modifier.height(44.dp),
                leading = {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (remaining != null) RespiraColors.Aqua else RespiraColors.SecondaryText,
                    )
                },
            )
            Spacer(Modifier.weight(1f))
            if (hasLayers) {
                GhostPill(
                    text = stringResource(R.string.mixer_clear),
                    onClick = onClear,
                    modifier = Modifier.height(44.dp),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_trash),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = RespiraColors.SecondaryText,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepTimerSheet(
    endMs: Long?,
    onSelect: (Int?) -> Unit,
) {
    val remaining = rememberSleepRemaining(endMs)
    val selectedMinutes = remaining?.let(::timerBucket)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.mixer_sleep_timer),
            style = MaterialTheme.typography.headlineSmall,
            color = RespiraColors.Mist,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.mixer_timer_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = RespiraColors.SecondaryText,
        )
        Spacer(Modifier.height(16.dp))

        SleepTimerOptions.forEach { minutes ->
            val selected = if (minutes == null) remaining == null else minutes == selectedMinutes
            val optionLabel = if (minutes == null) {
                stringResource(R.string.mixer_timer_off)
            } else {
                stringResource(R.string.mixer_timer_minutes, minutes)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) RespiraColors.Aqua.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f),
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(minutes) },
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = optionLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) RespiraColors.Mist else RespiraColors.SecondaryText,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(RespiraColors.Aqua),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (remaining != null) {
            Text(
                text = stringResource(R.string.mixer_timer_ends_in, formatCountdown(remaining)),
                style = MaterialTheme.typography.labelMedium,
                color = RespiraColors.Aqua,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun rememberSleepRemaining(endMs: Long?): Long? {
    var now by remember(endMs) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(endMs) {
        val end = endMs ?: return@LaunchedEffect
        while (true) {
            val current = System.currentTimeMillis()
            now = current
            if (current >= end) {
                if (Audio.hub.state.value.sleepTimerEndMs == end) {
                    Audio.hub.setSleepTimer(null)
                }
                break
            }
            delay(1_000L)
        }
    }

    return endMs?.let { (it - now).coerceAtLeast(0L) }
}

private fun readSavedLayers(prefs: SharedPreferences): Map<String, Float> =
    (prefs.getStringSet(KeyLayers, mutableSetOf()) ?: emptySet())
        .mapNotNull { entry ->
            val separator = entry.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            val id = entry.substring(0, separator)
            val volume = entry.substring(separator + 1).toFloatOrNull() ?: return@mapNotNull null
            if (AmbienceCatalog.layer(id) == null) null else id to volume.coerceIn(0f, 1f)
        }
        .filter { (_, volume) -> volume > 0.001f }
        .toMap()

private fun levelsMatch(layers: Map<String, Float>, expected: Map<String, Float>): Boolean {
    val active = layers.filterValues { it > 0.001f }
    if (active.size != expected.size) return false
    return expected.entries.all { entry ->
        val current = active[entry.key] ?: return@all false
        abs(current - entry.value) <= 0.01f
    }
}

private fun timerBucket(remainingMs: Long): Int = when {
    remainingMs > 45 * 60_000L -> 60
    remainingMs > 30 * 60_000L -> 45
    remainingMs > 15 * 60_000L -> 30
    else -> 15
}

private fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
