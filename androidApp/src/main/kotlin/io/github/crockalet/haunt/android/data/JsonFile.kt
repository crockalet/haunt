package io.github.crockalet.haunt.android.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A value persisted as one small JSON file, written atomically (temp file + rename). A missing file
 * reads as [empty]; an unreadable one is kept aside as `<name>.corrupt-<time>` and reads as [empty].
 * Not thread-safe on its own: callers serialise access.
 */
internal class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val empty: () -> T,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun read(): T {
        if (!file.exists()) return empty()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".corrupt-" + clock()))
            empty()
        }
    }

    /** @throws IOException if the file can't be written. */
    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write $file")
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
