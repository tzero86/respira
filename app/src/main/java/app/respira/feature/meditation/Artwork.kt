package app.respira.feature.meditation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
private const val ARTWORK_SAMPLE = 2
private const val ARTWORK_CACHE_ENTRIES = 24
private const val ARTWORK_TIMEOUT_MS = 15_000
private const val ARTWORK_USER_AGENT = "Respira/0.1 (Android)"

internal fun artworkDir(context: Context): File = File(context.filesDir, "artwork")

/**
 * Downloads a cover image next to the library. Returns the absolute path, or
 * null when the URL is dead, oversized or not decodable — callers fall back to
 * the generated gradient artwork.
 */
internal suspend fun fetchArtwork(context: Context, url: String, key: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = ARTWORK_TIMEOUT_MS
                readTimeout = ARTWORK_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ARTWORK_USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                val bytes = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_ARTWORK_BYTES) break
                    }
                    out.toByteArray()
                }
                if (bytes.isEmpty()) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val directory = artworkDir(context).apply { mkdirs() }
                val file = File(directory, "$key.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            null
        }
    }

internal fun deleteArtwork(context: Context, path: String?) {
    val value = path ?: return
    val directory = runCatching { artworkDir(context).canonicalFile }.getOrNull() ?: return
    val file = runCatching { File(value).canonicalFile }.getOrNull() ?: return
    if (file.parentFile == directory) {
        ArtworkCache.evict(file.absolutePath)
        runCatching { file.delete() }
    }
}

/**
 * Decoded covers, bounded and keyed by path. Covers are small and few, so a
 * plain LRU is enough and keeps scrolling free of re-decodes.
 */
internal object ArtworkCache {

    private val entries = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > ARTWORK_CACHE_ENTRIES
    }

    @Synchronized
    fun get(path: String): ImageBitmap? = entries[path]

    @Synchronized
    fun put(path: String, bitmap: ImageBitmap) {
        entries[path] = bitmap
    }

    @Synchronized
    fun evict(path: String) {
        entries.remove(path)
    }

    suspend fun load(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.canRead()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ARTWORK_SAMPLE
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext null
        bitmap.asImageBitmap().also { put(path, it) }
    }
}

@Composable
internal fun rememberArtwork(path: String?): ImageBitmap? {
    if (path == null) return null
    val state = produceState(initialValue = ArtworkCache.get(path), key1 = path) {
        if (value == null) value = ArtworkCache.load(path)
    }
    return state.value
}
