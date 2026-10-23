package app.respira.feature.meditation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.respira.R
import app.respira.audio.AudioTrack
import app.respira.audio.TrackSource
import app.respira.ui.theme.RespiraColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val ORIGIN_DEVICE = "device"
internal const val ORIGIN_LINK = "link"
internal const val ORIGIN_SHARED = "shared"

internal data class LibraryTrack(
    val id: String,
    val title: String,
    val uri: String,
    val origin: String? = null,
)

internal fun LibraryTrack.toMeditation(context: Context): MeditationTrack {
    val subtitle = when (origin) {
        ORIGIN_LINK -> R.string.med_from_link
        ORIGIN_SHARED -> R.string.med_shared
        else -> R.string.med_from_device
    }
    return MeditationTrack(
        track = AudioTrack(
            id = "local:$id",
            title = title,
            subtitle = context.getString(subtitle),
            source = TrackSource.Local(uri),
        ),
        icon = R.drawable.ic_list_music,
        accent = RespiraColors.Indigo,
        accentAlt = RespiraColors.Violet,
    )
}

internal data class LibraryImport(
    val tracks: List<LibraryTrack>,
    val failed: Int,
)

internal object MeditationLibrary {
    private const val Preferences = "respira_library"
    private const val TracksKey = "tracks"

    fun libraryDir(context: Context): File = File(context.filesDir, "library")

    fun load(context: Context): List<LibraryTrack> {
        val raw = preferences(context).getString(TracksKey, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList<LibraryTrack> {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.med_imported_track)
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                val origin = item.optString("origin").takeIf { it.isNotBlank() }
                add(LibraryTrack(id = id, title = title, uri = uri, origin = origin))
            }
        }
    }

    @Synchronized
    fun import(context: Context, uris: List<Uri>, existing: List<LibraryTrack>): LibraryImport {
        val knownUris = existing.mapTo(mutableSetOf<String>()) { it.uri }
        val added = mutableListOf<LibraryTrack>()
        var failed = 0
        uris.forEach { uri ->
            val value = uri.toString()
            if (!knownUris.add(value)) return@forEach
            if (!takePermission(context, uri)) {
                failed += 1
                return@forEach
            }
            added += LibraryTrack(
                id = UUID.randomUUID().toString(),
                title = displayTitle(context, uri),
                uri = value,
                origin = ORIGIN_DEVICE,
            )
        }
        if (added.isEmpty()) return LibraryImport(existing, failed = failed)
        val tracks = existing + added
        save(context, tracks)
        return LibraryImport(tracks, failed = failed)
    }

    @Synchronized
    fun addTrack(context: Context, title: String, uri: String, origin: String): LibraryTrack {
        val track = LibraryTrack(
            id = UUID.randomUUID().toString(),
            title = title,
            uri = uri,
            origin = origin,
        )
        save(context, load(context) + track)
        return track
    }

    @Synchronized
    fun remove(context: Context, tracks: List<LibraryTrack>, target: LibraryTrack): List<LibraryTrack> {
        val parsed = runCatching { Uri.parse(target.uri) }.getOrNull()
        if (parsed != null) {
            when (parsed.scheme?.lowercase()) {
                "file" -> deletePrivateFile(context, parsed)
                "content" -> runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        parsed,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        val remaining = tracks.filterNot { it.id == target.id }
        save(context, remaining)
        return remaining
    }

    fun canRead(context: Context, uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        if (parsed.scheme == "file") {
            val path = parsed.path ?: return false
            return runCatching { File(path).canRead() }.getOrDefault(false)
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { true } ?: false
        } catch (error: SecurityException) {
            false
        } catch (error: java.io.IOException) {
            false
        } catch (error: IllegalArgumentException) {
            false
        }
    }

    private fun deletePrivateFile(context: Context, uri: Uri) {
        val path = uri.path ?: return
        val directory = runCatching { libraryDir(context).canonicalFile }.getOrNull() ?: return
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (file.parentFile == directory || file.path.startsWith(directory.path + File.separator)) {
            runCatching { file.delete() }
        }
    }

    private fun takePermission(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (error: SecurityException) {
        false
    } catch (error: IllegalArgumentException) {
        false
    }

    private fun displayTitle(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
        }.getOrNull()
        return name
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.med_imported_track)
    }

    private fun save(context: Context, tracks: List<LibraryTrack>) {
        val array = JSONArray()
        tracks.forEach { track ->
            val item = JSONObject()
                .put("id", track.id)
                .put("title", track.title)
                .put("uri", track.uri)
            track.origin?.let { item.put("origin", it) }
            array.put(item)
        }
        preferences(context).edit().putString(TracksKey, array.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
}
