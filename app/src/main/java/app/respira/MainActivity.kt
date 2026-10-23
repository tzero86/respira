package app.respira

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import app.respira.feature.meditation.LibraryEvents
import app.respira.feature.meditation.importSharedAudio
import app.respira.i18n.LocalePreferences
import app.respira.ui.AppNavigation
import app.respira.ui.RespiraApp
import app.respira.ui.Routes
import app.respira.ui.theme.RespiraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalePreferences.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            RespiraTheme {
                RespiraApp()
            }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Recreating the activity for a language change can restore window insets.
        applyEdgeToEdge()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.sharedStreamUri() ?: return
        intent.removeExtra(Intent.EXTRA_STREAM)

        val mime = runCatching { intent.type ?: contentResolver.getType(uri) }.getOrNull()
        if (mime == null || !(mime.startsWith("audio/") || mime == "application/ogg")) return

        Toast.makeText(this, R.string.med_share_importing, Toast.LENGTH_SHORT).show()
        shareScope.launch {
            val track = runCatching { importSharedAudio(applicationContext, uri, mime) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (track != null) {
                    LibraryEvents.bump()
                    AppNavigation.open(Routes.Meditate)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.med_share_added, track.title),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.med_share_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun Intent.sharedStreamUri(): Uri? =
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)

    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
    }
}
