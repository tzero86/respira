package app.respira.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.respira.R
import app.respira.ui.theme.RespiraColors

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    borderColor: Color = Color.White.copy(alpha = 0.10f),
    topTint: Color = Color.White,
    solidColor: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .clip(shape)
            .then(if (solidColor != null) Modifier.background(solidColor) else Modifier)
            .background(
                Brush.verticalGradient(
                    listOf(topTint.copy(alpha = 0.08f), topTint.copy(alpha = 0.025f)),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.15f), borderColor),
                ),
                shape,
            )
            .then(clickModifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) RespiraColors.Aqua else RespiraColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) RespiraColors.Ink else RespiraColors.TertiaryText,
        )
    }
}

@Composable
fun GhostPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) RespiraColors.Aqua.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (selected) RespiraColors.Aqua.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.09f),
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) RespiraColors.Aqua else RespiraColors.SecondaryText,
            maxLines = 1,
        )
    }
}

@Composable
fun SoftIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    tint: Color = RespiraColors.Mist,
    background: Color = Color.White.copy(alpha = 0.06f),
    bordered: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(
                if (bordered) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape) else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.42f),
            tint = tint,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = RespiraColors.TertiaryText,
    )
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            SoftIconButton(R.drawable.ic_chevron_left, "Back", onBack)
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = RespiraColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun IconBadge(
    @DrawableRes icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(size * 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(size * 0.44f),
            tint = tint,
        )
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        "%d:%02d:%02d".format(hours, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
