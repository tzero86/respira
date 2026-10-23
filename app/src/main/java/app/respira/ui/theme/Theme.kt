package app.respira.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RespiraColorScheme = darkColorScheme(
    primary = RespiraColors.Aqua,
    onPrimary = RespiraColors.Ink,
    primaryContainer = RespiraColors.AquaDim,
    onPrimaryContainer = RespiraColors.Ink,
    secondary = RespiraColors.Violet,
    onSecondary = RespiraColors.Ink,
    secondaryContainer = RespiraColors.SurfaceHigh,
    onSecondaryContainer = RespiraColors.Mist,
    tertiary = RespiraColors.Amber,
    onTertiary = RespiraColors.Ink,
    background = RespiraColors.Night,
    onBackground = RespiraColors.Mist,
    surface = RespiraColors.Deep,
    onSurface = RespiraColors.Mist,
    surfaceVariant = RespiraColors.Surface,
    onSurfaceVariant = RespiraColors.SecondaryText,
    surfaceTint = RespiraColors.Aqua,
    outline = RespiraColors.LineStrong,
    outlineVariant = RespiraColors.Line,
    error = RespiraColors.Rose,
    onError = RespiraColors.Ink,
)

@Composable
fun RespiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RespiraColorScheme,
        typography = RespiraTypography,
        content = content,
    )
}
