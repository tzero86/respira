package app.respira.feature.meditation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import app.respira.R
import app.respira.audio.Audio
import app.respira.audio.AudioState
import app.respira.audio.AudioTrack
import app.respira.audio.PlaybackMemory
import app.respira.ui.components.AuroraBackdrop
import app.respira.ui.components.GhostPill
import app.respira.ui.components.SectionLabel
import app.respira.ui.components.SoftIconButton
import app.respira.ui.components.formatDuration
import app.respira.ui.theme.RespiraColors
import kotlinx.coroutines.delay
import kotlin.math.abs

private val sleepTimerOptions = listOf(15, 30, 45, 60)

private const val RESUME_THRESHOLD_MS = 30_000L
private const val AUTO_SLEEP_MIN_DURATION_MS = 60_000L

@Composable
internal fun MeditationPlayerDialog(
    track: AudioTrack,
    meditation: MeditationTrack,
    state: AudioState,
    onClose: () -> Unit,
) {
    val hub = Audio.hub
    val context = LocalContext.current
    val durationMs = if (state.durationMs > 0) state.durationMs else track.durationMs
    var scrubFraction by remember(track.id) { mutableStateOf<Float?>(null) }

    val nowMs = rememberNowMs(state.sleepTimerEndMs, state.autoSleepEndMs)
    val sleepRemainingMs = state.sleepTimerEndMs
        ?.let { (it - nowMs).coerceAtLeast(0L) }
        ?.takeIf { it > 0L }
    val activeTimer = sleepRemainingMs?.let { remaining ->
        val remainingMinutes = remaining / 60_000.0
        sleepTimerOptions.minByOrNull { abs(it - remainingMinutes) }
    }

    val autoSleepRemainingMs = state.autoSleepEndMs
        ?.let { (it - nowMs).coerceAtLeast(0L) }
        ?.takeIf { it > 0L }
    val autoSleepAvailable = durationMs > AUTO_SLEEP_MIN_DURATION_MS ||
        state.autoSleepEndMs != null

    val elapsedMs = scrubFraction?.let { (it * durationMs).toLong() } ?: state.positionMs
    val fraction = if (durationMs > 0L) {
        (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    val savedPosition = remember(track.id, state.trackPlaying, state.positionMs / 5_000L) {
        PlaybackMemory.position(context, track.id)
    }
    val resumePosition = savedPosition.takeIf {
        it > RESUME_THRESHOLD_MS && (durationMs <= 0L || it < durationMs)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        AuroraBackdrop {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftIconButton(
                        icon = R.drawable.ic_chevron_left,
                        contentDescription = stringResource(R.string.med_close_player),
                        onClick = onClose,
                    )
                    Spacer(Modifier.weight(1f))
                    SectionLabel(stringResource(R.string.med_now_playing))
                }

                Spacer(Modifier.height(36.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TrackArtwork(
                        meditation = meditation,
                        modifier = Modifier.size(248.dp),
                        shape = RoundedCornerShape(36.dp),
                        iconSize = 72.dp,
                    )
                }

                Spacer(Modifier.height(26.dp))

                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = RespiraColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = track.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RespiraColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(22.dp))

                Slider(
                    value = fraction,
                    onValueChange = { scrubFraction = it },
                    onValueChangeFinished = {
                        scrubFraction?.let { hub.seekTo((it * durationMs).toLong()) }
                        scrubFraction = null
                    },
                    enabled = durationMs > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = RespiraColors.Aqua,
                        activeTrackColor = RespiraColors.Aqua,
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDuration(elapsedMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = RespiraColors.TertiaryText,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (durationMs > 0L) {
                            formatDuration(durationMs)
                        } else {
                            stringResource(R.string.med_unknown_duration)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = RespiraColors.TertiaryText,
                    )
                }

                Spacer(Modifier.height(26.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SoftIconButton(
                        icon = if (state.trackPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = stringResource(
                            if (state.trackPlaying) R.string.med_pause else R.string.med_play,
                        ),
                        onClick = { hub.toggleTrack() },
                        size = 72.dp,
                        tint = RespiraColors.Ink,
                        background = RespiraColors.Aqua,
                        bordered = false,
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 8.dp,
                        alignment = Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GhostPill(
                        text = stringResource(R.string.med_sleep_off),
                        onClick = { hub.setSleepTimer(null) },
                        selected = sleepRemainingMs == null,
                    )
                    sleepTimerOptions.forEach { minutes ->
                        GhostPill(
                            text = stringResource(R.string.med_timer_minutes, minutes),
                            onClick = { hub.setSleepTimer(minutes) },
                            selected = activeTimer == minutes,
                        )
                    }
                }

                if (sleepRemainingMs != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.med_sleep_timer_remaining,
                            formatDuration(sleepRemainingMs),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = RespiraColors.Aqua,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (autoSleepAvailable) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.med_auto_sleep_toggle),
                            style = MaterialTheme.typography.labelMedium,
                            color = RespiraColors.TertiaryText,
                        )
                        Spacer(Modifier.weight(1f))
                        GhostPill(
                            text = if (autoSleepRemainingMs != null) {
                                stringResource(
                                    R.string.med_auto_sleep_end,
                                    formatDuration(autoSleepRemainingMs),
                                )
                            } else {
                                stringResource(R.string.med_auto_sleep_off)
                            },
                            onClick = {
                                hub.setAutoSleepToEndOfTrack(autoSleepRemainingMs == null)
                            },
                            selected = autoSleepRemainingMs != null,
                        )
                    }
                }

                if (resumePosition != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            space = 8.dp,
                            alignment = Alignment.CenterHorizontally,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostPill(
                            text = stringResource(
                                R.string.med_resume_position_at,
                                formatDuration(resumePosition),
                            ),
                            onClick = { hub.seekTo(resumePosition) },
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun rememberNowMs(vararg endTimes: Long?): Long {
    val keys = endTimes.toList()
    var now by remember(keys) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(keys) {
        val deadlines = keys.filterNotNull()
        if (deadlines.isEmpty()) return@LaunchedEffect
        while (true) {
            val current = System.currentTimeMillis()
            now = current
            if (deadlines.all { current >= it }) break
            delay(1_000L)
        }
    }
    return now
}
