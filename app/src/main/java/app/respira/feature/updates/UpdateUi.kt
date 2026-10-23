package app.respira.feature.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.R
import app.respira.ui.components.GlassCard
import app.respira.ui.components.GhostPill
import app.respira.ui.components.IconBadge
import app.respira.ui.components.PillButton
import app.respira.ui.components.SectionLabel
import app.respira.ui.theme.RespiraColors
import kotlinx.coroutines.launch

@Composable
internal fun UpdateBanner(onOpen: () -> Unit) {
    val info by UpdateCenter.available.collectAsStateWithLifecycle()
    val current = info ?: return
    GlassCard(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = R.drawable.ic_wind_arrow_down, tint = RespiraColors.Aqua, size = 50.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title, current.versionName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = RespiraColors.Mist,
                )
                Text(
                    text = stringResource(R.string.update_available_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = RespiraColors.TertiaryText,
            )
        }
    }
}

@Composable
internal fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by UpdateCenter.available.collectAsStateWithLifecycle()
    val info = update
    val state by UpdateCenter.state.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                solidColor = RespiraColors.Night,
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SectionLabel(stringResource(R.string.update_section))
                    Spacer(Modifier.height(8.dp))

                    if (info != null) {
                        Text(
                            text = stringResource(R.string.update_available_title, info.versionName),
                            style = MaterialTheme.typography.headlineSmall,
                            color = RespiraColors.Mist,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.update_available_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = RespiraColors.SecondaryText,
                        )
                    }

                    val notes = info?.notes.orEmpty()
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        SectionLabel(stringResource(R.string.update_notes_title))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = notes,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = RespiraColors.SecondaryText,
                        )
                    }

                    val current = state
                    if (current is UpdateState.Downloading) {
                        Spacer(Modifier.height(16.dp))
                        val percent = current.percent
                        if (percent != null) {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = RespiraColors.Aqua,
                                trackColor = Color.White.copy(alpha = 0.08f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.update_progress, percent),
                                style = MaterialTheme.typography.bodySmall,
                                color = RespiraColors.TertiaryText,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = RespiraColors.Aqua,
                                trackColor = Color.White.copy(alpha = 0.08f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.update_downloading),
                                style = MaterialTheme.typography.bodySmall,
                                color = RespiraColors.TertiaryText,
                            )
                        }
                    }

                    if (current is UpdateState.Error) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.update_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = RespiraColors.Rose,
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (info != null) {
                            GhostPill(
                                text = stringResource(R.string.update_open_browser),
                                onClick = { UpdateCenter.openBrowser(context) },
                            )
                            Spacer(Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        GhostPill(
                            text = stringResource(R.string.update_later),
                            onClick = {
                                UpdateCenter.dismiss()
                                onDismiss()
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    when (current) {
                        is UpdateState.Downloading -> PillButton(
                            text = stringResource(R.string.update_cancel),
                            onClick = { UpdateCenter.cancelDownload() },
                        )

                        UpdateState.Ready, UpdateState.Installing -> PillButton(
                            text = stringResource(R.string.update_install),
                            onClick = { UpdateCenter.install(context) },
                        )

                        UpdateState.Error -> PillButton(
                            text = stringResource(R.string.update_retry),
                            onClick = {
                                if (info != null) {
                                    UpdateCenter.startDownload(context)
                                } else {
                                    scope.launch {
                                        if (UpdateCenter.check(context, manual = true) ==
                                            CheckOutcome.UP_TO_DATE
                                        ) {
                                            onDismiss()
                                        }
                                    }
                                }
                            },
                        )

                        UpdateState.Idle -> PillButton(
                            text = stringResource(R.string.update_download),
                            enabled = info != null,
                            onClick = { UpdateCenter.startDownload(context) },
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.update_permission_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = RespiraColors.TertiaryText,
                    )
                }
            }
        }
    }
}
