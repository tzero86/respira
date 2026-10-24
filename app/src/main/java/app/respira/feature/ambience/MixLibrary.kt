package app.respira.feature.ambience

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved mix the user named and can reload. */
internal data class SavedMix(
    val id: String,
    val name: String,
    val layers: Map<String, Float>,
    val masterVolume: Float,
)

internal object MixLibrary {
    private const val Preferences = "respira_mixes"
    private const val MixesKey = "mixes"
    private const val SilentThreshold = 0.001f

    /** Named mixes, most recently saved first. */
    fun saved(context: Context): List<SavedMix> {
        val raw = preferences(context).getString(MixesKey, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                add(
                    SavedMix(
                        id = id,
                        name = name,
                        layers = readLayers(item.optJSONObject("layers")),
                        masterVolume = item.optDouble("master", 1.0).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }
    }

    /**
     * Stores [name] with [layers] and [masterVolume]. A mix with no audible
     * layer is never written; the mix already carrying that name comes back
     * untouched instead.
     */
    @Synchronized
    fun save(
        context: Context,
        name: String,
        layers: Map<String, Float>,
        masterVolume: Float,
    ): SavedMix {
        val current = saved(context)
        val trimmed = name.trim()
        val existing = current.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val active = layers
            .filterValues { it > SilentThreshold }
            .mapValues { (_, volume) -> volume.coerceIn(0f, 1f) }
        if (active.isEmpty() || trimmed.isEmpty()) {
            return existing ?: SavedMix(
                id = "",
                name = trimmed,
                layers = emptyMap(),
                masterVolume = masterVolume.coerceIn(0f, 1f),
            )
        }
        val mix = SavedMix(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = trimmed,
            layers = active,
            masterVolume = masterVolume.coerceIn(0f, 1f),
        )
        persist(context, listOf(mix) + current.filterNot { it.id == mix.id })
        return mix
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        if (id.isBlank()) return
        persist(context, saved(context).filterNot { it.id == id })
    }

    private fun readLayers(source: JSONObject?): Map<String, Float> {
        if (source == null) return emptyMap()
        val layers = mutableMapOf<String, Float>()
        source.keys().forEach { id ->
            val volume = source.optDouble(id, 0.0).toFloat()
            if (volume > SilentThreshold) layers[id] = volume.coerceIn(0f, 1f)
        }
        return layers
    }

    private fun persist(context: Context, mixes: List<SavedMix>) {
        val array = JSONArray()
        mixes.forEach { mix ->
            val layers = JSONObject()
            mix.layers.forEach { (id, volume) -> layers.put(id, volume.toDouble()) }
            array.put(
                JSONObject()
                    .put("id", mix.id)
                    .put("name", mix.name)
                    .put("layers", layers)
                    .put("master", mix.masterVolume.toDouble()),
            )
        }
        preferences(context).edit().putString(MixesKey, array.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
}
