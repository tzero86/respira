package app.respira.feature.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.respira.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

internal data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val htmlUrl: String,
    val notes: String,
)

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val percent: Int?) : UpdateState
    data object Ready : UpdateState
    data object Installing : UpdateState
    data object Error : UpdateState
}

internal enum class CheckOutcome { UPDATE, UP_TO_DATE, FAILED, SKIPPED }

private const val TAG = "UpdateCenter"
private const val API_URL = "https://api.github.com/repos/tzero86/respira/releases/latest"
private const val USER_AGENT = "Respira/0.1"
private const val JSON_ACCEPT = "application/vnd.github+json"
private const val BINARY_ACCEPT = "application/octet-stream"
private const val TIMEOUT_MS = 15_000
private const val MAX_REDIRECTS = 5
private const val MAX_JSON_BYTES = 2 * 1024 * 1024
private const val MAX_APK_BYTES = 300L * 1024 * 1024
private const val PROGRESS_STEP = 256 * 1024L
private const val COPY_BUFFER = 64 * 1024
private const val PREFS = "respira_updates"
private const val KEY_LAST_CHECK = "last_check"
private const val THROTTLE_MS = 6L * 60 * 60 * 1000
private const val TARGET_PACKAGE = "app.respira"
private const val AUTHORITY_SUFFIX = ".updates"
private const val UPDATES_DIR = "updates"
private const val APK_MIME = "application/vnd.android.package-archive"

private val TAG_PATTERN = Regex("""^v(\d+)\.(\d+)\.(\d+)-alpha(\d+)$""")
private const val HEX = "0123456789abcdef"

internal object UpdateCenter {
    private val downloadLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check(context: Context, manual: Boolean = false): CheckOutcome {
        if (_state.value is UpdateState.Downloading) return CheckOutcome.SKIPPED

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0L) < THROTTLE_MS) {
            return CheckOutcome.SKIPPED
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val info = try {
            withContext(Dispatchers.IO) { fetchLatest(context) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Update check failed", error)
            if (manual && _state.value !is UpdateState.Ready && _state.value !is UpdateState.Installing) {
                _state.value = UpdateState.Error
            }
            return CheckOutcome.FAILED
        }

        if (info == null) {
            if (_state.value !is UpdateState.Ready && _state.value !is UpdateState.Installing) {
                _available.value = null
                _state.value = UpdateState.Idle
            }
            return CheckOutcome.UP_TO_DATE
        }

        val changed = _available.value?.versionCode != info.versionCode
        _available.value = info
        if (changed && _state.value !is UpdateState.Downloading) {
            _state.value = UpdateState.Idle
        } else if (_state.value is UpdateState.Error) {
            _state.value = UpdateState.Idle
        }
        return CheckOutcome.UPDATE
    }

    fun startDownload(context: Context) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            download(context.applicationContext)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) return
        _available.value = null
        if (_state.value !is UpdateState.Installing) {
            _state.value = UpdateState.Idle
        }
    }

    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val info = _available.value ?: return@withLock
            if (_state.value is UpdateState.Downloading) return@withLock

            val directory = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
            val part = File(directory, "respira-${info.versionCode}.apk.part")
            val target = File(directory, "respira-${info.versionCode}.apk")
            directory.listFiles()
                ?.filter { it.extension == "apk" || it.extension == "part" }
                ?.filter { it.name != target.name && it.name != part.name }
                ?.forEach { it.delete() }
            part.delete()

            _state.value = UpdateState.Downloading(null)
            try {
                val connection = openBinary(info.apkUrl)
                try {
                    val declared = connection.contentLengthLong.takeIf { it > 0L }
                    if (declared != null && declared > MAX_APK_BYTES) {
                        throw IOException("APK exceeds 300 MB")
                    }
                    val downloaded = connection.inputStream.use { input ->
                        FileOutputStream(part).use { output ->
                            copyWithProgress(input, output, declared) { done ->
                                val percent = declared?.let { (done * 100 / it).toInt().coerceIn(0, 100) }
                                _state.value = UpdateState.Downloading(percent)
                            }
                        }
                    }
                    if (declared != null && downloaded < declared) throw IOException("Truncated download")
                } finally {
                    connection.disconnect()
                }

                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                verify(context, target)
                _state.value = UpdateState.Ready
            } catch (error: CancellationException) {
                Log.i(TAG, "Update download cancelled")
                part.delete()
                target.delete()
                _state.value = UpdateState.Idle
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Update download failed", error)
                part.delete()
                target.delete()
                _state.value = UpdateState.Error
            }
        }
    }

    fun install(context: Context) {
        val info = _available.value ?: return
        val file = apkFile(context, info)
        if (!file.exists()) {
            _state.value = UpdateState.Error
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _state.value = UpdateState.Installing
        } catch (error: ActivityNotFoundException) {
            Log.i(TAG, "No installer available, opening release page", error)
            openBrowser(context, info)
        } catch (error: Exception) {
            Log.w(TAG, "Could not start installer", error)
            _state.value = UpdateState.Error
        }
    }

    fun openBrowser(context: Context, info: UpdateInfo? = _available.value) {
        val url = info?.htmlUrl?.takeIf { it.isNotBlank() } ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: ActivityNotFoundException) {
            Log.i(TAG, "No browser available", error)
        }
    }

    private fun apkFile(context: Context, info: UpdateInfo): File =
        File(File(context.cacheDir, UPDATES_DIR), "respira-${info.versionCode}.apk")

    private fun fetchLatest(context: Context): UpdateInfo? {
        val payload = readLimited(API_URL, JSON_ACCEPT, MAX_JSON_BYTES)
        val root = JSONObject(payload)
        val tag = root.optString("tag_name")
        val match = TAG_PATTERN.find(tag) ?: return null
        val versionCode = match.groupValues[4].toLongOrNull() ?: return null
        if (versionCode <= installedVersionCode(context)) return null

        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            if (apkUrl != null) break
        }
        val downloadUrl = apkUrl ?: return null

        return UpdateInfo(
            tag = tag,
            versionName = tag.removePrefix("v"),
            versionCode = versionCode,
            apkUrl = downloadUrl,
            htmlUrl = root.optString("html_url"),
            notes = root.optString("body").trim(),
        )
    }

    private fun readLimited(url: String, accept: String, limit: Int): String {
        val connection = open(URL(url), accept)
        try {
            val declared = connection.contentLengthLong
            if (declared > limit) throw IOException("Response exceeds limit")
            return connection.inputStream.use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                val output = ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > limit) throw IOException("Response exceeds limit")
                    output.write(buffer, 0, read)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openBinary(url: String): HttpURLConnection = open(URL(url), BINARY_ACCEPT)

    private fun open(start: URL, accept: String): HttpURLConnection {
        var current = start
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as? HttpURLConnection
                ?: throw IOException("Not an HTTP connection")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Encoding", "identity")

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without location")
                if (redirects >= MAX_REDIRECTS) throw IOException("Too many redirects")
                val next = runCatching { URL(current, location) }.getOrNull()
                    ?: throw IOException("Invalid redirect")
                if (next.protocol.lowercase() !in setOf("http", "https")) {
                    throw IOException("Invalid redirect scheme")
                }
                current = next
                redirects += 1
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection
        }
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: FileOutputStream,
        total: Long?,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var downloaded = 0L
        var reported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            downloaded += read
            if (downloaded > MAX_APK_BYTES) throw IOException("APK exceeds 300 MB")
            output.write(buffer, 0, read)
            if (downloaded - reported >= PROGRESS_STEP || (total != null && downloaded >= total)) {
                onProgress(downloaded)
                reported = downloaded
            }
        }
        return downloaded
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File) {
        val manager = context.packageManager
        val flags = signerFlags()
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Unreadable APK")
        if (archive.packageName != TARGET_PACKAGE) throw IOException("Unexpected package")

        val installedCode = installedVersionCode(context)
        if (PackageInfoCompat.getLongVersionCode(archive) <= installedCode) {
            throw IOException("APK is not newer than the installed build")
        }

        if (context.packageName != TARGET_PACKAGE) return
        if (BuildConfig.DEBUG) return

        val installedSigners: Array<Signature>?
        val archiveSigners: Array<Signature>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installedSigners = manager.getPackageInfo(context.packageName, flags)
                .signingInfo
                ?.apkContentsSigners
            archiveSigners = archive.signingInfo?.apkContentsSigners
        } else {
            installedSigners = manager.getPackageInfo(context.packageName, flags).signatures
            archiveSigners = archive.signatures
        }

        val installedCertificates = certificateDigests(installedSigners)
        val archiveCertificates = certificateDigests(archiveSigners)
        if (installedCertificates.isEmpty() || archiveCertificates.isEmpty()) {
            throw IOException("Missing signing certificate")
        }
        if (installedCertificates.intersect(archiveCertificates).isEmpty()) {
            throw IOException("Signing certificate mismatch")
        }
    }

    private fun certificateDigests(signatures: Array<Signature>?): Set<String> =
        signatures.orEmpty().mapTo(HashSet()) { signature ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
            buildString(digest.size * 2) {
                for (byte in digest) {
                    val value = byte.toInt() and 0xFF
                    append(HEX[value shr 4])
                    append(HEX[value and 0xF])
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun signerFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    private fun installedVersionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }
}
