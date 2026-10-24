package app.respira.feature.meditation

import android.content.Context
import android.util.Log
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import app.respira.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

internal enum class UnsupportedReason { YOUTUBE, YOUTUBE_UNAVAILABLE, NOT_AUDIO, INVALID_URL }

internal enum class FailureReason { NETWORK, TIMEOUT, TOO_LARGE, INVALID_RESPONSE, HTTP_ERROR }

internal sealed interface AudioSource {
    data class Direct(val url: String) : AudioSource
    data class YouTube(val videoId: String) : AudioSource
}

internal enum class AudioKind { DIRECT, YOUTUBE }

internal data class FeedEpisode(
    val title: String,
    val url: String,
    val durationMs: Long?,
    val sizeBytes: Long?,
)

internal sealed interface LinkInspection {
    data class Audio(
        val source: AudioSource,
        val title: String,
        val sizeBytes: Long?,
        val durationMs: Long? = null,
        val kind: AudioKind = AudioKind.DIRECT,
    ) : LinkInspection
    data class Feed(val url: String, val title: String, val episodes: List<FeedEpisode>) : LinkInspection
    data class Unsupported(val reason: UnsupportedReason) : LinkInspection
    data class Failure(val reason: FailureReason) : LinkInspection
}

private const val USER_AGENT = "Respira/0.1 (Android)"
private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_REDIRECTS = 5
private const val MAX_FEED_BYTES = 3 * 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 300L * 1024 * 1024
private const val MAX_EPISODES = 200
private const val COPY_BUFFER = 64 * 1024
private const val MAX_STREAM_ATTEMPTS = 2
private const val RANGE_FROM_START = "bytes=0-"
private const val DOWNLOAD_WORKERS = 4
private const val CHUNK_BYTES = 4L * 1024 * 1024
private const val CHUNK_ATTEMPTS = 2
private const val PROGRESS_STEP = 256 * 1024L
private val EXPIRED_STREAM_CODES = setOf(403, 410)

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

private val ACCEPT_HEADER =
    "audio/*, application/ogg, application/rss+xml, application/atom+xml, " +
        "application/xml, text/xml, */*;q=0.1"

private val BARE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

private class FeedTooLargeException : IOException("Feed exceeds 3 MB")
private class TooManyRedirectsException : IOException("Too many redirects")
private class DownloadTooLargeException : IOException("Download exceeds 300 MB")

internal suspend fun inspectLink(context: Context, rawUrl: String): LinkInspection =
    withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        if (isYouTubeLink(rawUrl, url)) return@withContext resolveYouTube(context, rawUrl)
        val parsed = url
            ?: return@withContext LinkInspection.Unsupported(UnsupportedReason.INVALID_URL)
        try {
            inspectUrl(context, parsed)
        } catch (error: SocketTimeoutException) {
            LinkInspection.Failure(FailureReason.TIMEOUT)
        } catch (error: FeedTooLargeException) {
            LinkInspection.Failure(FailureReason.TOO_LARGE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            LinkInspection.Failure(FailureReason.NETWORK)
        } catch (error: Exception) {
            LinkInspection.Failure(FailureReason.INVALID_RESPONSE)
        }
    }

private fun isYouTubeLink(raw: String, url: URL?): Boolean {
    val trimmed = raw.trim()
    if (!trimmed.contains('.') && BARE_VIDEO_ID.matches(trimmed)) return true
    return url != null && isYouTubeHost(url.host)
}

private fun resolveYouTube(context: Context, raw: String): LinkInspection {
    val id = YouTubeResolver.videoId(raw)
        ?: return LinkInspection.Unsupported(UnsupportedReason.YOUTUBE)
    val audio = YouTubeResolver.resolve(id, ImportPreferences.quality(context)).getOrElse {
        return LinkInspection.Unsupported(UnsupportedReason.YOUTUBE_UNAVAILABLE)
    }
    return LinkInspection.Audio(
        source = AudioSource.YouTube(id),
        title = audio.title,
        sizeBytes = audio.sizeBytes,
        durationMs = audio.durationMs,
        kind = AudioKind.YOUTUBE,
    )
}

internal suspend fun downloadAudio(
    context: Context,
    source: AudioSource,
    title: String,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
): Result<LibraryTrack> = withContext(Dispatchers.IO) {
    var partFile: File? = null
    var targetFile: File? = null
    try {
        val stream = when (source) {
            is AudioSource.YouTube ->
                resolveYouTubeStream(source.videoId, ImportPreferences.quality(context))
            is AudioSource.Direct -> {
                val parsed = normalizeUrl(source.url) ?: throw IOException("Invalid audio URL")
                val opened = openFollowingRedirects(parsed, range = RANGE_FROM_START)
                val redirectedId = if (isYouTubeHost(opened.second.host)) {
                    opened.first.disconnect()
                    YouTubeResolver.videoId(opened.second.toString())
                        ?: throw IOException("YouTube playlist link")
                } else {
                    null
                }
                if (redirectedId != null) {
                    resolveYouTubeStream(redirectedId, ImportPreferences.quality(context))
                } else {
                    val code = opened.first.responseCode
                    AudioStream(
                        connection = opened.first,
                        finalUrl = opened.second,
                        sizeHint = null,
                        rangeTotal = if (code == 206) {
                            contentRangeTotal(opened.first.getHeaderField("Content-Range"))
                        } else {
                            null
                        },
                        mimeHint = null,
                        isYouTube = false,
                    )
                }
            }
        }
        try {
            val code = stream.connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val extension = if (stream.isYouTube) {
                youtubeExtension(stream.mimeHint ?: stream.connection.contentType)
            } else {
                extensionFor(
                    contentType = stream.connection.contentType?.substringBefore(';')?.trim()?.lowercase(),
                    path = stream.finalUrl.path,
                    fileName = fileNameFromDisposition(
                        stream.connection.getHeaderField("Content-Disposition"),
                    ),
                ) ?: "mp3"
            }
            val displayTitle = title.trim().ifBlank { context.getString(R.string.med_imported_track) }

            val directory = MeditationLibrary.libraryDir(context).apply { mkdirs() }
            val fileName = "${sanitizeFileName(displayTitle)}-${UUID.randomUUID().toString().take(8)}.$extension"
            val target = File(directory, fileName)
            val part = File(directory, "$fileName.part")
            partFile = part
            targetFile = target

            val compressed = stream.connection.contentEncoding
                ?.equals("gzip", ignoreCase = true) == true
            val total = if (compressed) {
                null
            } else {
                stream.connection.contentLengthLong.takeIf { it > 0L } ?: stream.sizeHint
            }
            if (total != null && total > MAX_DOWNLOAD_BYTES) throw DownloadTooLargeException()

            val rangeTotal = stream.rangeTotal
                ?.takeIf { !compressed && it <= MAX_DOWNLOAD_BYTES }

            if (rangeTotal != null) {
                stream.connection.disconnect()
                onProgress(0L, rangeTotal)
                chunkedDownload(
                    url = stream.finalUrl,
                    part = part,
                    total = rangeTotal,
                    userAgent = if (stream.isYouTube) YOUTUBE_USER_AGENT else USER_AGENT,
                    onProgress = onProgress,
                )
            } else {
                onProgress(0L, total)
                streamFor(stream.connection).use { input ->
                    FileOutputStream(part).use { output ->
                        copyDownload(input, output, total, onProgress)
                    }
                }
            }

            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            partFile = null

            val artwork = stream.artworkUrl?.let { url ->
                fetchArtwork(context, url, fileName.substringBeforeLast('.'))
            }

            val track = MeditationLibrary.addTrack(
                context = context,
                title = displayTitle,
                uri = Uri.fromFile(target).toString(),
                origin = ORIGIN_LINK,
                artwork = artwork,
            )
            targetFile = null
            Result.success(track)
        } finally {
            stream.connection.disconnect()
        }
    } catch (error: CancellationException) {
        partFile?.delete()
        targetFile?.delete()
        throw error
    } catch (error: Exception) {
        partFile?.delete()
        targetFile?.delete()
        Result.failure(error)
    }
}

internal suspend fun importSharedAudio(
    context: Context,
    uri: Uri,
    mimeType: String?,
): LibraryTrack? = withContext(Dispatchers.IO) {
    var partFile: File? = null
    var targetFile: File? = null
    try {
        val resolver = context.contentResolver
        val resolvedType = runCatching { resolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: mimeType
        val displayName = queryDisplayName(context, uri)
        val title = displayName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.med_imported_track)
        val extension = extensionFor(
            contentType = resolvedType,
            path = uri.lastPathSegment,
            fileName = displayName,
        ) ?: "mp3"

        val directory = MeditationLibrary.libraryDir(context).apply { mkdirs() }
        val fileName = "${sanitizeFileName(title)}-${UUID.randomUUID().toString().take(8)}.$extension"
        val target = File(directory, fileName)
        val part = File(directory, "$fileName.part")
        partFile = part
        targetFile = target

        val source = resolver.openInputStream(uri) ?: return@withContext null
        source.use { input ->
            FileOutputStream(part).use { output -> copyLimited(input, output) }
        }

        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        partFile = null

        val track = MeditationLibrary.addTrack(
            context = context,
            title = title,
            uri = Uri.fromFile(target).toString(),
            origin = ORIGIN_SHARED,
        )
        targetFile = null
        track
    } catch (error: CancellationException) {
        partFile?.delete()
        targetFile?.delete()
        throw error
    } catch (error: Exception) {
        Log.w("MediaLinkImporter", "Shared audio import failed", error)
        partFile?.delete()
        targetFile?.delete()
        null
    }
}

private fun inspectUrl(context: Context, start: URL): LinkInspection {
    val (connection, finalUrl) = openFollowingRedirects(start)
    try {
        if (isYouTubeHost(finalUrl.host)) return resolveYouTube(context, finalUrl.toString())
        val code = connection.responseCode
        if (code !in 200..299) return LinkInspection.Failure(FailureReason.HTTP_ERROR)

        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
        val path = finalUrl.path.orEmpty()
        val disposition = connection.getHeaderField("Content-Disposition")

        if (isAudioContentType(contentType) || hasAudioExtension(path)) {
            return LinkInspection.Audio(
                source = AudioSource.Direct(finalUrl.toString()),
                title = titleForAudio(context, finalUrl, disposition),
                sizeBytes = connection.contentLengthLong.takeIf { it > 0L },
            )
        }

        if (isFeedCandidate(contentType, path)) {
            if (connection.contentLengthLong > MAX_FEED_BYTES) throw FeedTooLargeException()
            val bytes = streamFor(connection).use { input ->
                readLimited(input, MAX_FEED_BYTES)
            }
            val parsed = parseFeed(context, finalUrl, bytes)
                ?: return LinkInspection.Unsupported(UnsupportedReason.NOT_AUDIO)
            return LinkInspection.Feed(
                url = finalUrl.toString(),
                title = parsed.title,
                episodes = parsed.episodes,
            )
        }

        return LinkInspection.Unsupported(UnsupportedReason.NOT_AUDIO)
    } finally {
        connection.disconnect()
    }
}

private fun normalizeUrl(raw: String): URL? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (!trimmed.contains("://") &&
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!//)(?!\\d)").containsMatchIn(trimmed)
    ) {
        return null
    }
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val url = runCatching { URL(candidate) }.getOrNull() ?: return null
    if (url.protocol.lowercase() !in setOf("http", "https")) return null
    if (url.host.isNullOrBlank()) return null
    return url
}

private fun openFollowingRedirects(
    start: URL,
    userAgent: String = USER_AGENT,
    range: String? = null,
): Pair<HttpURLConnection, URL> {
    var current = start
    var redirects = 0
    while (true) {
        val connection = current.openConnection() as? HttpURLConnection
            ?: throw IOException("Not an HTTP connection")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", ACCEPT_HEADER)
        connection.setRequestProperty("Accept-Encoding", "identity")
        range?.let { connection.setRequestProperty("Range", it) }

        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw IOException("Redirect without location")
            if (redirects >= MAX_REDIRECTS) throw TooManyRedirectsException()
            val next = runCatching { URL(current, location) }.getOrNull()
                ?: throw IOException("Invalid redirect")
            if (next.protocol.lowercase() !in setOf("http", "https")) {
                throw IOException("Invalid redirect scheme")
            }
            current = next
            redirects += 1
            continue
        }
        return connection to current
    }
}

private class AudioStream(
    val connection: HttpURLConnection,
    val finalUrl: URL,
    val sizeHint: Long?,
    val rangeTotal: Long?,
    val mimeHint: String?,
    val isYouTube: Boolean,
    val artworkUrl: String? = null,
)

private fun resolveYouTubeStream(videoId: String, quality: YouTubeQuality): AudioStream {
    var lastError: IOException? = null
    repeat(MAX_STREAM_ATTEMPTS) {
        val audio = YouTubeResolver.resolve(videoId, quality).getOrElse { error ->
            throw (error as? IOException) ?: IOException("YouTube resolve failed", error)
        }
        val url = normalizeUrl(audio.streamUrl) ?: throw IOException("Invalid stream URL")
        val (connection, finalUrl) = openFollowingRedirects(
            url,
            YOUTUBE_USER_AGENT,
            range = RANGE_FROM_START,
        )
        val code = connection.responseCode
        if (code in 200..299) {
            return AudioStream(
                connection = connection,
                finalUrl = finalUrl,
                sizeHint = audio.sizeBytes,
                rangeTotal = if (code == 206) {
                    contentRangeTotal(connection.getHeaderField("Content-Range"))
                } else {
                    null
                },
                mimeHint = audio.mimeType,
                isYouTube = true,
                artworkUrl = audio.artworkUrl,
            )
        }
        connection.disconnect()
        if (code !in EXPIRED_STREAM_CODES) throw IOException("HTTP $code")
        lastError = IOException("HTTP $code")
    }
    throw lastError ?: IOException("YouTube stream failed")
}

private fun contentRangeTotal(header: String?): Long? =
    header?.substringAfter('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0L }

private fun youtubeExtension(contentType: String?): String {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        type == null -> "m4a"
        type == "audio/mp4" || type == "audio/x-m4a" || type == "audio/m4a" -> "m4a"
        type == "audio/webm" -> "webm"
        type == "audio/ogg" || type == "application/ogg" || type == "audio/opus" -> "ogg"
        else -> "m4a"
    }
}

private fun isYouTubeHost(host: String?): Boolean {
    val value = host?.lowercase() ?: return false
    return value == "youtu.be" ||
        value == "youtube.com" ||
        value.endsWith(".youtube.com") ||
        value == "youtube-nocookie.com" ||
        value.endsWith(".youtube-nocookie.com")
}

private fun isAudioContentType(contentType: String?): Boolean {
    val value = contentType ?: return false
    return value.startsWith("audio/") ||
        value == "application/ogg" ||
        value == "application/x-ogg"
}

private fun hasAudioExtension(value: String?): Boolean {
    val name = value ?: return false
    val extension = name.substringAfterLast('.', "").lowercase().substringBefore('?')
    return extension in AUDIO_EXTENSIONS
}

private fun isFeedCandidate(contentType: String?, path: String): Boolean {
    if (contentType != null && contentType in setOf(
            "application/rss+xml",
            "application/atom+xml",
            "application/xml",
            "text/xml",
        )
    ) {
        return true
    }
    val lower = path.lowercase()
    return lower.endsWith(".rss") || lower.endsWith(".atom") || lower.endsWith(".xml")
}

private fun streamFor(connection: HttpURLConnection): InputStream {
    val stream = connection.inputStream
    return if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
        GZIPInputStream(stream)
    } else {
        stream
    }
}

private fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > limit) throw FeedTooLargeException()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private suspend fun copyDownload(
    input: InputStream,
    output: OutputStream,
    total: Long?,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
) {
    val buffer = ByteArray(COPY_BUFFER)
    var downloaded = 0L
    var lastReported = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        downloaded += read
        if (downloaded > MAX_DOWNLOAD_BYTES) throw DownloadTooLargeException()
        output.write(buffer, 0, read)
        if (downloaded - lastReported >= PROGRESS_STEP || (total != null && downloaded >= total)) {
            onProgress(downloaded, total)
            lastReported = downloaded
        }
    }
    onProgress(downloaded, total ?: downloaded)
}

private suspend fun chunkedDownload(
    url: URL,
    part: File,
    total: Long,
    userAgent: String,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
) {
    val nextChunk = AtomicLong(0L)
    val downloaded = AtomicLong(0L)
    val reported = AtomicLong(0L)

    RandomAccessFile(part, "rw").use { file ->
        file.setLength(total)
        file.channel.use { channel ->
            coroutineScope {
                repeat(DOWNLOAD_WORKERS) {
                    async {
                        val buffer = ByteArray(COPY_BUFFER)
                        val slice = ByteBuffer.allocateDirect(COPY_BUFFER)
                        while (true) {
                            val index = nextChunk.getAndIncrement()
                            val start = index * CHUNK_BYTES
                            if (start >= total) break
                            val end = minOf(start + CHUNK_BYTES, total) - 1
                            downloadChunk(
                                url = url,
                                channel = channel,
                                start = start,
                                end = end,
                                total = total,
                                userAgent = userAgent,
                                buffer = buffer,
                                slice = slice,
                                downloaded = downloaded,
                                reported = reported,
                                onProgress = onProgress,
                            )
                        }
                    }
                }
            }
        }
    }
    reportProgress(total, total, reported, onProgress, force = true)
}

private suspend fun downloadChunk(
    url: URL,
    channel: FileChannel,
    start: Long,
    end: Long,
    total: Long,
    userAgent: String,
    buffer: ByteArray,
    slice: ByteBuffer,
    downloaded: AtomicLong,
    reported: AtomicLong,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
) {
    var position = start
    var lastError: Exception? = null
    repeat(CHUNK_ATTEMPTS) {
        if (position > end) return
        val connection = try {
            openChunk(url, userAgent, position, end)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            null
        }
        if (connection != null) {
            try {
                streamFor(connection).use { input ->
                    while (position <= end) {
                        currentCoroutineContext().ensureActive()
                        val limit = minOf(buffer.size.toLong(), end - position + 1).toInt()
                        val read = input.read(buffer, 0, limit)
                        if (read < 0) break
                        slice.clear()
                        slice.put(buffer, 0, read)
                        slice.flip()
                        var writeAt = position
                        while (slice.hasRemaining()) {
                            writeAt += channel.write(slice, writeAt)
                        }
                        position = writeAt
                        val now = downloaded.addAndGet(read.toLong())
                        if (now > MAX_DOWNLOAD_BYTES) throw DownloadTooLargeException()
                        reportProgress(now, total, reported, onProgress)
                    }
                }
                if (position <= end) throw IOException("Incomplete chunk")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            } finally {
                connection.disconnect()
            }
        }
    }
    if (position <= end) throw lastError ?: IOException("Chunk failed")
}

private fun openChunk(url: URL, userAgent: String, start: Long, end: Long): HttpURLConnection {
    val (connection, _) = openFollowingRedirects(
        url,
        userAgent,
        range = "bytes=$start-$end",
    )
    val code = runCatching { connection.responseCode }.getOrElse { error ->
        connection.disconnect()
        throw error
    }
    if (code != 206) {
        connection.disconnect()
        throw IOException("HTTP $code")
    }
    if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
        connection.disconnect()
        throw IOException("Chunked encoding")
    }
    val rangeStart = "bytes $start-"
    val contentRange = connection.getHeaderField("Content-Range")
    if (contentRange == null || !contentRange.startsWith(rangeStart)) {
        connection.disconnect()
        throw IOException("Unexpected range")
    }
    return connection
}

private fun reportProgress(
    now: Long,
    total: Long,
    reported: AtomicLong,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
    force: Boolean = false,
) {
    val last = reported.get()
    if (force) {
        reported.set(now)
        onProgress(now, total)
        return
    }
    if (now - last < PROGRESS_STEP) return
    if (reported.compareAndSet(last, now)) onProgress(now, total)
}

private suspend fun copyLimited(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(COPY_BUFFER)
    var written = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        written += read
        if (written > MAX_DOWNLOAD_BYTES) throw DownloadTooLargeException()
        output.write(buffer, 0, read)
    }
}

private fun titleForAudio(context: Context, url: URL, disposition: String?): String {
    val raw = fileNameFromDisposition(disposition)
        ?.takeIf { it.isNotBlank() }
        ?: url.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val decoded = raw?.let { value ->
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
    val title = decoded?.let { stripExtension(it) }?.trim().orEmpty()
    return title.ifBlank { context.getString(R.string.med_imported_track) }
}

private fun fileNameFromDisposition(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val extended = Regex("(?i)filename\\*\\s*=\\s*([^;]+)")
        .find(header)
        ?.groupValues
        ?.get(1)
        ?.trim()
    if (!extended.isNullOrEmpty()) {
        val value = extended.trim('"')
        val parts = value.split('\'', limit = 3)
        val encoded = if (parts.size == 3) parts[2] else value
        val charset = if (parts.size == 3) parts[0].ifBlank { "UTF-8" } else "UTF-8"
        val decoded = runCatching { URLDecoder.decode(encoded, charset) }.getOrNull()
        if (!decoded.isNullOrBlank()) return decoded
    }
    val quoted = Regex("(?i)filename\\s*=\\s*\"([^\"]*)\"")
        .find(header)
        ?.groupValues
        ?.get(1)
    if (!quoted.isNullOrBlank()) return quoted
    return Regex("(?i)filename\\s*=\\s*([^;]+)")
        .find(header)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }
}

private fun stripExtension(value: String): String {
    val dot = value.lastIndexOf('.')
    return if (dot > 0) value.substring(0, dot) else value
}

private fun extensionFor(contentType: String?, path: String?, fileName: String?): String? {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    val byType = when {
        type == null -> null
        type == "audio/mpeg" || type == "audio/mp3" -> "mp3"
        type == "audio/mp4" || type == "audio/x-m4a" || type == "audio/m4a" -> "m4a"
        type == "audio/aac" -> "aac"
        type == "audio/ogg" || type == "application/ogg" || type == "application/x-ogg" -> "ogg"
        type == "audio/opus" -> "opus"
        type == "audio/wav" || type == "audio/x-wav" || type == "audio/wave" -> "wav"
        type == "audio/flac" || type == "audio/x-flac" -> "flac"
        type.startsWith("audio/") -> type.substringAfter('/').substringBefore('+')
        else -> null
    }
    if (byType != null && byType in AUDIO_EXTENSIONS) return byType

    val candidate = fileName ?: path?.substringAfterLast('/')
    val extension = candidate?.substringAfterLast('.', "")?.lowercase()
    return extension?.takeIf { it in AUDIO_EXTENSIONS }
}

private fun sanitizeFileName(value: String): String {
    val cleaned = value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(60)
    return cleaned.ifBlank { "audio" }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
}.getOrNull()

private class ParsedFeed(val title: String, val episodes: List<FeedEpisode>)

private class EpisodeBuilder {
    var title: String? = null
    var durationMs: Long? = null
    val enclosures = mutableListOf<Enclosure>()
}

private data class Enclosure(val url: String?, val type: String?, val length: String?)

private enum class TextTarget { NONE, FEED_TITLE, EPISODE_TITLE, DURATION }

private fun parseFeed(context: Context, base: URL, bytes: ByteArray): ParsedFeed? {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(ByteArrayInputStream(bytes), null)

    var rootName: String? = null
    var feedTitle: String? = null
    var inEpisode = false
    var inImage = false
    var textTarget = TextTarget.NONE
    val text = StringBuilder()
    var episode: EpisodeBuilder? = null
    val episodes = mutableListOf<FeedEpisode>()

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val name = parser.name
                if (rootName == null) rootName = name
                when (name) {
                    "item", "entry" -> {
                        inEpisode = true
                        episode = EpisodeBuilder()
                        textTarget = TextTarget.NONE
                        text.clear()
                    }
                    "image" -> if (!inEpisode) inImage = true
                    "title" -> if (!inImage) {
                        textTarget = when {
                            inEpisode -> TextTarget.EPISODE_TITLE
                            feedTitle.isNullOrBlank() -> TextTarget.FEED_TITLE
                            else -> TextTarget.NONE
                        }
                        text.clear()
                    }
                    "enclosure" -> episode?.enclosures?.add(
                        Enclosure(
                            url = parser.getAttributeValue(null, "url"),
                            type = parser.getAttributeValue(null, "type"),
                            length = parser.getAttributeValue(null, "length"),
                        ),
                    )
                    "link" -> episode?.let { builder ->
                        val rel = parser.getAttributeValue(null, "rel")
                        if (rel.equals("enclosure", ignoreCase = true)) {
                            builder.enclosures += Enclosure(
                                url = parser.getAttributeValue(null, "href"),
                                type = parser.getAttributeValue(null, "type"),
                                length = parser.getAttributeValue(null, "length"),
                            )
                        }
                    }
                    "duration" -> if (inEpisode) {
                        textTarget = TextTarget.DURATION
                        text.clear()
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (textTarget != TextTarget.NONE) text.append(parser.text)
            }
            XmlPullParser.END_TAG -> {
                when (parser.name) {
                    "title" -> {
                        val value = text.toString().trim()
                        when (textTarget) {
                            TextTarget.FEED_TITLE -> if (feedTitle.isNullOrBlank() && value.isNotBlank()) {
                                feedTitle = value
                            }
                            TextTarget.EPISODE_TITLE -> episode?.title = value
                            else -> Unit
                        }
                        textTarget = TextTarget.NONE
                        text.clear()
                    }
                    "duration" -> {
                        if (textTarget == TextTarget.DURATION) {
                            episode?.durationMs = parseDuration(text.toString())
                        }
                        textTarget = TextTarget.NONE
                        text.clear()
                    }
                    "item", "entry" -> {
                        val builder = episode
                        if (builder != null && episodes.size < MAX_EPISODES) {
                            val enclosure = builder.enclosures.firstOrNull { candidate ->
                                isAudioEnclosure(candidate.url, candidate.type)
                            }
                            if (enclosure != null) {
                                val resolved = enclosure.url?.let { resolveHttpUrl(base, it) }
                                val host = resolved?.let { runCatching { URL(it).host }.getOrNull() }
                                if (resolved != null && !isYouTubeHost(host)) {
                                    episodes += FeedEpisode(
                                        title = builder.title?.trim()?.takeIf { it.isNotBlank() }
                                            ?: context.getString(
                                                R.string.med_link_episode,
                                                episodes.size + 1,
                                            ),
                                        url = resolved,
                                        durationMs = builder.durationMs,
                                        sizeBytes = enclosure.length
                                            ?.toLongOrNull()
                                            ?.takeIf { it > 0L },
                                    )
                                }
                            }
                        }
                        inEpisode = false
                        episode = null
                        textTarget = TextTarget.NONE
                        text.clear()
                    }
                    "image" -> inImage = false
                }
            }
        }
        event = parser.next()
    }

    val root = rootName?.lowercase()
    if (root != "rss" && root != "feed") return null
    return ParsedFeed(
        title = feedTitle?.takeIf { it.isNotBlank() } ?: base.host ?: base.toString(),
        episodes = episodes,
    )
}

private fun isAudioEnclosure(url: String?, type: String?): Boolean {
    val audioUrl = url?.trim().orEmpty()
    if (audioUrl.isEmpty()) return false
    val cleanType = type?.substringBefore(';')?.trim()?.lowercase()
    if (cleanType != null && (cleanType.startsWith("audio/") || cleanType == "application/ogg")) {
        return true
    }
    if (cleanType != null && cleanType.startsWith("video/")) return false
    return hasAudioExtension(audioUrl)
}

private fun resolveHttpUrl(base: URL, raw: String): String? {
    val resolved = runCatching { URL(base, raw.trim()) }.getOrNull() ?: return null
    if (resolved.protocol.lowercase() !in setOf("http", "https")) return null
    return resolved.toString()
}

private fun parseDuration(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split(':')
    val seconds = if (parts.size == 1) {
        text.replace(',', '.').toDoubleOrNull() ?: return null
    } else {
        val numbers = parts.map { part ->
            part.trim().replace(',', '.').toDoubleOrNull() ?: return null
        }
        when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            else -> return null
        }
    }
    if (seconds < 0) return null
    return (seconds * 1000.0).toLong()
}
