package app.respira.feature.meditation

import android.content.Context

/** Which YouTube stream the next link import should take. */
internal object ImportPreferences {

    private const val Preferences = "respira_import"
    private const val QualityKey = "quality"

    fun quality(context: Context): YouTubeQuality {
        val stored = preferences(context).getString(QualityKey, null)
        return stored?.let { value ->
            YouTubeQuality.entries.firstOrNull { it.name == value }
        } ?: YouTubeQuality.LIGHT
    }

    fun setQuality(context: Context, quality: YouTubeQuality) {
        preferences(context).edit().putString(QualityKey, quality.name).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
}
