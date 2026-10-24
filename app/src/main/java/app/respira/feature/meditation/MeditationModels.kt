package app.respira.feature.meditation

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.respira.R
import app.respira.audio.AudioTrack
import app.respira.audio.TrackSource
import app.respira.ui.theme.RespiraColors

internal data class MeditationTrack(
    val track: AudioTrack,
    @DrawableRes val icon: Int,
    val accent: Color,
    val accentAlt: Color,
    /** Absolute path to a downloaded cover image, when the source had one. */
    val artwork: String? = null,
)

internal data class BundledMeditation(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @RawRes val rawResId: Int,
    val durationMs: Long,
    @DrawableRes val icon: Int,
    val accent: Color,
    val accentAlt: Color,
)

private const val EIGHT_MINUTES = 8 * 60_000L
private const val SEVEN_MINUTES = 7 * 60_000L

internal val bundledMeditations: List<BundledMeditation> = listOf(
    BundledMeditation(
        id = "med_still_water",
        titleRes = R.string.med_track_still_water,
        subtitleRes = R.string.med_track_still_water_subtitle,
        rawResId = R.raw.med_still_water,
        durationMs = EIGHT_MINUTES,
        icon = R.drawable.ic_droplets,
        accent = RespiraColors.Aqua,
        accentAlt = RespiraColors.Indigo,
    ),
    BundledMeditation(
        id = "med_night_sky",
        titleRes = R.string.med_track_night_sky,
        subtitleRes = R.string.med_track_night_sky_subtitle,
        rawResId = R.raw.med_night_sky,
        durationMs = EIGHT_MINUTES,
        icon = R.drawable.ic_moon_star,
        accent = RespiraColors.Indigo,
        accentAlt = RespiraColors.Violet,
    ),
    BundledMeditation(
        id = "med_sunrise",
        titleRes = R.string.med_track_sunrise,
        subtitleRes = R.string.med_track_sunrise_subtitle,
        rawResId = R.raw.med_sunrise,
        durationMs = SEVEN_MINUTES,
        icon = R.drawable.ic_sun,
        accent = RespiraColors.Amber,
        accentAlt = RespiraColors.Rose,
    ),
    BundledMeditation(
        id = "med_deep_rest",
        titleRes = R.string.med_track_deep_rest,
        subtitleRes = R.string.med_track_deep_rest_subtitle,
        rawResId = R.raw.med_deep_rest,
        durationMs = EIGHT_MINUTES,
        icon = R.drawable.ic_moon,
        accent = RespiraColors.Violet,
        accentAlt = RespiraColors.Indigo,
    ),
    BundledMeditation(
        id = "med_ocean_breath",
        titleRes = R.string.med_track_ocean_breath,
        subtitleRes = R.string.med_track_ocean_breath_subtitle,
        rawResId = R.raw.med_ocean_breath,
        durationMs = EIGHT_MINUTES,
        icon = R.drawable.ic_wind,
        accent = RespiraColors.Aqua,
        accentAlt = RespiraColors.Violet,
    ),
)

internal fun BundledMeditation.toMeditation(context: Context): MeditationTrack = MeditationTrack(
    track = AudioTrack(
        id = id,
        title = context.getString(titleRes),
        subtitle = context.getString(subtitleRes),
        source = TrackSource.Bundled(rawResId),
        durationMs = durationMs,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
    ),
    icon = icon,
    accent = accent,
    accentAlt = accentAlt,
)

internal fun AudioTrack.asMeditation(): MeditationTrack =
    bundledMeditations.firstOrNull { it.id == id }?.let { bundled ->
        MeditationTrack(
            track = this,
            icon = bundled.icon,
            accent = bundled.accent,
            accentAlt = bundled.accentAlt,
        )
    } ?: MeditationTrack(
        track = this,
        icon = R.drawable.ic_list_music,
        accent = RespiraColors.Indigo,
        accentAlt = RespiraColors.Violet,
    )
