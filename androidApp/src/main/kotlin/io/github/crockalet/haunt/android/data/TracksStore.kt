package io.github.crockalet.haunt.android.data

import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.TrackFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** An imported GPX / KML track. [format] is "GPX" or "KML". */
@Serializable
data class SavedTrack(
    val id: String,
    val name: String,
    val route: Route,
    val format: String,
    val importedMillis: Long,
)

/**
 * Imported tracks (Library → Tracks), newest first, persisted as a JSON file next to the
 * favourites. Thread-safe; does file I/O, so call it off the main thread.
 */
class TracksStore(
    file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    @Serializable
    private data class FileContent(val version: Int = 1, val tracks: List<SavedTrack> = emptyList())

    private val lock = Any()
    private val store = JsonFile(file, FileContent.serializer(), ::FileContent, clock)
    private val _tracks = MutableStateFlow(store.read().tracks)

    val tracks: StateFlow<List<SavedTrack>> = _tracks.asStateFlow()

    /** Saves [routes] (one file can hold several tracks). @throws java.io.IOException */
    fun add(routes: List<Route>, format: String, fallbackName: String): List<SavedTrack> = synchronized(lock) {
        val now = clock()
        val added = routes.mapIndexed { i, route ->
            val name = route.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (routes.size > 1) "$fallbackName (${i + 1})" else fallbackName
            SavedTrack(newId(), name, route.copy(name = name), format, now)
        }
        val next = added + _tracks.value
        store.write(FileContent(tracks = next))
        _tracks.value = next
        added
    }

    fun delete(id: String): SavedTrack? = synchronized(lock) {
        val target = _tracks.value.firstOrNull { it.id == id } ?: return null
        val next = _tracks.value - target
        store.write(FileContent(tracks = next))
        _tracks.value = next
        target
    }
}

/** Reading a GPX / KML file picked by the user. */
object TrackImport {
    data class Result(val routes: List<Route>, val format: String)

    /**
     * Parses [text] with [TrackFormats]; keeps tracks with at least two points.
     * @throws IllegalArgumentException with a user-facing message if nothing playable is in it.
     */
    fun parse(text: String): Result {
        val format = if (text.contains("<kml", ignoreCase = true)) "KML" else "GPX"
        val routes = TrackFormats.parse(text).filter { it.points.size >= 2 }
        require(routes.isNotEmpty()) { "No track with at least two points in this file" }
        return Result(routes, format)
    }

    /** "Morning run.gpx" → "Morning run". */
    fun nameFromFile(fileName: String?): String =
        fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "Imported track"
}
