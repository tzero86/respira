package app.respira.feature.ambience

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.R
import app.respira.audio.AmbienceCatalog
import app.respira.audio.Audio
import app.respira.audio.AudioState
import app.respira.audio.MixerState
import app.respira.ui.components.GhostPill
import app.respira.ui.components.GlassCard
import app.respira.ui.components.PillButton
import app.respira.ui.components.ScreenHeader
import app.respira.ui.components.SectionLabel
import app.respira.ui.theme.RespiraColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val DefaultLayerVolume = 0.6f

private val SleepTimerOptions = listOf<Int?>(null, 15, 30, 45, 60, 90, 120, 240, 480)
private val SleepTimerMinutes = SleepTimerOptions.filterNotNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbienceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hub = Audio.hub
    val state by hub.state.collectAsStateWithLifecycle()

    val lastVolumes = rememberLastLayerVolumes()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var restored by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedMixes by remember(context) { mutableStateOf(MixLibrary.saved(context)) }

    LaunchedEffect(hub) {
        val snapshot = hub.state.value

        if (snapshot.layers.isEmpty()) {
            val saved = MixerState.read(context)
            if (saved.layers.isNotEmpty()) {
                // The saved mix is restored with its levels but stays silent:
                // a sound that begins by itself when the screen opens is not
                // what the user asked for. Only the service resumes a mix, and
                // only after the system stopped it mid-night.
                hub.setLayers(saved.layers)
                hub.setLayersPlaying(false)
            }

            if (abs(snapshot.masterVolume - saved.masterVolume) > 0.001f) {
                hub.setMasterVolume(saved.masterVolume)
            }

            if (snapshot.sleepTimerEndMs == null) {
                val remaining = saved.timerEndMs - System.currentTimeMillis()
                if (remaining > 0L) {
                    hub.setSleepTimer(ceil(remaining / 60_000.0).toInt().coerceAtLeast(1))
                }
            }
        }

        restored = true
    }

    LaunchedEffect(restored, state.layers, state.masterVolume, state.sleepTimerEndMs) {
        if (!restored) return@LaunchedEffect
        MixerState.write(context, state.layers, state.masterVolume, state.sleepTimerEndMs)
    }

    val selectedPresetId = remember(state.layers) {
        AmbienceCatalog.presets.firstOrNull { levelsMatch(state.layers, it.levels) }?.id
    }

    // Only the set of active layers reorders the grid, so a fading layer never
    // makes the tiles jump around.
    val activeLayerIds = state.layers.filterValues { it > 0.001f }.keys
    val orderedLayers = remember(activeLayerIds) {
        val active = AmbienceCatalog.layers
            .filter { it.id in activeLayerIds }
            .sortedByDescending { state.layers[it.id] ?: 0f }
        val inactive = AmbienceCatalog.layers.filter { it.id !in activeLayerIds }
        active + inactive
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

        BatteryCard(
            timerEndMs = state.sleepTimerEndMs,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        SavedMixesRow(
            mixes = savedMixes,
            onLoad = { mix ->
                hub.setLayers(mix.layers)
                hub.setMasterVolume(mix.masterVolume)
            },
            onDelete = { mix ->
                MixLibrary.delete(context, mix.id)
                savedMixes = MixLibrary.saved(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.mixer_mix_deleted),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )

        Spacer(Modifier.height(12.dp))

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
                count = orderedLayers.size,
                key = { index -> orderedLayers[index].id },
            ) { index ->
                val layer = orderedLayers[index]
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
            onSaveMix = { showSaveDialog = true },
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

    if (showSaveDialog) {
        SaveMixDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                val mix = MixLibrary.save(context, name, state.layers, state.masterVolume)
                savedMixes = MixLibrary.saved(context)
                showSaveDialog = false
                if (mix.id.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.mixer_mix_saved, mix.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}

@Composable
private fun SavedMixesRow(
    mixes: List<SavedMix>,
    onLoad: (SavedMix) -> Unit,
    onDelete: (SavedMix) -> Unit,
) {
    if (mixes.isEmpty()) {
        Text(
            text = stringResource(R.string.mixer_no_saved_mixes),
            style = MaterialTheme.typography.bodySmall,
            color = RespiraColors.TertiaryText,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(text = stringResource(R.string.mixer_saved_mixes))
        LazyRow(
            modifier = Modifier.height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            items(
                count = mixes.size,
                key = { index -> mixes[index].id },
            ) { index ->
                val mix = mixes[index]
                SavedMixChip(
                    name = mix.name,
                    onLoad = { onLoad(mix) },
                    onDelete = { onDelete(mix) },
                )
            }
        }
    }
}

@Composable
private fun SavedMixChip(
    name: String,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val loadDescription = stringResource(R.string.mixer_load_mix, name)
    val deleteDescription = stringResource(R.string.mixer_delete_mix)
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = RespiraColors.SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(onClick = onLoad)
                .semantics { contentDescription = loadDescription }
                .padding(start = 14.dp, end = 8.dp),
        )
        Box(
            modifier = Modifier
                .padding(end = 11.dp)
                .size(20.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = deleteDescription,
                modifier = Modifier.size(12.dp),
                tint = RespiraColors.TertiaryText,
            )
        }
    }
}

@Composable
private fun SaveMixDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                solidColor = RespiraColors.Night,
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.mixer_save_mix),
                        style = MaterialTheme.typography.headlineSmall,
                        color = RespiraColors.Mist,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.mixer_mix_name_label)) },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.mixer_mix_name_hint),
                                color = RespiraColors.TertiaryText,
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = RespiraColors.Mist,
                            unfocusedTextColor = RespiraColors.Mist,
                            cursorColor = RespiraColors.Aqua,
                            focusedBorderColor = RespiraColors.Aqua,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                            focusedLabelColor = RespiraColors.Aqua,
                            unfocusedLabelColor = RespiraColors.TertiaryText,
                            focusedPlaceholderColor = RespiraColors.TertiaryText,
                            unfocusedPlaceholderColor = RespiraColors.TertiaryText,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostPill(
                            text = stringResource(R.string.mixer_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.height(44.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        PillButton(
                            text = stringResource(R.string.mixer_save),
                            onClick = { onSave(trimmed) },
                            enabled = trimmed.isNotEmpty(),
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MixerFooter(
    state: AudioState,
    onTogglePlay: () -> Unit,
    onMasterVolume: (Float) -> Unit,
    onOpenTimer: () -> Unit,
    onSaveMix: () -> Unit,
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
        }

        if (hasLayers) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostPill(
                    text = stringResource(R.string.mixer_save_mix),
                    onClick = onSaveMix,
                    modifier = Modifier.height(44.dp),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = RespiraColors.SecondaryText,
                        )
                    },
                )
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
                timerOptionLabel(minutes)
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
private fun timerOptionLabel(minutes: Int): String = if (minutes >= 120) {
    stringResource(R.string.mixer_timer_hours, minutes / 60)
} else {
    stringResource(R.string.mixer_timer_minutes_short, minutes)
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

private fun levelsMatch(layers: Map<String, Float>, expected: Map<String, Float>): Boolean {
    val active = layers.filterValues { it > 0.001f }
    if (active.size != expected.size) return false
    return expected.entries.all { entry ->
        val current = active[entry.key] ?: return@all false
        abs(current - entry.value) <= 0.01f
    }
}

private fun timerBucket(remainingMs: Long): Int {
    val minutes = remainingMs / 60_000.0
    return SleepTimerMinutes.firstOrNull { minutes <= it } ?: SleepTimerMinutes.last()
}

private fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
