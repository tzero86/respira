package app.respira.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.respira.R
import app.respira.i18n.LocalePreferences
import app.respira.ui.theme.RespiraColors

@Composable
fun LanguageButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val current = LocalePreferences.language(context)
    var showDialog by remember { mutableStateOf(false) }

    GhostPill(
        text = if (current == LocalePreferences.SPANISH) "ES" else "EN",
        onClick = { showDialog = true },
        modifier = modifier,
    )

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                solidColor = RespiraColors.Night.copy(alpha = 0.98f),
                contentPadding = PaddingValues(22.dp),
            ) {
                Text(
                    text = stringResource(R.string.language_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = RespiraColors.Mist,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                )
                Spacer(Modifier.height(18.dp))
                LanguageOption(
                    label = stringResource(R.string.language_spanish),
                    selected = current == LocalePreferences.SPANISH,
                    onSelect = {
                        showDialog = false
                        if (current != LocalePreferences.SPANISH) {
                            LocalePreferences.setLanguage(context, LocalePreferences.SPANISH)
                            activity?.recreate()
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                LanguageOption(
                    label = stringResource(R.string.language_english),
                    selected = current == LocalePreferences.ENGLISH,
                    onSelect = {
                        showDialog = false
                        if (current != LocalePreferences.ENGLISH) {
                            LocalePreferences.setLanguage(context, LocalePreferences.ENGLISH)
                            activity?.recreate()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) RespiraColors.Aqua.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (selected) RespiraColors.Aqua.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) RespiraColors.Aqua else RespiraColors.Mist,
        )
        Box(
            modifier = Modifier
                .size(if (selected) 10.dp else 8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) RespiraColors.Aqua else Color.White.copy(alpha = 0.15f)),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
