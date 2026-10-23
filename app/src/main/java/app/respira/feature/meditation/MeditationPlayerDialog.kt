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
import app.respira.ui.components.AuroraBackdrop
import app.respira.ui.components.GhostPill
import app.respira.ui.components.SectionLabel
import app.respira.ui.components.SoftIconButton
import app.respira.ui.components.formatDuration
import app.respira.ui.theme.RespiraColors
import kotlinx.coroutines.delay
import kotlin.math.abs

private val sleepTimerOptions = listOf(15, 30, 45, 60)

@Composable
internal fun MeditationPlayerDialog(
    track: AudioTrack,
    meditation: MeditationTrack,
    state: AudioState,
    onClose: () -> Unit,
) {
    val hub = Audio.hub
    val durationMs = if (state.durationMs > 0) state.durationMs else track.durationMs
    var scrubFraction by remember(track.id) { mutableStateOf<Float?>(null) }

    val nowMs = rememberNowMs(state.sleepTimerEndMs)
    val sleepRemainingMs = state.sleepTimerEndMs
        ?.let { (it - nowMs).coerceAtLeast(0L) }
        ?.takeIf { it > 0L }
    val activeTimer = sleepRemainingMs?.let { remaining ->
        val remainingMinutes = remaining / 60_000.0
        sleepTimerOptions.minByOrNull { abs(it - remainingMinutes) }
    }

    val elapsedMs = scrubFraction?.let { (it * durationMs).toLong() } ?: state.positionMs
    val fraction = if (durationMs > 0L) {
        (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
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

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun rememberNowMs(endMs: Long?): Long {
    var now by remember(endMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMs) {
        if (endMs == null) return@LaunchedEffect
        while (true) {
            val current = System.currentTimeMillis()
            now = current
            if (current >= endMs) break
            delay(1_000L)
        }
    }
    return now
}
