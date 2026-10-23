package app.respira.audio

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import app.respira.R

data class AmbienceLayer(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val hintRes: Int,
    /** Key the UI maps to a drawable + accent color. */
    val icon: String,
    @RawRes val rawResId: Int,
)

data class AmbiencePreset(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val hintRes: Int,
    val levels: Map<String, Float>,
)

/**
 * Stable identifiers for the bundled soundscape. Audio engine, mixer UI and
 * presets all read from here so a layer can never drift out of sync.
 */
object AmbienceCatalog {
    val layers: List<AmbienceLayer> = listOf(
        AmbienceLayer("rain", R.string.amb_layer_rain, R.string.amb_layer_rain_hint, "rain", R.raw.amb_rain),
        AmbienceLayer("rain_heavy", R.string.amb_layer_rain_heavy, R.string.amb_layer_rain_heavy_hint, "rain", R.raw.amb_rain_heavy),
        AmbienceLayer("thunder", R.string.amb_layer_thunder, R.string.amb_layer_thunder_hint, "lightning", R.raw.amb_thunder),
        AmbienceLayer("wind", R.string.amb_layer_wind, R.string.amb_layer_wind_hint, "wind", R.raw.amb_wind),
        AmbienceLayer("stream", R.string.amb_layer_stream, R.string.amb_layer_stream_hint, "droplets", R.raw.amb_stream),
        AmbienceLayer("ocean", R.string.amb_layer_ocean, R.string.amb_layer_ocean_hint, "ocean", R.raw.amb_ocean),
        AmbienceLayer("fire", R.string.amb_layer_fire, R.string.amb_layer_fire_hint, "flame", R.raw.amb_fire),
        AmbienceLayer("forest", R.string.amb_layer_forest, R.string.amb_layer_forest_hint, "forest", R.raw.amb_forest),
        AmbienceLayer("birds", R.string.amb_layer_birds, R.string.amb_layer_birds_hint, "bird", R.raw.amb_birds),
        AmbienceLayer("drone", R.string.amb_layer_drone, R.string.amb_layer_drone_hint, "drone", R.raw.amb_drone),
    )

    val presets: List<AmbiencePreset> = listOf(
        AmbiencePreset(
            id = "rainy_night",
            nameRes = R.string.amb_preset_rainy_night,
            hintRes = R.string.amb_preset_rainy_night_hint,
            levels = mapOf("rain" to 0.72f, "thunder" to 0.30f, "wind" to 0.34f),
        ),
        AmbiencePreset(
            id = "storm",
            nameRes = R.string.amb_preset_storm,
            hintRes = R.string.amb_preset_storm_hint,
            levels = mapOf("rain_heavy" to 0.80f, "thunder" to 0.72f, "wind" to 0.50f),
        ),
        AmbiencePreset(
            id = "forest_stream",
            nameRes = R.string.amb_preset_forest_stream,
            hintRes = R.string.amb_preset_forest_stream_hint,
            levels = mapOf("stream" to 0.70f, "birds" to 0.30f, "wind" to 0.20f),
        ),
        AmbiencePreset(
            id = "ocean_calm",
            nameRes = R.string.amb_preset_ocean_calm,
            hintRes = R.string.amb_preset_ocean_calm_hint,
            levels = mapOf("ocean" to 0.80f, "wind" to 0.28f),
        ),
        AmbiencePreset(
            id = "fireside",
            nameRes = R.string.amb_preset_fireside,
            hintRes = R.string.amb_preset_fireside_hint,
            levels = mapOf("fire" to 0.75f, "forest" to 0.30f, "wind" to 0.18f),
        ),
        AmbiencePreset(
            id = "deep_calm",
            nameRes = R.string.amb_preset_deep_calm,
            hintRes = R.string.amb_preset_deep_calm_hint,
            levels = mapOf("drone" to 0.72f, "rain" to 0.30f),
        ),
    )

    fun layer(id: String): AmbienceLayer? = layers.firstOrNull { it.id == id }

    fun preset(id: String): AmbiencePreset? = presets.firstOrNull { it.id == id }
}
