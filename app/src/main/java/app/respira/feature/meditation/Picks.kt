package app.respira.feature.meditation

import android.content.Context

/**
 * A track the user pointed at — either from her library or the bundled set.
 * [entry] is only set for library tracks so the caller can pass it through to
 * play/remove without re-resolving the id.
 */
internal data class Pick(
    val meditation: MeditationTrack,
    val entry: LibraryTrack?,
)

internal fun resolvePick(
    context: Context,
    id: String?,
    bundled: List<MeditationTrack>,
    library: List<LibraryTrack>,
): Pick? {
    val key = id ?: return null
    if (key.startsWith("local:")) {
        val entry = library.firstOrNull { "local:${it.id}" == key } ?: return null
        if (!MeditationLibrary.canRead(context, entry.uri)) return null
        return Pick(entry.toMeditation(context), entry)
    }
    val meditation = bundled.firstOrNull { it.track.id == key } ?: return null
    return Pick(meditation, null)
}

/** The pinned nightly track, or null when unset or its file vanished. */
internal fun resolvePreferred(context: Context): Pick? {
    val id = MeditationLibrary.preferred(context) ?: return null
    return resolvePick(
        context = context,
        id = id,
        bundled = bundledMeditations.map { it.toMeditation(context) },
        library = MeditationLibrary.load(context),
    )
}

/** Starred tracks in the order they were starred, newest first. */
internal fun resolveFavorites(context: Context): List<Pick> {
    val ids = MeditationLibrary.favorites(context)
    if (ids.isEmpty()) return emptyList()
    val library = MeditationLibrary.load(context)
    val bundled = bundledMeditations.map { it.toMeditation(context) }
    return ids.mapNotNull { resolvePick(context, it, bundled, library) }
}
