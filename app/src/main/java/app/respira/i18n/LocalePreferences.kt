package app.respira.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language with Spanish as the factory default regardless of the
 * device locale. The chosen locale is applied by wrapping the Application and
 * Activity base contexts, so resources, Compose strings and notifications all
 * resolve consistently.
 */
object LocalePreferences {
    const val SPANISH = "es"
    const val ENGLISH = "en"

    private const val PREFS = "respira_locale"
    private const val KEY = "language"

    val supported: List<String> = listOf(SPANISH, ENGLISH)

    fun language(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return if (stored in supported) stored.orEmpty() else SPANISH
    }

    fun setLanguage(context: Context, language: String) {
        if (language !in supported) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language)
            .apply()
    }

    fun localizedContext(base: Context): Context {
        val locale = Locale(language(base))
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(configuration)
    }
}
