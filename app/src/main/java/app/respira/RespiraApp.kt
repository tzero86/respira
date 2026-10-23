package app.respira

import android.app.Application
import android.content.Context
import app.respira.audio.Audio
import app.respira.i18n.LocalePreferences

class RespiraApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocalePreferences.localizedContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        Audio.bootstrap(this)
    }
}
