package app.respira.feature.meditation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.respira.R
import app.respira.audio.Audio
import app.respira.ui.components.GhostPill
import app.respira.ui.components.GlassCard
import app.respira.ui.components.IconBadge
import app.respira.ui.components.PillButton
import app.respira.ui.components.ScreenHeader
import app.respira.ui.components.SectionLabel
import app.respira.ui.components.SoftIconButton
import app.respira.ui.components.formatDuration
import app.respira.ui.theme.RespiraColors

@Composable
fun MeditateScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by Audio.hub.state.collectAsStateWithLifecycle()
    val bundled = remember(context) { bundledMeditations.map { it.toMeditation(context) } }
    var library by remember { mutableStateOf(MeditationLibrary.load(context)) }
    var importFailed by remember { mutableStateOf(false) }
    var failedTrackId by remember { mutableStateOf<String?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val result = MeditationLibrary.import(context, uris, library)
        library = result.tracks
        importFailed = result.failed > 0
    }

    val openPicker = { picker.launch(arrayOf("audio/*")) }

    fun play(meditation: MeditationTrack, entry: LibraryTrack? = null) {
        if (entry != null && !MeditationLibrary.canRead(context, entry.uri)) {
            failedTrackId = entry.id
            return
        }
        failedTrackId = null
        Audio.hub.playTrack(meditation.track)
        showPlayer = true
    }

    fun select(meditation: MeditationTrack, entry: LibraryTrack? = null) {
        if (state.track?.id == meditation.track.id) {
            showPlayer = true
        } else {
            play(meditation, entry)
        }
    }

    fun toggle(meditation: MeditationTrack, entry: LibraryTrack? = null) {
        if (state.track?.id == meditation.track.id) {
            Audio.hub.toggleTrack()
        } else {
            play(meditation, entry)
        }
    }

    fun remove(entry: LibraryTrack, meditation: MeditationTrack) {
        if (state.track?.id == meditation.track.id) Audio.hub.stopTrack()
        library = MeditationLibrary.remove(context, library, entry)
        if (failedTrackId == entry.id) failedTrackId = null
    }

    LaunchedEffect(state.track) {
        if (state.track == null) showPlayer = false
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.med_title),
                subtitle = stringResource(R.string.med_subtitle),
            )
        }

        item {
            val pick = bundled.first()
            val active = state.track?.id == pick.track.id
            TonightPick(
                meditation = pick,
                active = active,
                playing = active && state.trackPlaying,
                onOpen = { select(pick) },
                onToggle = { toggle(pick) },
            )
        }

        item {
            SectionLabel(
                text = stringResource(R.string.med_section_built_in),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        items(bundled, key = { it.track.id }) { meditation ->
            val active = state.track?.id == meditation.track.id
            TrackRow(
                meditation = meditation,
                durationLabel = formatDuration(meditation.track.durationMs),
                active = active,
                playing = active && state.trackPlaying,
                onClick = { select(meditation) },
                onToggle = { toggle(meditation) },
                onRemove = null,
                error = null,
            )
        }

        item {
            SectionLabel(
                text = stringResource(R.string.med_section_library),
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (importFailed) {
            item {
                ImportErrorCard(onRetry = openPicker)
            }
        }

        if (library.isEmpty()) {
            item {
                LibraryEmpty(onAdd = openPicker)
            }
        } else {
            items(library, key = { it.id }) { entry ->
                val meditation = remember(entry.id, entry.uri) { entry.toMeditation(context) }
                val active = state.track?.id == meditation.track.id
                val error = if (failedTrackId == entry.id) {
                    stringResource(R.string.med_error_unavailable)
                } else {
                    null
                }
                TrackRow(
                    meditation = meditation,
                    durationLabel = stringResource(R.string.med_imported),
                    active = active,
                    playing = active && state.trackPlaying,
                    onClick = { select(meditation, entry) },
                    onToggle = { toggle(meditation, entry) },
                    onRemove = { remove(entry, meditation) },
                    error = error,
                )
            }
            item {
                GhostPill(
                    text = stringResource(R.string.med_add_from_device),
                    onClick = openPicker,
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = RespiraColors.Aqua,
                        )
                    },
                )
            }
        }
    }

    if (showPlayer) {
        state.track?.let { current ->
            MeditationPlayerDialog(
                track = current,
                meditation = current.asMeditation(),
                state = state,
                onClose = { showPlayer = false },
            )
        }
    }
}

@Composable
private fun TonightPick(
    meditation: MeditationTrack,
    active: Boolean,
    playing: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val playDescription = stringResource(
        if (active && playing) R.string.med_pause_track else R.string.med_play_track,
        meditation.track.title,
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = meditation.accent.copy(alpha = 0.30f),
        topTint = meditation.accent,
        onClick = onOpen,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(text = stringResource(R.string.med_tonights_pick))
            Spacer(Modifier.weight(1f))
            if (active) PlayingIndicator(playing)
        }
        Spacer(Modifier.height(12.dp))
        TrackArtwork(
            meditation = meditation,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(22.dp),
            iconSize = 52.dp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meditation.track.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = RespiraColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meditation.track.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDuration(meditation.track.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = RespiraColors.TertiaryText,
                )
            }
            Spacer(Modifier.width(12.dp))
            SoftIconButton(
                icon = if (active && playing) R.drawable.ic_pause else R.drawable.ic_play,
                contentDescription = playDescription,
                onClick = onToggle,
                size = 56.dp,
                tint = RespiraColors.Ink,
                background = RespiraColors.Aqua,
                bordered = false,
            )
        }
    }
}

@Composable
private fun TrackRow(
    meditation: MeditationTrack,
    durationLabel: String,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)?,
    error: String?,
) {
    val accent = meditation.accent
    val playDescription = stringResource(
        if (active && playing) R.string.med_pause_track else R.string.med_play_track,
        meditation.track.title,
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        borderColor = if (active) accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.10f),
        topTint = accent,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = meditation.icon, tint = accent, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meditation.track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = RespiraColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meditation.track.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = RespiraColors.TertiaryText,
                    )
                    if (active) {
                        Spacer(Modifier.width(10.dp))
                        PlayingIndicator(playing)
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = RespiraColors.Rose,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SoftIconButton(
                icon = if (active && playing) R.drawable.ic_pause else R.drawable.ic_play,
                contentDescription = playDescription,
                onClick = onToggle,
                size = 44.dp,
            )
            if (onRemove != null) {
                Spacer(Modifier.width(6.dp))
                SoftIconButton(
                    icon = R.drawable.ic_x,
                    contentDescription = stringResource(
                        R.string.med_remove_track,
                        meditation.track.title,
                    ),
                    onClick = onRemove,
                    size = 44.dp,
                    tint = RespiraColors.SecondaryText,
                )
            }
        }
    }
}

@Composable
private fun PlayingIndicator(playing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (playing) RespiraColors.Aqua else RespiraColors.TertiaryText),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (playing) R.string.med_playing else R.string.med_paused,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (playing) RespiraColors.Aqua else RespiraColors.SecondaryText,
        )
    }
}

@Composable
private fun LibraryEmpty(onAdd: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        topTint = RespiraColors.Indigo,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = R.drawable.ic_folder_open, tint = RespiraColors.Indigo, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.med_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = RespiraColors.Mist,
                )
                Text(
                    text = stringResource(R.string.med_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        PillButton(
            text = stringResource(R.string.med_add_from_device),
            onClick = onAdd,
            leading = {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = RespiraColors.Ink,
                )
            },
        )
    }
}

@Composable
private fun ImportErrorCard(onRetry: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = RespiraColors.Amber.copy(alpha = 0.35f),
        topTint = RespiraColors.Amber,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = R.drawable.ic_cloud_lightning, tint = RespiraColors.Amber, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.med_import_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = RespiraColors.Mist,
                )
                Text(
                    text = stringResource(R.string.med_import_error_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                )
            }
            Spacer(Modifier.width(8.dp))
            GhostPill(text = stringResource(R.string.med_retry), onClick = onRetry)
        }
    }
}
