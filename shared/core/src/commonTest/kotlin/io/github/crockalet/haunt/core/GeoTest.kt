package io.github.crockalet.haunt.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeoTest {
    private val tokyoTower = LatLng(35.65858, 139.74543)
    private val shibuya = LatLng(35.65803, 139.70164)
    private val landsEnd = LatLng(50.0664, -5.7147)
    private val johnOGroats = LatLng(58.6439, -3.0700)

    @Test
    fun oneDegreeOfLatitude() {
        val expected = Geo.EARTH_RADIUS_METERS * PI / 180.0
        assertEquals(expected, Geo.distanceMeters(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 1e-6)
        assertEquals(111_195.08, expected, 0.01)
    }

    @Test
    fun tokyoTowerToShibuya() {
        val d = Geo.distanceMeters(tokyoTower, shibuya)
        // Equirectangular approximation is excellent at this scale.
        val meanLat = (tokyoTower.lat + shibuya.lat) / 2 * PI / 180
        val x = (shibuya.lng - tokyoTower.lng) * PI / 180 * cos(meanLat)
        val y = (shibuya.lat - tokyoTower.lat) * PI / 180
        val approx = kotlin.math.sqrt(x * x + y * y) * Geo.EARTH_RADIUS_METERS
        assertEquals(approx, d, 0.5)
        assertEquals(3_963.0, d, 15.0)
        assertEquals(d, Geo.distanceMeters(shibuya, tokyoTower), 1e-9)
    }

    @Test
    fun landsEndToJohnOGroats() {
        // Reference values from the haversine on R = 6371 km (movable-type.co.uk), scaled.
        assertEquals(968_853.0, Geo.distanceMeters(landsEnd, johnOGroats), 200.0)
        assertEquals(9.11972, Geo.initialBearingDeg(landsEnd, johnOGroats), 1e-3)
    }

    @Test
    fun bearingsCardinal() {
        val o = LatLng(0.0, 0.0)
        assertEquals(0.0, Geo.initialBearingDeg(o, LatLng(1.0, 0.0)), 1e-9)
        assertEquals(90.0, Geo.initialBearingDeg(o, LatLng(0.0, 1.0)), 1e-9)
        assertEquals(180.0, Geo.initialBearingDeg(o, LatLng(-1.0, 0.0)), 1e-9)
        assertEquals(270.0, Geo.initialBearingDeg(o, LatLng(0.0, -1.0)), 1e-9)
        assertEquals(0.0, Geo.initialBearingDeg(o, o))
        // Across the antimeridian, eastwards.
        assertEquals(90.0, Geo.initialBearingDeg(LatLng(0.0, 179.5), LatLng(0.0, -179.5)), 1e-9)
    }

    @Test
    fun destinationKnownValue() {
        // movable-type.co.uk example: 53°19′14″N 001°43′47″W, 096°01′18″, 124.8 km → 53°11′18″N 000°08′00″E
        val start = LatLng(53.32056, -1.72972)
        val dest = Geo.destination(start, 96.0217, 124_800.0)
        assertEquals(53.18826, dest.lat, 2e-4)
        assertEquals(0.13328, dest.lng, 2e-4)
    }

    @Test
    fun destinationRoundTrip() {
        for (bearing in listOf(0.0, 33.0, 90.0, 181.0, 270.0, 359.0)) {
            val d = Geo.destination(tokyoTower, bearing, 1234.5)
            assertEquals(1234.5, Geo.distanceMeters(tokyoTower, d), 1e-3)
            assertEquals(bearing, Geo.initialBearingDeg(tokyoTower, d), 1e-3)
        }
        assertEquals(tokyoTower, Geo.destination(tokyoTower, 45.0, 0.0))
    }

    @Test
    fun destinationWrapsLongitude() {
        val d = Geo.destination(LatLng(0.0, 179.99), 90.0, 10_000.0)
        assertTrue(d.lng < -179.0, "lng was ${d.lng}")
    }

    @Test
    fun normalizeBearing() {
        assertEquals(0.0, Geo.normalizeBearing(360.0))
        assertEquals(350.0, Geo.normalizeBearing(-10.0))
        assertEquals(10.0, Geo.normalizeBearing(730.0))
        assertEquals(0.0, Geo.normalizeBearing(-720.0))
        assertEquals(-170.0, Geo.normalizeLongitude(190.0), 1e-9)
        assertEquals(-180.0, Geo.normalizeLongitude(180.0), 1e-9)
    }

    @Test
    fun interpolateMidpoint() {
        val mid = Geo.interpolate(LatLng(0.0, 0.0), LatLng(0.0, 10.0), 0.5)
        assertEquals(0.0, mid.lat, 1e-9)
        assertEquals(5.0, mid.lng, 1e-9)
        val a = tokyoTower
        val b = shibuya
        val q = Geo.interpolate(a, b, 0.25)
        assertEquals(Geo.distanceMeters(a, b) * 0.25, Geo.distanceMeters(a, q), 1e-3)
    }

    @Test
    fun polylineLength() {
        val pts = listOf(LatLng(0.0, 0.0), LatLng(1.0, 0.0), LatLng(1.0, 0.0), LatLng(2.0, 0.0))
        assertEquals(2 * Geo.EARTH_RADIUS_METERS * PI / 180, Geo.polylineLength(pts), 1e-6)
        assertEquals(0.0, Geo.polylineLength(listOf(LatLng(1.0, 1.0))))
        assertEquals(0.0, Geo.polylineLength(emptyList()))
    }

    @Test
    fun polylinePositionAt() {
        val degree = Geo.EARTH_RADIUS_METERS * PI / 180
        // North 1°, then east 1° (along the equator-ish at lat 1).
        val line = Polyline(listOf(LatLng(0.0, 0.0), LatLng(1.0, 0.0), LatLng(1.0, 0.0), LatLng(1.0, 1.0)))
        assertEquals(4, line.cumulativeMeters.size)

        val start = line.positionAt(0.0)
        assertEquals(LatLng(0.0, 0.0), start.position)
        assertEquals(0.0, start.bearingDeg, 1e-9)
        assertEquals(0, start.segmentIndex)

        val half = line.positionAt(degree / 2)
        assertEquals(0.5, half.position.lat, 1e-9)
        assertEquals(0.0, half.bearingDeg, 1e-6)

        val corner = line.positionAt(degree)
        assertEquals(2, corner.segmentIndex) // zero-length segment 1 skipped
        assertEquals(1.0, corner.position.lat, 1e-9)
        assertTrue(abs(corner.bearingDeg - 90.0) < 0.1)

        val end = line.positionAt(line.lengthMeters + 100)
        assertEquals(LatLng(1.0, 1.0), end.position)
        assertEquals(line.lengthMeters, end.distanceMeters)
        assertEquals(2, end.segmentIndex)
        assertTrue(abs(end.bearingDeg - 90.0) < 0.1)

        assertEquals(LatLng(0.0, 0.0), line.positionAt(-5.0).position)
    }

    @Test
    fun polylineBinarySearchManySegments() {
        val pts = (0..1000).map { LatLng(it * 0.001, 0.0) }
        val line = Polyline(pts)
        val step = Geo.distanceMeters(pts[0], pts[1])
        for (i in listOf(0, 1, 499, 999)) {
            val p = line.positionAt(step * i + step / 2)
            assertEquals(i, p.segmentIndex)
            assertEquals(i * 0.001 + 0.0005, p.position.lat, 1e-9)
        }
    }

    @Test
    fun singlePointPolyline() {
        val line = Polyline(listOf(tokyoTower))
        assertEquals(0.0, line.lengthMeters)
        assertEquals(tokyoTower, line.positionAt(10.0).position)
        assertFailsWith<IllegalArgumentException> { Polyline(emptyList()) }
    }
}
