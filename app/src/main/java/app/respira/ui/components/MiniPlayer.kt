package app.respira.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.R
import app.respira.audio.Audio
import app.respira.ui.theme.RespiraColors
import app.respira.ui.theme.RespiraColors.Mist
import app.respira.ui.theme.RespiraColors.SecondaryText

/**
 * The now-playing strip. It lives inside the bottom bar, above the tabs, so
 * there is one floating bar instead of two. It has no card of its own.
 */
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val state by Audio.hub.state.collectAsStateWithLifecycle()
    val track = state.track

    AnimatedVisibility(
        visible = track != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        track?.let { current ->
            val titleText = current.titleRes?.let { stringResource(it) } ?: current.title
            val subtitleText = current.subtitleRes?.let { stringResource(it) } ?: current.subtitle
            val openLabel = stringResource(R.string.audio_open_player)
            val playbackState = stringResource(
                if (state.trackPlaying) R.string.med_playing else R.string.med_paused,
            )
            val progress = if (state.durationMs > 0L) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }

            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = openLabel,
                            onClick = onOpen,
                        )
                        .semantics { stateDescription = playbackState }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        RespiraColors.Violet.copy(alpha = 0.35f),
                                        RespiraColors.Indigo.copy(alpha = 0.22f),
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_moon),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = Mist,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleSmall,
                            color = Mist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    SoftIconButton(
                        icon = if (state.trackPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = stringResource(
                            if (state.trackPlaying) R.string.home_player_pause
                            else R.string.home_player_play,
                        ),
                        onClick = { Audio.hub.toggleTrack() },
                        size = 38.dp,
                        tint = RespiraColors.Aqua,
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = RespiraColors.Aqua,
                    trackColor = RespiraColors.Hairline.copy(alpha = 0.12f),
                )
            }
        }
    }
}
