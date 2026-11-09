package app.respira.feature.breathe

import android.content.Context

/** Remembers the haptics and sound toggles between launches. */
internal object BreathePreferences {
    private const val FILE = "breathe_prefs"
    private const val KEY_HAPTICS = "haptics_on"
    private const val KEY_SOUND = "sound_on"

    fun hapticsOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    fun soundOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setHapticsOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, on).apply()
    }

    fun setSoundOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, on).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
