package app.respira.feature.meditation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.audio.Audio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pending request to show the full player.
 *
 * [hostTrackId] names the one host allowed to render it: null is the catch-all
 * host that shows whatever is playing, a track id is the host bound to that
 * track. Two hosts therefore never open the player at the same time.
 */
internal data class PlayerRequest(
    /** The track the player was opened for. */
    val trackId: String,
    /** The host that renders the request. */
    val hostTrackId: String?,
)

/**
 * Shared state for the full meditation player.
 *
 * The shell hosts a catch-all player that shows whatever is playing; the
 * Meditate screen hosts one bound to the track it started.
 */
internal object MeditationPlayerRequest {
    private val _request = MutableStateFlow<PlayerRequest?>(null)

    /** The pending request, or null when no player is open. */
    val request: StateFlow<PlayerRequest?> = _request.asStateFlow()

    /** Opens the player for [trackId] in the catch-all host. */
    fun openAny(trackId: String) {
        _request.value = PlayerRequest(trackId = trackId, hostTrackId = null)
    }

    /** Opens the player for [trackId] in the host bound to that track. */
    fun openTrack(trackId: String) {
        _request.value = PlayerRequest(trackId = trackId, hostTrackId = trackId)
    }

    /** Closes the player. Idempotent. */
    fun close() {
        _request.value = null
    }

    /** True when [request] belongs to the host identified by [hostTrackId]. */
    fun owns(request: PlayerRequest?, hostTrackId: String?): Boolean =
        request != null && request.hostTrackId == hostTrackId
}

/**
 * Host for the full-screen player, usable from any screen.
 *
 * [trackId] picks the host: null shows whatever is playing, a track id shows
 * that track only. The player opens on request through
 * [MeditationPlayerRequest] and closes the request when dismissed.
 */
@Composable
internal fun MeditationPlayerHost(
    trackId: String? = null,
    onClose: () -> Unit,
) {
    val state by Audio.hub.state.collectAsStateWithLifecycle()
    val request by MeditationPlayerRequest.request.collectAsStateWithLifecycle()
    val hostTrackId = rememberUpdatedState(trackId)
    val closeCallback = rememberUpdatedState(onClose)

    DisposableEffect(Unit) {
        onDispose {
            val pending = MeditationPlayerRequest.request.value ?: return@onDispose
            if (!MeditationPlayerRequest.owns(pending, hostTrackId.value)) return@onDispose
            val live = Audio.hub.state.value.track?.id
            if (hostTrackId.value != null && live != null) {
                // The screen that owned the player went away; hand it to the
                // catch-all host so the player survives a tab change.
                MeditationPlayerRequest.openAny(live)
            } else {
                MeditationPlayerRequest.close()
            }
        }
    }

    LaunchedEffect(request, state.track?.id) {
        val pending = MeditationPlayerRequest.request.value ?: return@LaunchedEffect
        if (!MeditationPlayerRequest.owns(pending, hostTrackId.value)) return@LaunchedEffect
        val live = Audio.hub.state.value.track
        if (hostTrackId.value == null) {
            // The catch-all host follows whatever is playing.
            when {
                live == null -> MeditationPlayerRequest.close()
                live.id != pending.trackId -> MeditationPlayerRequest.openAny(live.id)
            }
            return@LaunchedEffect
        }
        if (live?.id == pending.trackId) return@LaunchedEffect
        // The hub may still be publishing the track the request was made for.
        delay(400)
        if (Audio.hub.state.value.track?.id != pending.trackId) MeditationPlayerRequest.close()
    }

    val pending = request ?: return
    if (!MeditationPlayerRequest.owns(pending, trackId)) return
    val track = state.track ?: return
    if (hostTrackId.value != null && track.id != pending.trackId) return

    MeditationPlayerDialog(
        track = track,
        meditation = track.asMeditation(),
        state = state,
        onClose = {
            MeditationPlayerRequest.close()
            closeCallback.value()
        },
    )
}
