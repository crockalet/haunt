package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.data.FavoritesStore
import io.github.crockalet.haunt.core.LatLng
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoritesStoreTest {
    private val dir: File = Files.createTempDirectory("haunt-fav").toFile()
    private val file = File(dir, "favorites.json")
    private var ids = 0
    private fun store() = FavoritesStore(file, clock = { 1000L }, newId = { "id${++ids}" })

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun saveListDeleteAndReload() {
        val s = store()
        val tower = s.save("Tokyo Tower", LatLng(35.6586, 139.7454), folder = "Tokyo")
        s.save("Shibuya", LatLng(35.6595, 139.7006), color = "#FF0000")
        assertEquals(listOf("Tokyo Tower", "Shibuya"), s.list().map { it.name })
        assertEquals("id1", tower.id)
        assertEquals(1000L, tower.createdMillis)

        val reloaded = store()
        assertEquals(s.list(), reloaded.list())

        assertEquals("id2", reloaded.delete(name = "shibuya")?.id)
        assertNull(reloaded.delete(id = "nope"))
        assertEquals(listOf("Tokyo Tower"), store().list().map { it.name })
    }

    @Test
    fun sameNameOverwritesKeepingIdAndExtras() {
        val s = store()
        s.save("Home", LatLng(1.0, 2.0), folder = "Places", color = "#00FF00")
        val updated = s.save(" home ", LatLng(3.0, 4.0))
        assertEquals(1, s.list().size)
        assertEquals("id1", updated.id)
        assertEquals(LatLng(3.0, 4.0), updated.position)
        assertEquals("Places", updated.folder)
        assertEquals("#00FF00", updated.color)
        assertNotNull(s.findByName("HOME"))
        assertEquals(s.favorites.value, s.list())
    }

    @Test
    fun corruptFileIsSetAsideNotFatal() {
        file.writeText("{not json")
        val s = store()
        assertTrue(s.list().isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("favorites.json.corrupt-") })
        s.save("A", LatLng(0.0, 0.0))
        assertEquals(1, store().list().size)
    }
}
