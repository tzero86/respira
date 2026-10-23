package app.respira.feature.meditation

import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.R
import app.respira.ui.components.GhostPill
import app.respira.ui.components.GlassCard
import app.respira.ui.components.IconBadge
import app.respira.ui.components.PillButton
import app.respira.ui.components.SoftIconButton
import app.respira.ui.components.formatDuration
import app.respira.ui.theme.RespiraColors
import kotlinx.coroutines.launch

@Composable
internal fun LinkImportDialog(
    onDismiss: () -> Unit,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<LinkState>(LinkState.Idle) }
    var previous by remember { mutableStateOf<LinkState?>(null) }
    val download by Downloads.state.collectAsStateWithLifecycle()
    var lastDownload by remember { mutableStateOf(download) }

    fun analyze() {
        val value = url.trim()
        if (value.isEmpty() || state is LinkState.Inspecting || download is DownloadState.Active) return
        state = LinkState.Inspecting
        scope.launch {
            val result = inspectLink(context, value)
            state = when (result) {
                is LinkInspection.Audio -> LinkState.Direct(result, result.title)
                is LinkInspection.Feed -> LinkState.Feed(result)
                is LinkInspection.Unsupported -> LinkState.Unsupported(result.reason)
                is LinkInspection.Failure -> LinkState.Failed(result.reason)
            }
        }
    }

    fun startDownload(next: PendingDownload, restore: LinkState) {
        if (download is DownloadState.Active) return
        previous = restore
        state = restore
        Downloads.start(context, next.source, next.title)
    }

    LaunchedEffect(download) {
        if (download == lastDownload) return@LaunchedEffect
        lastDownload = download
        when (download) {
            is DownloadState.Done -> onImported()
            DownloadState.Idle -> previous?.let { restore ->
                previous = null
                state = restore
            }
            else -> Unit
        }
    }

    val busy = state is LinkState.Inspecting || download is DownloadState.Active

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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.med_add_from_link),
                                style = MaterialTheme.typography.headlineSmall,
                                color = RespiraColors.Mist,
                                modifier = Modifier.weight(1f),
                            )
                            SoftIconButton(
                                icon = R.drawable.ic_x,
                                contentDescription = stringResource(R.string.med_link_close),
                                onClick = onDismiss,
                                size = 40.dp,
                            )
                        }
                    }

                    item {
                        LinkField(
                            value = url,
                            onValueChange = { value ->
                                url = value
                                if (download !is DownloadState.Active) state = LinkState.Idle
                            },
                            label = stringResource(R.string.med_link_url_label),
                            placeholder = stringResource(R.string.med_link_url_hint),
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { analyze() }),
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GhostPill(
                                text = stringResource(R.string.med_link_paste),
                                onClick = {
                                    val clip = readClipboard(context)
                                    if (!clip.isNullOrBlank()) {
                                        url = clip
                                        state = LinkState.Idle
                                    }
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            PillButton(
                                text = stringResource(R.string.med_link_analyze),
                                onClick = { analyze() },
                                enabled = url.isNotBlank() && !busy,
                            )
                        }
                    }

                    when (val current = download) {
                        is DownloadState.Active -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = current.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = RespiraColors.Mist,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(R.string.med_link_downloading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = RespiraColors.SecondaryText,
                                    )
                                }
                            }
                            item {
                                val total = current.total
                                val fraction = total
                                    ?.takeIf { it > 0L }
                                    ?.let { (current.downloaded.toFloat() / it).coerceIn(0f, 1f) }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.08f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction ?: 1f)
                                                .fillMaxHeight()
                                                .background(
                                                    if (fraction == null) {
                                                        RespiraColors.Aqua.copy(alpha = 0.25f)
                                                    } else {
                                                        RespiraColors.Aqua
                                                    },
                                                ),
                                        )
                                    }
                                    Text(
                                        text = if (total != null) {
                                            stringResource(
                                                R.string.med_link_progress,
                                                Formatter.formatShortFileSize(
                                                    context,
                                                    current.downloaded,
                                                ),
                                                Formatter.formatShortFileSize(context, total),
                                            )
                                        } else {
                                            Formatter.formatShortFileSize(
                                                context,
                                                current.downloaded,
                                            )
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = RespiraColors.TertiaryText,
                                    )
                                }
                            }
                            item {
                                GhostPill(
                                    text = stringResource(R.string.med_link_cancel),
                                    onClick = { Downloads.cancel(context) },
                                )
                            }
                        }

                        is DownloadState.Failed -> {
                            item {
                                Text(
                                    text = downloadFailureText(current.failure),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = RespiraColors.Rose,
                                )
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PillButton(
                                        text = stringResource(R.string.med_retry),
                                        onClick = { Downloads.retry(context) },
                                    )
                                    GhostPill(
                                        text = stringResource(R.string.med_link_cancel),
                                        onClick = { Downloads.dismissFailure() },
                                    )
                                }
                            }
                        }

                        else -> when (val current = state) {
                            LinkState.Idle -> {
                                item {
                                    Text(
                                        text = stringResource(R.string.med_link_idle_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = RespiraColors.SecondaryText,
                                    )
                                }
                            }

                            LinkState.Inspecting -> {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = RespiraColors.Aqua,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.med_link_analyzing),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = RespiraColors.SecondaryText,
                                        )
                                    }
                                }
                            }

                            is LinkState.Direct -> {
                                item {
                                    LinkField(
                                        value = current.title,
                                        onValueChange = { title ->
                                            state = current.copy(title = title)
                                        },
                                        label = stringResource(R.string.med_link_title_label),
                                        enabled = true,
                                    )
                                }
                                if (current.audio.kind == AudioKind.YOUTUBE) {
                                    item {
                                        Text(
                                            text = stringResource(R.string.med_link_youtube_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = RespiraColors.SecondaryText,
                                        )
                                    }
                                    item {
                                        val kind = stringResource(R.string.med_link_kind_youtube)
                                        val duration = current.audio.durationMs
                                            ?.takeIf { it > 0L }
                                            ?.let { formatDuration(it) }
                                        val size = current.audio.sizeBytes
                                            ?.takeIf { it > 0L }
                                            ?.let { Formatter.formatShortFileSize(context, it) }
                                        val meta = mutableListOf<String>()
                                        meta += kind
                                        duration?.let { meta += it }
                                        size?.let { meta += it }
                                        Text(
                                            text = meta.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = RespiraColors.TertiaryText,
                                        )
                                    }
                                } else {
                                    current.audio.sizeBytes?.let { size ->
                                        item {
                                            Text(
                                                text = stringResource(
                                                    R.string.med_link_size,
                                                    Formatter.formatShortFileSize(context, size),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = RespiraColors.TertiaryText,
                                            )
                                        }
                                    }
                                }
                                item {
                                    PillButton(
                                        text = stringResource(R.string.med_link_download),
                                        onClick = {
                                            val latest = state as? LinkState.Direct ?: current
                                            val title = latest.title.trim()
                                                .ifBlank { latest.audio.title }
                                            startDownload(
                                                PendingDownload(latest.audio.source, title),
                                                latest,
                                            )
                                        },
                                    )
                                }
                            }

                            is LinkState.Feed -> {
                                item {
                                    Text(
                                        text = current.feed.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = RespiraColors.Mist,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (current.feed.episodes.isEmpty()) {
                                    item {
                                        Text(
                                            text = stringResource(R.string.med_link_no_episodes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = RespiraColors.SecondaryText,
                                        )
                                    }
                                } else {
                                    items(current.feed.episodes) { episode ->
                                        FeedEpisodeRow(
                                            episode = episode,
                                            onDownload = {
                                                startDownload(
                                                    PendingDownload(
                                                        AudioSource.Direct(episode.url),
                                                        episode.title,
                                                    ),
                                                    current,
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            is LinkState.Failed -> {
                                item {
                                    Text(
                                        text = failureText(current.reason),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = RespiraColors.Rose,
                                    )
                                }
                                item {
                                    PillButton(
                                        text = stringResource(R.string.med_retry),
                                        onClick = { analyze() },
                                    )
                                }
                            }

                            is LinkState.Unsupported -> {
                                item {
                                    Text(
                                        text = unsupportedText(current.reason),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = RespiraColors.SecondaryText,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val hint = placeholder
    val placeholderContent: (@Composable () -> Unit)? = if (hint != null) {
        { Text(text = hint, color = RespiraColors.TertiaryText) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(text = label) },
        placeholder = placeholderContent,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RespiraColors.Mist,
            unfocusedTextColor = RespiraColors.Mist,
            disabledTextColor = RespiraColors.TertiaryText,
            cursorColor = RespiraColors.Aqua,
            focusedBorderColor = RespiraColors.Aqua,
            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            disabledBorderColor = Color.White.copy(alpha = 0.08f),
            focusedLabelColor = RespiraColors.Aqua,
            unfocusedLabelColor = RespiraColors.TertiaryText,
            disabledLabelColor = RespiraColors.TertiaryText,
            focusedPlaceholderColor = RespiraColors.TertiaryText,
            unfocusedPlaceholderColor = RespiraColors.TertiaryText,
        ),
    )
}

@Composable
private fun FeedEpisodeRow(
    episode: FeedEpisode,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val meta = buildList<String> {
        episode.durationMs
            ?.takeIf { it > 0L }
            ?.let { add(formatDuration(it)) }
        episode.sizeBytes
            ?.takeIf { it > 0L }
            ?.let { add(Formatter.formatShortFileSize(context, it)) }
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = R.drawable.ic_audio_lines,
            tint = RespiraColors.Violet,
            size = 38.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyMedium,
                color = RespiraColors.Mist,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = RespiraColors.TertiaryText,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        GhostPill(
            text = stringResource(R.string.med_link_download),
            onClick = onDownload,
        )
    }
}

@Composable
private fun failureText(reason: FailureReason): String = when (reason) {
    FailureReason.NETWORK -> stringResource(R.string.med_link_network)
    FailureReason.TIMEOUT -> stringResource(R.string.med_link_timeout)
    FailureReason.TOO_LARGE -> stringResource(R.string.med_link_too_large)
    FailureReason.INVALID_RESPONSE -> stringResource(R.string.med_link_invalid_response)
    FailureReason.HTTP_ERROR -> stringResource(R.string.med_link_http_error)
}

@Composable
private fun downloadFailureText(failure: DownloadFailure): String = when (failure) {
    DownloadFailure.UNAVAILABLE -> stringResource(R.string.med_link_youtube_unavailable)
    DownloadFailure.EXPIRED -> stringResource(R.string.med_link_expired)
    DownloadFailure.TOO_LARGE -> stringResource(R.string.med_link_too_large)
    DownloadFailure.GENERIC -> stringResource(R.string.med_link_download_failed)
}

@Composable
private fun unsupportedText(reason: UnsupportedReason): String = when (reason) {
    UnsupportedReason.YOUTUBE -> stringResource(R.string.med_link_youtube)
    UnsupportedReason.YOUTUBE_UNAVAILABLE -> stringResource(R.string.med_link_youtube_unavailable)
    UnsupportedReason.NOT_AUDIO -> stringResource(R.string.med_link_not_audio)
    UnsupportedReason.INVALID_URL -> stringResource(R.string.med_link_invalid_url)
}

private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return runCatching {
        clip.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

private data class PendingDownload(val source: AudioSource, val title: String)

private sealed interface LinkState {
    data object Idle : LinkState
    data object Inspecting : LinkState
    data class Direct(val audio: LinkInspection.Audio, val title: String) : LinkState
    data class Feed(val feed: LinkInspection.Feed) : LinkState
    data class Failed(val reason: FailureReason) : LinkState
    data class Unsupported(val reason: UnsupportedReason) : LinkState
}
