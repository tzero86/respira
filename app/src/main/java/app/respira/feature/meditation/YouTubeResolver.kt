package app.respira.feature.meditation

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

internal data class YouTubeAudio(
    val streamUrl: String,
    val title: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val mimeType: String?,
    val artworkUrl: String? = null,
)

/** Which YouTube stream a link import should take. */
internal enum class YouTubeQuality { LIGHT, HIGH }

internal class YouTubeUnavailableException(status: String) :
    IOException("YouTube video unavailable: $status")

internal const val YOUTUBE_USER_AGENT =
    "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

private const val VISIONOS_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.1 Safari/605.1.15"

internal object YouTubeResolver {

    private const val ENDPOINT =
        "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val VISITOR_ENDPOINT =
        "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val VISITOR_TTL_MS = 30 * 60 * 1000L

    private val LIGHT_ITAGS = listOf(600, 599, 249, 139)
    private val HIGH_ITAGS = listOf(140, 251, 600, 599)

    @Volatile private var cachedVisitor: Pair<String, Long>? = null

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val VIDEO_PATH_PREFIXES = setOf("shorts", "embed", "live")

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")

    private class Client(
        val name: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private class Candidate(
        val itag: Int,
        val bitrate: Long,
        val url: String,
        val contentLength: Long?,
        val mimeType: String,
    )

    private val CLIENTS = listOf(
        Client(
            name = "VISIONOS",
            version = "1.02.4",
            userAgent = VISIONOS_USER_AGENT,
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice14,1",
                "osName" to "visionOS",
                "osVersion" to "2.0.0.22N198",
            ),
        ),
        Client(
            name = "ANDROID",
            version = "20.10.38",
            userAgent = YOUTUBE_USER_AGENT,
            extra = mapOf("androidSdkVersion" to 30),
        ),
        Client(
            name = "IOS",
            version = "20.10.4",
            userAgent =
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_0 like Mac OS X;)",
            extra = mapOf(
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.0.22D60",
            ),
        ),
        Client(
            name = "ANDROID_VR",
            version = "1.60.19",
            userAgent =
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12) gzip",
            extra = mapOf(
                "androidSdkVersion" to 32,
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
            ),
        ),
    )

    fun videoId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains('.') && VIDEO_ID.matches(trimmed)) return trimmed
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = runCatching { URL(candidate) }.getOrNull() ?: return null
        if (url.protocol.lowercase() !in setOf("http", "https")) return null
        val host = url.host?.lowercase() ?: return null
        if (!isYouTubeHost(host)) return null
        val segments = url.path.orEmpty().split('/').filter { it.isNotBlank() }
        val raw = when {
            host == "youtu.be" -> segments.firstOrNull()
            segments.size >= 2 && segments[0] in VIDEO_PATH_PREFIXES -> segments[1]
            else -> queryParameter(url.query, "v")
        } ?: return null
        return raw.trim().takeIf { VIDEO_ID.matches(it) }
    }

    fun resolve(videoId: String, quality: YouTubeQuality): Result<YouTubeAudio> = try {
        Result.success(resolveOrThrow(videoId, quality))
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(IOException(error.message ?: "YouTube resolve failed", error))
    }

    private fun resolveOrThrow(videoId: String, quality: YouTubeQuality): YouTubeAudio {
        val visitor = visitorData()
        var lastStatus: String? = null
        var lastError: IOException? = null
        var details: JSONObject? = null
        val candidates = ArrayList<Candidate>()
        val preferred = preferredItags(quality)
        for (client in CLIENTS) {
            val payload = try {
                player(videoId, client, visitor)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            val status = payload.optJSONObject("playabilityStatus")
                ?.optString("status")
                .orEmpty()
            if (status != "OK") {
                lastStatus = status.ifBlank { "ERROR" }
                continue
            }
            val formats = payload.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            if (formats == null) {
                lastError = IOException("Missing audio formats")
                continue
            }
            if (details == null) details = payload.optJSONObject("videoDetails")
            candidates += candidatesOf(formats)
            // Clients publish different itags, so keep asking until the best
            // stream of the tier shows up.
            if (candidates.any { it.itag == preferred.first() }) break
        }
        val chosen = selectAudio(candidates, quality)
        if (chosen == null) {
            throw lastStatus?.let { YouTubeUnavailableException(it) }
                ?: lastError
                ?: IOException("No audio stream")
        }
        return buildAudio(videoId, details, chosen)
    }

    private fun player(
        videoId: String,
        client: Client,
        visitor: String?,
    ): JSONObject {
        var current = URL(ENDPOINT)
        var body: ByteArray? = requestBody(videoId, client).toByteArray(Charsets.UTF_8)
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("User-Agent", client.userAgent)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("X-Goog-Api-Format-Version", "2")
            visitor?.let { connection.setRequestProperty("X-Goog-Visitor-Id", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }
            try {
                body?.let { connection.outputStream.use { output -> output.write(it) } }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw IOException("Redirect without location")
                    if (redirects >= MAX_REDIRECTS) throw IOException("Too many redirects")
                    val next = runCatching { URL(current, location) }.getOrNull()
                        ?: throw IOException("Invalid redirect")
                    if (next.protocol.lowercase() !in setOf("http", "https")) {
                        throw IOException("Invalid redirect scheme")
                    }
                    current = next
                    redirects += 1
                    if (code != 307 && code != 308) body = null
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return JSONObject(readCapped(streamFor(connection)))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun visitorData(): String? {
        val cached = cachedVisitor
        if (cached != null && System.currentTimeMillis() - cached.second < VISITOR_TTL_MS) {
            return cached.first
        }
        val fresh = try {
            fetchVisitorData()
        } catch (error: Exception) {
            null
        } ?: return cached?.first
        cachedVisitor = fresh to System.currentTimeMillis()
        return fresh
    }

    private fun fetchVisitorData(): String? {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20241201.00.00")))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val connection = URL(VISITOR_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", VISIONOS_USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Origin", "https://www.youtube.com")
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) return null
            val payload = JSONObject(readCapped(streamFor(connection)))
            return payload.optJSONObject("responseContext")
                ?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(videoId: String, client: Client): String {
        val context = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "es")
            .put("gl", "ES")
        for ((key, value) in client.extra) context.put(key, value)
        return JSONObject()
            .put("context", JSONObject().put("client", context))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
    }

    private fun buildAudio(
        videoId: String,
        details: JSONObject?,
        chosen: Candidate,
    ): YouTubeAudio {
        val videoDetails = details ?: JSONObject()
        val lengthSeconds = videoDetails.optString("lengthSeconds").toLongOrNull() ?: -1L
        return YouTubeAudio(
            streamUrl = chosen.url,
            title = videoDetails.optString("title").trim().ifBlank { videoId },
            sizeBytes = chosen.contentLength,
            durationMs = (lengthSeconds * 1000L).takeIf { lengthSeconds > 0L },
            mimeType = chosen.mimeType,
            artworkUrl = thumbnailUrl(videoDetails),
        )
    }

    /**
     * Largest thumbnail in the player response. The list is descending, so the
     * first entry is the richest cover the video publishes.
     */
    private fun thumbnailUrl(details: JSONObject): String? {
        val thumbnails = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: return null
        var best: String? = null
        var bestWidth = -1
        for (index in 0 until thumbnails.length()) {
            val entry = thumbnails.optJSONObject(index) ?: continue
            val url = entry.optString("url").takeIf { it.startsWith("http") } ?: continue
            val width = entry.optInt("width")
            if (width > bestWidth) {
                bestWidth = width
                best = url
            }
        }
        return best
    }

    private fun preferredItags(quality: YouTubeQuality): List<Int> = when (quality) {
        YouTubeQuality.LIGHT -> LIGHT_ITAGS
        YouTubeQuality.HIGH -> HIGH_ITAGS
    }

    private fun candidatesOf(formats: JSONArray): List<Candidate> {
        val candidates = ArrayList<Candidate>(formats.length())
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            val url = directUrl(format) ?: continue
            candidates += Candidate(
                itag = format.optInt("itag"),
                bitrate = format.optLong("bitrate"),
                url = url,
                contentLength = format.optString("contentLength").toLongOrNull()
                    ?.takeIf { it > 0L },
                mimeType = mime,
            )
        }
        return candidates
    }

    private fun selectAudio(candidates: List<Candidate>, quality: YouTubeQuality): Candidate? {
        if (candidates.isEmpty()) return null
        for (itag in preferredItags(quality)) {
            candidates.firstOrNull { it.itag == itag }?.let { return it }
        }
        val sized = candidates.filter { it.contentLength != null }
        val bySizeThenBitrate = Comparator<Candidate> { left, right ->
            val size = left.contentLength!!.compareTo(right.contentLength!!)
            if (size != 0) size else left.bitrate.compareTo(right.bitrate)
        }
        return when (quality) {
            YouTubeQuality.LIGHT ->
                sized.minWithOrNull(bySizeThenBitrate) ?: candidates.minByOrNull { it.bitrate }
            YouTubeQuality.HIGH ->
                sized.maxWithOrNull(bySizeThenBitrate) ?: candidates.maxByOrNull { it.bitrate }
        }
    }

    private fun directUrl(format: JSONObject): String? {
        format.optString("url").takeIf { it.startsWith("http") }?.let { return it }
        val cipher = format.optString("signatureCipher").ifBlank {
            format.optString("cipher")
        }
        if (cipher.isBlank()) return null
        return runCatching { JSONObject(cipher).optString("url") }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
    }

    private fun queryParameter(query: String?, name: String): String? {
        val value = query ?: return null
        return value.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun streamFor(connection: HttpURLConnection): InputStream {
        val stream = connection.inputStream
        return if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
    }

    private fun readCapped(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw IOException("YouTube response exceeds 2 MB")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString("UTF-8")
    }
}
