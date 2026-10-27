package app.respira.audio

import android.content.Context

/**
 * The mix the user left behind, kept on disk so an overnight session can put
 * itself back after the system stops the app.
 */
internal data class SavedMix(
    val layers: Map<String, Float> = emptyMap(),
    val masterVolume: Float = 1f,
    /** Wall-clock instant of the pending sleep timer, or 0 when none was set. */
    val timerEndMs: Long = 0L,
    /** True when the mix was playing, as opposed to paused with its levels kept. */
    val playing: Boolean = false,
)

/**
 * Single owner of the mixer preferences. The mixer screen writes the levels and
 * the timer, the playback engine writes the play intent, and the service reads
 * both back when it starts again.
 */
internal object MixerState {
    const val PrefsName = "respira_mixer"
    private const val KeyLayers = "layers"
    private const val KeyMaster = "master"
    private const val KeyTimerEnd = "sleep_timer_end_ms"
    private const val KeyPlaying = "was_playing"
    private const val KeyBatteryDismissed = "battery_prompt_dismissed"

    fun read(context: Context): SavedMix {
        val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        val layers = prefs.getStringSet(KeyLayers, null).orEmpty()
            .mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                val id = entry.substring(0, separator)
                val volume = entry.substring(separator + 1).toFloatOrNull() ?: return@mapNotNull null
                if (AmbienceCatalog.layer(id) == null) null else id to volume.coerceIn(0f, 1f)
            }
            .filter { (_, volume) -> volume > 0.001f }
            .toMap()
        return SavedMix(
            layers = layers,
            masterVolume = prefs.getFloat(KeyMaster, 1f).coerceIn(0f, 1f),
            timerEndMs = prefs.getLong(KeyTimerEnd, 0L),
            playing = prefs.getBoolean(KeyPlaying, false),
        )
    }

    /** Writes the levels and the timer; the play intent has its own writer. */
    fun write(context: Context, layers: Map<String, Float>, masterVolume: Float, timerEndMs: Long?) {
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).edit()
            .putStringSet(
                KeyLayers,
                layers.filterValues { it > 0.001f }
                    .map { (id, volume) -> "$id:$volume" }
                    .toMutableSet(),
            )
            .putFloat(KeyMaster, masterVolume)
            .putLong(KeyTimerEnd, timerEndMs ?: 0L)
            .apply()
    }

    /**
     * Records whether the mix was playing. Written by the engine on every
     * transition, because a mix that was paused must stay silent after a restart.
     */
    fun setPlaying(context: Context, playing: Boolean) {
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(KeyPlaying, playing)
            .apply()
    }

    fun batteryPromptDismissed(context: Context): Boolean =
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .getBoolean(KeyBatteryDismissed, false)

    fun dismissBatteryPrompt(context: Context) {
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(KeyBatteryDismissed, true)
            .apply()
    }
}
