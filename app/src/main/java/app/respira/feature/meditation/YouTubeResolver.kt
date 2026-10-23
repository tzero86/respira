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
)

internal class YouTubeUnavailableException(status: String) :
    IOException("YouTube video unavailable: $status")

internal const val YOUTUBE_USER_AGENT =
    "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

internal object YouTubeResolver {

    private const val ENDPOINT =
        "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val PREFERRED_ITAG = 140
    private const val FALLBACK_ITAG = 251

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

    fun resolve(videoId: String): Result<YouTubeAudio> = try {
        Result.success(resolveOrThrow(videoId))
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(IOException(error.message ?: "YouTube resolve failed", error))
    }

    private fun resolveOrThrow(videoId: String): YouTubeAudio {
        var lastStatus: String? = null
        var lastError: IOException? = null
        for (client in CLIENTS) {
            val payload = try {
                player(videoId, client)
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
            try {
                return parse(payload, videoId)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastStatus?.let { YouTubeUnavailableException(it) }
            ?: lastError
            ?: IOException("YouTube player request failed")
    }

    private fun player(videoId: String, client: Client): JSONObject {
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

    private fun parse(payload: JSONObject, videoId: String): YouTubeAudio {
        val details = payload.optJSONObject("videoDetails")
            ?: throw IOException("Missing video details")
        val formats = payload.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: throw IOException("Missing audio formats")
        val chosen = selectAudio(formats) ?: throw IOException("No audio stream")
        val lengthSeconds = details.optString("lengthSeconds").toLongOrNull() ?: -1L
        return YouTubeAudio(
            streamUrl = chosen.url,
            title = details.optString("title").trim().ifBlank { videoId },
            sizeBytes = chosen.contentLength,
            durationMs = (lengthSeconds * 1000L).takeIf { lengthSeconds > 0L },
            mimeType = chosen.mimeType,
        )
    }

    private fun selectAudio(formats: JSONArray): Candidate? {
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
        return candidates.firstOrNull { it.itag == PREFERRED_ITAG }
            ?: candidates.firstOrNull { it.itag == FALLBACK_ITAG }
            ?: candidates.maxByOrNull { it.bitrate }
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
