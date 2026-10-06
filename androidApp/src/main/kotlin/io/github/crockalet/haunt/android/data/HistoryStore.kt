package io.github.crockalet.haunt.android.data

import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** A place Haunt faked, for Library → History and Search → Recent. */
@Serializable
data class HistoryEntry(val name: String, val position: LatLng, val timeMillis: Long)

/**
 * Recently haunted places, newest first, persisted as a small JSON file. Re-haunting a spot within
 * [MERGE_RADIUS_METERS] of an entry moves it to the top instead of adding a duplicate. Keeps at most
 * [capacity] entries. Thread-safe; does file I/O, so call it off the main thread.
 */
class HistoryStore(
    file: File,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class FileContent(val version: Int = 1, val entries: List<HistoryEntry> = emptyList())

    private val lock = Any()
    private val store = JsonFile(file, FileContent.serializer(), ::FileContent, clock)
    private val _entries = MutableStateFlow(store.read().entries.take(capacity))

    /** Newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    /** Records [position] (named [name]) as just haunted. @throws java.io.IOException */
    fun record(name: String, position: LatLng): HistoryEntry = synchronized(lock) {
        val entry = HistoryEntry(name.trim().ifEmpty { "Dropped pin" }, position, clock())
        val rest = _entries.value.filterNot { Geo.distanceMeters(it.position, position) < MERGE_RADIUS_METERS }
        val next = (listOf(entry) + rest).take(capacity)
        store.write(FileContent(entries = next))
        _entries.value = next
        entry
    }

    fun clear() = synchronized(lock) {
        store.write(FileContent())
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
        const val MERGE_RADIUS_METERS = 25.0
    }
}
