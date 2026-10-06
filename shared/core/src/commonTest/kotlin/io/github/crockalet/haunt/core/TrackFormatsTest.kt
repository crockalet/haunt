package io.github.crockalet.haunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackFormatsTest {

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8" standalone="no" ?>
        <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="Test"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <metadata><name>Morning &amp; walk</name></metadata>
          <wpt lat="35.6586" lon="139.7454"><name>Tokyo Tower</name></wpt>
          <wpt lat="35.6595" lon="139.7005"><time>2024-05-01T00:00:00Z</time></wpt>
          <trk>
            <name><![CDATA[Shiba Park loop]]></name>
            <trkseg>
              <trkpt lat="35.6586" lon="139.7454"><ele>20.5</ele><time>2024-05-01T09:00:00+09:00</time></trkpt>
              <trkpt lat="35.6590" lon="139.7460"><ele>21</ele><time>2024-05-01T00:00:10Z</time></trkpt>
            </trkseg>
            <!-- second segment -->
            <trkseg>
              <trkpt lat="35.6600" lon="139.7470"><ele>22</ele><time>2024-05-01T00:00:20.500Z</time></trkpt>
              <trkpt lat="bogus" lon="139.7470"><ele>22</ele></trkpt>
            </trkseg>
          </trk>
          <rte>
            <name>Planned</name>
            <rtept lat="35.0" lon="139.0"/>
            <rtept lat="35.1" lon="139.1"><ele>5</ele></rtept>
          </rte>
        </gpx>
    """.trimIndent()

    @Test
    fun parsesGpxTracksRoutesAndWaypoints() {
        val routes = TrackFormats.parse(gpx)
        assertEquals(3, routes.size)

        val trk = routes[0]
        assertEquals("Shiba Park loop", trk.name)
        assertEquals(listOf(LatLng(35.6586, 139.7454), LatLng(35.6590, 139.7460), LatLng(35.6600, 139.7470)), trk.points)
        val t0 = Iso8601.parse("2024-05-01T00:00:00Z")!!
        assertEquals(listOf(t0, t0 + 10_000, t0 + 20_500), trk.timestampsMillis)
        assertEquals(listOf(20.5, 21.0, 22.0), trk.altitudes)

        val rte = routes[1]
        assertEquals("Planned", rte.name)
        assertEquals(2, rte.points.size)
        assertNull(rte.timestampsMillis)
        assertNull(rte.altitudes) // only one point has <ele>

        val wpts = routes[2]
        assertEquals("Morning & walk", wpts.name)
        assertEquals(2, wpts.points.size)
        assertNull(wpts.timestampsMillis)
    }

    @Test
    fun gpxWithPrefixesAndDecreasingTimes() {
        val doc = """
            <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk><g:trkseg>
              <g:trkpt lat='1' lon='2'><g:time>2024-01-01T00:00:10Z</g:time></g:trkpt>
              <g:trkpt lat='1.001' lon='2'><g:time>2024-01-01T00:00:05Z</g:time></g:trkpt>
            </g:trkseg></g:trk></g:gpx>
        """.trimIndent()
        val route = TrackFormats.parseGpx(doc).single()
        assertEquals(listOf(LatLng(1.0, 2.0), LatLng(1.001, 2.0)), route.points)
        assertNull(route.timestampsMillis)
    }

    private val kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
          <Document>
            <name>Trip</name>
            <Placemark>
              <name>Tokyo Tower</name>
              <Point><coordinates>139.7454,35.6586,333</coordinates></Point>
            </Placemark>
            <Folder>
              <Placemark>
                <name>Line</name>
                <LineString>
                  <tessellate>1</tessellate>
                  <coordinates>
                    139.7454,35.6586,10 139.7460,35.6590,11
                    139.7470, 35.6600, 12
                    999,999
                  </coordinates>
                </LineString>
              </Placemark>
            </Folder>
            <Placemark>
              <name>Recorded</name>
              <gx:Track>
                <when>2024-05-01T00:00:00Z</when>
                <when>2024-05-01T00:00:05Z</when>
                <gx:coord>139.7454 35.6586 10</gx:coord>
                <gx:coord>139.7460 35.6590 12</gx:coord>
              </gx:Track>
            </Placemark>
            <Placemark>
              <name>Shibuya</name>
              <Point><coordinates>139.7005,35.6595</coordinates></Point>
            </Placemark>
          </Document>
        </kml>
    """.trimIndent()

    @Test
    fun parsesKml() {
        val routes = TrackFormats.parse(kml)
        assertEquals(3, routes.size)

        val line = routes[0]
        assertEquals("Line", line.name)
        assertEquals(listOf(LatLng(35.6586, 139.7454), LatLng(35.6590, 139.7460), LatLng(35.6600, 139.7470)), line.points)
        assertEquals(listOf(10.0, 11.0, 12.0), line.altitudes)
        assertNull(line.timestampsMillis)

        val track = routes[1]
        assertEquals("Recorded", track.name)
        val t0 = Iso8601.parse("2024-05-01T00:00:00Z")!!
        assertEquals(listOf(t0, t0 + 5000), track.timestampsMillis)
        assertEquals(listOf(10.0, 12.0), track.altitudes)
        assertEquals(LatLng(35.6590, 139.7460), track.points[1])

        val points = routes[2]
        assertEquals("Trip", points.name)
        assertEquals(listOf(LatLng(35.6586, 139.7454), LatLng(35.6595, 139.7005)), points.points)
        assertNull(points.altitudes) // Shibuya has no altitude
    }

    @Test
    fun singlePointKmlUsesPlacemarkName() {
        val doc = """<kml><Placemark><name>Pin</name><Point><coordinates>2.2945,48.8584</coordinates></Point></Placemark></kml>"""
        val route = TrackFormats.parseKml(doc).single()
        assertEquals("Pin", route.name)
        assertEquals(listOf(LatLng(48.8584, 2.2945)), route.points)
    }

    @Test
    fun rejectsOtherDocuments() {
        assertFailsWith<TrackFormatException> { TrackFormats.parse("<html><body/></html>") }
        assertFailsWith<TrackFormatException> { TrackFormats.parse("not xml at all") }
        assertFailsWith<TrackFormatException> { TrackFormats.parseGpx(kml) }
        assertFailsWith<TrackFormatException> { TrackFormats.parseKml(gpx) }
        assertTrue(TrackFormats.parse("<gpx></gpx>").isEmpty())
    }

    @Test
    fun exportsGpxThatRoundTrips() {
        val t0 = 1_714_521_600_000L
        val route = Route(
            points = listOf(LatLng(35.6586, 139.7454), LatLng(-0.00001, -139.0000001), LatLng(35.66, 139.747)),
            timestampsMillis = listOf(t0, t0 + 1500, t0 + 3000),
            name = "Fish & <chips>",
            altitudes = listOf(1.5, -2.0, 3.25),
        )
        val xml = TrackFormats.toGpx(route)
        assertTrue("lat=\"-0.00001\"" in xml, xml)
        assertTrue("Fish &amp; &lt;chips&gt;" in xml)
        assertTrue("<time>2024-05-01T00:00:01.500Z</time>" in xml, xml)
        val parsed = TrackFormats.parse(xml).single()
        assertEquals(route.name, parsed.name)
        assertEquals(route.timestampsMillis, parsed.timestampsMillis)
        assertEquals(route.altitudes, parsed.altitudes)
        route.points.zip(parsed.points).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-7)
            assertEquals(a.lng, b.lng, 1e-7)
        }

        val plain = TrackFormats.toGpx(Route(listOf(LatLng(1.0, 2.0))))
        assertTrue("<trkpt lat=\"1\" lon=\"2\"/>" in plain, plain)
        assertEquals(Route(listOf(LatLng(1.0, 2.0))), TrackFormats.parse(plain).single())
    }

    @Test
    fun decimalFormatting() {
        assertEquals("0", TrackFormats.decimal(0.0, 7))
        assertEquals("0", TrackFormats.decimal(-0.00000001, 7))
        assertEquals("-12.5", TrackFormats.decimal(-12.5, 7))
        assertEquals("139.7454329", TrackFormats.decimal(139.74543287, 7))
    }
}
