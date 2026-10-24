package app.respira.audio

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One playable meditation item. [TrackSource] keeps bundled resources and
 * user-imported files behind the same model so the player never branches on
 * where the audio came from.
 */
data class AudioTrack(
    val id: String,
    val title: String,
    val subtitle: String,
    val source: TrackSource,
    val durationMs: Long = 0L,
    @StringRes val titleRes: Int? = null,
    @StringRes val subtitleRes: Int? = null,
)

sealed interface TrackSource {
    data class Bundled(val rawResId: Int) : TrackSource
    data class Local(val uri: String) : TrackSource
}

data class AudioState(
    val track: AudioTrack? = null,
    val trackPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Active ambience layers mapped to their target volume (0..1). */
    val layers: Map<String, Float> = emptyMap(),
    val layersPlaying: Boolean = false,
    val masterVolume: Float = 1f,
    /** Wall-clock instant when the sleep timer fires, or null when inactive. */
    val sleepTimerEndMs: Long? = null,
    /** Wall-clock instant when the automatic end-of-track fade finishes, null when off. */
    val autoSleepEndMs: Long? = null,
)

interface AudioHub {
    val state: StateFlow<AudioState>

    fun playTrack(track: AudioTrack)
    /** Starts [track] at [positionMs] instead of from the beginning. */
    fun playTrackFrom(track: AudioTrack, positionMs: Long)
    fun toggleTrack()
    fun pauseTrack()
    fun resumeTrack()
    fun seekTo(positionMs: Long)
    fun stopTrack()

    fun setLayerVolume(layerId: String, volume: Float)
    fun setLayers(levels: Map<String, Float>)
    fun clearLayers()
    fun applyPreset(presetId: String)
    fun setLayersPlaying(playing: Boolean)
    fun toggleLayers()
    fun setMasterVolume(volume: Float)

    fun setSleepTimer(minutes: Int?)
    /** Arms or cancels the automatic fade-out that lets the track end softly. */
    fun setAutoSleepToEndOfTrack(enabled: Boolean)
    fun stopAll()
}

/**
 * Process-wide handle used by every screen. The real implementation is
 * installed from [app.respira.RespiraApp]; until then the silent hub keeps the
 * UI fully functional.
 */
object Audio {
    @Volatile
    var hub: AudioHub = SilentAudioHub()
        private set

    fun bootstrap(context: Context) {
        hub = DefaultAudioHub(context.applicationContext)
    }
}

/**
 * State-faithful hub without audio output. Kept as the fallback and as a
 * compilation target for the UI while the playback engine is wired.
 */
internal open class SilentAudioHub : AudioHub {
    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()

    override fun playTrack(track: AudioTrack) = _state.update {
        it.copy(
            track = track,
            trackPlaying = true,
            positionMs = 0L,
            durationMs = if (track.durationMs > 0) track.durationMs else 0L,
        )
    }

    override fun playTrackFrom(track: AudioTrack, positionMs: Long) = _state.update {
        it.copy(
            track = track,
            trackPlaying = true,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (track.durationMs > 0) track.durationMs else 0L,
        )
    }

    override fun toggleTrack() = _state.update {
        if (it.track == null) it else it.copy(trackPlaying = !it.trackPlaying)
    }

    override fun pauseTrack() = _state.update { it.copy(trackPlaying = false) }

    override fun resumeTrack() = _state.update {
        if (it.track == null) it else it.copy(trackPlaying = true)
    }

    override fun seekTo(positionMs: Long) = _state.update {
        it.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    override fun stopTrack() = _state.update {
        it.copy(
            track = null,
            trackPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            autoSleepEndMs = null,
        )
    }

    override fun setLayerVolume(layerId: String, volume: Float) = _state.update {
        val v = volume.coerceIn(0f, 1f)
        val layers = if (v <= 0.001f) it.layers - layerId else it.layers + (layerId to v)
        it.copy(layers = layers, layersPlaying = layers.isNotEmpty())
    }

    override fun setLayers(levels: Map<String, Float>) = _state.update {
        val cleaned = levels.mapValues { (_, v) -> v.coerceIn(0f, 1f) }.filterValues { v -> v > 0.001f }
        it.copy(layers = cleaned, layersPlaying = cleaned.isNotEmpty())
    }

    override fun clearLayers() = _state.update {
        it.copy(layers = emptyMap(), layersPlaying = false)
    }

    override fun applyPreset(presetId: String) {
        val preset = AmbienceCatalog.preset(presetId) ?: return
        setLayers(preset.levels)
    }

    override fun setLayersPlaying(playing: Boolean) = _state.update {
        if (it.layers.isEmpty()) it else it.copy(layersPlaying = playing)
    }

    override fun toggleLayers() = _state.update {
        if (it.layers.isEmpty()) it else it.copy(layersPlaying = !it.layersPlaying)
    }

    override fun setMasterVolume(volume: Float) = _state.update {
        it.copy(masterVolume = volume.coerceIn(0f, 1f))
    }

    override fun setSleepTimer(minutes: Int?) = _state.update {
        it.copy(
            sleepTimerEndMs = minutes?.let { m ->
                System.currentTimeMillis() + m.coerceAtLeast(1) * 60_000L
            },
        )
    }

    override fun setAutoSleepToEndOfTrack(enabled: Boolean) {
        _state.update { current ->
            if (!enabled) {
                current.copy(autoSleepEndMs = null)
            } else {
                val remaining = current.durationMs - current.positionMs
                current.copy(
                    autoSleepEndMs = if (remaining > 0L) {
                        System.currentTimeMillis() + remaining
                    } else {
                        null
                    },
                )
            }
        }
    }

    override fun stopAll() = _state.update {
        AudioState(masterVolume = it.masterVolume)
    }
}
