package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.data.HistoryStore
import io.github.crockalet.haunt.android.data.TrackImport
import io.github.crockalet.haunt.android.data.TracksStore
import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.Route
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistoryStoreTest {
    private val dir: File = Files.createTempDirectory("haunt-history").toFile()
    private val file = File(dir, "history.json")
    private var now = 1_000L
    private fun store(capacity: Int = 50) = HistoryStore(file, capacity) { now }

    private val tokyo = LatLng(35.6586, 139.7454)
    private val shibuya = LatLng(35.6595, 139.7006)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun newestFirstAndPersisted() {
        val s = store()
        s.record("Tokyo Tower", tokyo)
        now = 2_000
        s.record("Shibuya", shibuya)
        assertEquals(listOf("Shibuya", "Tokyo Tower"), s.entries.value.map { it.name })
        assertEquals(listOf("Shibuya", "Tokyo Tower"), store().entries.value.map { it.name })
    }

    @Test
    fun revisitingASpotMovesItToTheTop() {
        val s = store()
        s.record("Tokyo Tower", tokyo)
        s.record("Shibuya", shibuya)
        now = 5_000
        s.record("Dropped pin", Geo.destination(tokyo, 0.0, 10.0)) // 10 m away: same spot
        assertEquals(listOf("Dropped pin", "Shibuya"), s.entries.value.map { it.name })
        assertEquals(5_000, s.entries.value.first().timeMillis)
    }

    @Test
    fun cappedAndBlankNames() {
        val s = store(capacity = 3)
        repeat(5) { i -> s.record(" ", Geo.destination(tokyo, 90.0, 1_000.0 * i)) }
        assertEquals(3, s.entries.value.size)
        assertTrue(s.entries.value.all { it.name == "Dropped pin" })
    }

    @Test
    fun corruptFileIsSetAside() {
        file.writeText("{not json")
        assertTrue(store().entries.value.isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("history.json.corrupt-") })
    }
}

class TracksStoreTest {
    private val dir: File = Files.createTempDirectory("haunt-tracks").toFile()
    private val file = File(dir, "tracks.json")
    private var ids = 0
    private fun store() = TracksStore(file, clock = { 42L }, newId = { "t${++ids}" })

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val gpx = """
        <?xml version="1.0"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>Morning run</name><trkseg>
            <trkpt lat="35.6700" lon="139.6950"><ele>40</ele><time>2026-01-01T06:00:00Z</time></trkpt>
            <trkpt lat="35.6710" lon="139.6960"><ele>41</ele><time>2026-01-01T06:01:00Z</time></trkpt>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    @Test
    fun importKeepsTimingAndSaves() {
        val parsed = TrackImport.parse(gpx)
        assertEquals("GPX", parsed.format)
        val route = parsed.routes.single()
        assertEquals(listOf(1_767_247_200_000L, 1_767_247_260_000L), route.timestampsMillis)

        val s = store()
        val saved = s.add(parsed.routes, parsed.format, TrackImport.nameFromFile("content://x/run.gpx"))
        assertEquals("Morning run", saved.single().name)
        val reloaded = store().tracks.value.single()
        assertEquals(route.timestampsMillis, reloaded.route.timestampsMillis)
        assertEquals(listOf(40.0, 41.0), reloaded.route.altitudes)
        assertEquals("t1", reloaded.id)
    }

    @Test
    fun unnamedTracksUseTheFileName() {
        val s = store()
        val a = Route(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01)))
        val names = s.add(listOf(a, a), "KML", TrackImport.nameFromFile("Delivery round.kml")).map { it.name }
        assertEquals(listOf("Delivery round (1)", "Delivery round (2)"), names)
        s.delete("t1")
        assertEquals(listOf("Delivery round (2)"), store().tracks.value.map { it.name })
    }

    @Test
    fun kmlIsDetectedAndBadFilesFail() {
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><LineString>
            <coordinates>139.69,35.67,0 139.70,35.68,0</coordinates></LineString></Placemark></kml>"""
        assertEquals("KML", TrackImport.parse(kml).format)
        assertFailsWith<IllegalArgumentException> { TrackImport.parse("<html></html>") }
        assertFailsWith<IllegalArgumentException> {
            TrackImport.parse("""<gpx><wpt lat="1" lon="2"/></gpx>""")
        }
        assertEquals("Imported track", TrackImport.nameFromFile(null))
    }
}
