package io.github.crockalet.haunt.android.data

import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.protocol.Favorite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Favourite places, persisted as a small JSON file (`{"version":1,"favorites":[…]}`).
 *
 * All operations are synchronous and thread-safe; they do file I/O, so call them off the main
 * thread (they're cheap: the file holds at most a few hundred entries). Names are unique
 * (case-insensitive): saving an existing name overwrites it, keeping its id.
 */
class FavoritesStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    @Serializable
    private data class FileContent(val version: Int = 1, val favorites: List<Favorite> = emptyList())

    private val lock = Any()
    private val _favorites = MutableStateFlow(load())

    /** All favourites in insertion order. */
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    fun list(): List<Favorite> = _favorites.value

    fun findByName(name: String): Favorite? = _favorites.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun findById(id: String): Favorite? = _favorites.value.firstOrNull { it.id == id }

    /** Saves or overwrites (same name) a favourite. @throws IOException if the file can't be written. */
    fun save(name: String, position: LatLng, folder: String? = null, color: String? = null): Favorite = synchronized(lock) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "name must not be blank" }
        val current = _favorites.value
        val existing = current.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val favorite = Favorite(
            id = existing?.id ?: newId(),
            name = trimmed,
            position = position,
            folder = folder ?: existing?.folder,
            color = color ?: existing?.color,
            createdMillis = existing?.createdMillis ?: clock(),
        )
        val next = if (existing != null) current.map { if (it.id == existing.id) favorite else it } else current + favorite
        write(next)
        favorite
    }

    /** Deletes by [id] or (if [id] is null) by [name]; returns the removed favourite or null if none matched. */
    fun delete(id: String? = null, name: String? = null): Favorite? = synchronized(lock) {
        val current = _favorites.value
        val target = when {
            id != null -> current.firstOrNull { it.id == id }
            name != null -> current.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            else -> null
        } ?: return null
        write(current.filterNot { it.id == target.id })
        target
    }

    private fun write(favorites: List<Favorite>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(FileContent.serializer(), FileContent(favorites = favorites)))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write $file")
        }
        _favorites.value = favorites
    }

    private fun load(): List<Favorite> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(FileContent.serializer(), file.readText()).favorites
        } catch (e: Exception) {
            // Keep the unreadable file for inspection instead of silently overwriting it later.
            file.renameTo(File(file.parentFile, file.name + ".corrupt-" + clock()))
            emptyList()
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = false
        }
    }
}
