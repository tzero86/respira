package app.respira.feature.ambience

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.respira.R
import app.respira.audio.MixerState
import app.respira.ui.components.GhostPill
import app.respira.ui.components.GlassCard
import app.respira.ui.components.PillButton
import app.respira.ui.theme.RespiraColors

/** A timer at least this long means the mix has to survive a whole night. */
private const val OvernightMinutes = 60

/**
 * Asks once, and only while a long timer is running, for the exemption that
 * stops OEM battery managers from killing playback at three in the morning.
 * Nothing appears for short naps, after the user said no, or once the system
 * already allows Respira to run unrestricted.
 */
@Composable
internal fun BatteryCard(timerEndMs: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(MixerState.batteryPromptDismissed(context)) }
    var exempt by remember { mutableStateOf(isBatteryExempt(context)) }

    // The user leaves for the settings screen and comes back; re-read the flag
    // so the card disappears as soon as the exemption is granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = isBatteryExempt(context)
    }

    val remaining = (timerEndMs ?: 0L) - System.currentTimeMillis()
    val overnight = remaining >= OvernightMinutes * 60_000L
    if (!overnight || exempt || dismissed) return

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.mixer_battery_title),
            style = MaterialTheme.typography.titleMedium,
            color = RespiraColors.Mist,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.mixer_battery_body),
            style = MaterialTheme.typography.bodySmall,
            color = RespiraColors.SecondaryText,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                text = stringResource(R.string.mixer_battery_action),
                onClick = { requestBatteryExemption(context) },
            )
            GhostPill(
                text = stringResource(R.string.mixer_battery_later),
                onClick = {
                    MixerState.dismissBatteryPrompt(context)
                    dismissed = true
                },
            )
        }
    }
}

private fun isBatteryExempt(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the system exemption dialog. Some builds refuse the direct request, so
 * the battery-optimisation list is the fallback.
 */
private fun requestBatteryExemption(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return

    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(list) }
}
