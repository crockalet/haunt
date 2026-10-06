package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.Speed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ParsingTest {
    @Test
    fun negativeNumbersAreEscapedOnlyBeforeDoubleDash() {
        val argv = listOf("set", "-33.8568", "151.2153", "-s", "emu", "--alt", "-5", "--", "-1")
        val escaped = escapeNegativeNumbers(argv)
        assertEquals(listOf("set", "−33.8568", "151.2153", "-s", "emu", "--alt", "−5", "--", "-1"), escaped)
        assertEquals(listOf("−33.8,151.2"), escapeNegativeNumbers(listOf("-33.8,151.2")))
        assertEquals(-33.8568, parseNumber("−33.8568"))
    }

    @Test
    fun latLngFormats() {
        assertEquals(LatLng(35.6586, 139.7454), parseLatLng("35.6586,139.7454"))
        assertEquals(LatLng(35.6586, 139.7454), parseLatLng("35.6586, 139.7454"))
        assertEquals(LatLng(-33.8568, 151.2153), parseLatLng("−33.8568 151.2153"))
        assertEquals(LatLng(-33.8568, -151.2153), parseLatLng("-33.8568;-151.2153"))
        assertNull(parseLatLng("Tokyo Tower"))
        assertNull(parseLatLng("95, 10"))
        assertNull(parseLatLng("1,2,3"))
    }

    @Test
    fun targets() {
        assertEquals(Target.Coordinates(LatLng(35.6586, 139.7454)), parseTarget(listOf("35.6586", "139.7454")))
        assertEquals(Target.Coordinates(LatLng(35.6586, 139.7454)), parseTarget(listOf("35.6586,139.7454")))
        assertEquals(Target.Query("Tokyo Tower"), parseTarget(listOf("Tokyo Tower")))
        assertEquals(Target.Query("Tokyo Tower"), parseTarget(listOf("Tokyo", "Tower")))
        assertEquals(Target.Query("Route 66"), parseTarget(listOf("Route", "66")))
        assertFailsWith<IllegalArgumentException> { parseTarget(listOf("100", "10")) }
    }

    @Test
    fun speeds() {
        assertEquals(Speed.Walk, parseSpeed("walk"))
        assertEquals(Speed.Cycle, parseSpeed("Cycle"))
        assertEquals(Speed.Drive, parseSpeed("drive"))
        assertEquals(30.0, parseSpeed("30kmh").kmh, 1e-9)
        assertEquals(30.0, parseSpeed("30 km/h").kmh, 1e-9)
        assertEquals(12.0, parseSpeed("12").kmh, 1e-9)
        assertEquals(5.0, parseSpeed("5mps").metersPerSecond, 1e-9)
        assertEquals(5.0, parseSpeed("5m/s").metersPerSecond, 1e-9)
        assertEquals(8.9408, parseSpeed("20mph").metersPerSecond, 1e-9)
        assertFailsWith<IllegalArgumentException> { parseSpeed("fast") }
        assertFailsWith<IllegalArgumentException> { parseSpeed("0kmh") }
    }

    @Test
    fun rates() {
        assertEquals(2.0, parseRate("2x"))
        assertEquals(0.5, parseRate("0.5X"))
        assertEquals(3.0, parseRate("3"))
        assertFailsWith<IllegalArgumentException> { parseRate("x") }
        assertFailsWith<IllegalArgumentException> { parseRate("-2x") }
    }

    @Test
    fun formatting() {
        assertEquals("850 m", Format.distance(850.0))
        assertEquals("1.2 km", Format.distance(1234.0))
        assertEquals("45s", Format.duration(45))
        assertEquals("3m05s", Format.duration(185))
        assertEquals("2h01m", Format.duration(7260))
        assertEquals("35.658600, 139.745400", Format.latLng(TokyoTower))
    }
}
