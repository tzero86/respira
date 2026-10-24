package app.respira.audio

import android.content.Context

/**
 * Remembers where each track stopped so bedtime playback can resume there.
 */
internal object PlaybackMemory {
    fun remember(context: Context, trackId: String, positionMs: Long) {
        preferences(context)
            .edit()
            .putLong(positionKey(trackId), positionMs.coerceAtLeast(0L))
            .putString(KEY_LAST_TRACK, trackId)
            .putLong(KEY_LAST_PLAYED_AT, System.currentTimeMillis())
            .apply()
    }

    fun position(context: Context, trackId: String): Long =
        preferences(context).getLong(positionKey(trackId), 0L)

    fun lastTrackId(context: Context): String? =
        preferences(context).getString(KEY_LAST_TRACK, null)

    fun lastPosition(context: Context): Long =
        lastTrackId(context)?.let { position(context, it) } ?: 0L

    fun lastPlayedAt(context: Context): Long =
        preferences(context).getLong(KEY_LAST_PLAYED_AT, 0L)

    fun forget(context: Context, trackId: String) {
        preferences(context).edit().remove(positionKey(trackId)).apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun positionKey(trackId: String) = "$KEY_POSITION_PREFIX$trackId"

    private const val PREFS = "respira_playback"
    private const val KEY_POSITION_PREFIX = "pos:"
    private const val KEY_LAST_TRACK = "last_track"
    private const val KEY_LAST_PLAYED_AT = "last_played_at"
}
