package app.respira.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.respira.R
import app.respira.feature.ambience.AmbienceScreen
import app.respira.feature.meditation.MeditateScreen
import app.respira.ui.theme.RespiraColors

private enum class LibrarySection { TRACKS, SOUNDS }

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(LibrarySection.TRACKS) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        LibrarySwitcher(
            selected = section,
            onSelect = { section = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )

        Box(Modifier.weight(1f)) {
            when (section) {
                LibrarySection.TRACKS -> MeditateScreen()
                LibrarySection.SOUNDS -> AmbienceScreen()
            }
        }
    }
}

@Composable
private fun LibrarySwitcher(
    selected: LibrarySection,
    onSelect: (LibrarySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibrarySegment(
            label = stringResource(R.string.library_section_tracks),
            icon = R.drawable.ic_list_music,
            selected = selected == LibrarySection.TRACKS,
            onClick = { onSelect(LibrarySection.TRACKS) },
            modifier = Modifier.weight(1f),
        )
        LibrarySegment(
            label = stringResource(R.string.library_section_sounds),
            icon = R.drawable.ic_sliders_horizontal,
            selected = selected == LibrarySection.SOUNDS,
            onClick = { onSelect(LibrarySection.SOUNDS) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibrarySegment(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) RespiraColors.Aqua.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.04f),
            )
            .border(
                1.dp,
                if (selected) RespiraColors.Aqua.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f),
                shape,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (selected) RespiraColors.Aqua else RespiraColors.TertiaryText,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) RespiraColors.Aqua else RespiraColors.SecondaryText,
        )
    }
}
