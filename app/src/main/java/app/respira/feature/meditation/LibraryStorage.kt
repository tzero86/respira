package app.respira.feature.meditation

import android.content.Context
import java.io.File

internal data class StorageUsage(
    val libraryBytes: Long,
    val artworkBytes: Long,
    val partBytes: Long,
    val trackCount: Int,
) {
    val totalBytes: Long get() = libraryBytes + artworkBytes
}

internal object LibraryStorage {

    private const val PART_SUFFIX = ".part"
    private const val PART_STALE_MS = 60_000L

    /**
     * Current on-disk usage of the private library and artwork folders.
     * [partBytes] is the half-finished downloads sitting inside the library
     * folder, so it is part of [libraryBytes] and not extra on top of it.
     */
    fun usage(context: Context): StorageUsage {
        val tracks = MeditationLibrary.load(context)
        val library = MeditationLibrary.libraryDir(context)
        val artwork = artworkDir(context)
        var libraryBytes = 0L
        var partBytes = 0L
        for (file in library.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val size = file.length()
            libraryBytes += size
            if (file.name.endsWith(PART_SUFFIX)) partBytes += size
        }
        var artworkBytes = 0L
        for (file in artwork.listFiles().orEmpty()) {
            if (file.isFile) artworkBytes += file.length()
        }
        return StorageUsage(
            libraryBytes = libraryBytes,
            artworkBytes = artworkBytes,
            partBytes = partBytes,
            trackCount = tracks.size,
        )
    }

    /**
     * Deletes leftover `.part` files and artwork files no library entry points at.
     * Returns the number of bytes freed. A `.part` file younger than a minute is
     * left alone, and nothing is removed while a download is running.
     */
    fun sweep(context: Context): Long {
        val tracks = MeditationLibrary.load(context)
        val keptArtwork = tracks.mapNotNull { it.artwork }
            .map { path -> runCatching { File(path).canonicalPath }.getOrDefault(path) }
            .toSet()
        var freed = 0L

        if (Downloads.state.value !is DownloadState.Active) {
            val staleBefore = System.currentTimeMillis() - PART_STALE_MS
            for (file in MeditationLibrary.libraryDir(context).listFiles().orEmpty()) {
                if (!file.isFile || !file.name.endsWith(PART_SUFFIX)) continue
                if (file.lastModified() > staleBefore) continue
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) freed += size
            }
        }

        val artworkDir = artworkDir(context)
        for (file in artworkDir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
            if (path in keptArtwork) continue
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                ArtworkCache.evict(path)
                ArtworkCache.evict(file.absolutePath)
                freed += size
            }
        }

        return freed
    }
}
