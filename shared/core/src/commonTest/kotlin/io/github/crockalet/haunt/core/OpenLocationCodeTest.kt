package io.github.crockalet.haunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenLocationCodeTest {
    @Test
    fun decodeArea() {
        val area = OpenLocationCode.decode("8FVC9G8F+6X")
        assertEquals(47.3655, area.latitudeLo, 1e-9)
        assertEquals(8.524875, area.longitudeLo, 1e-9)
        assertEquals(47.365625, area.latitudeHi, 1e-9)
        assertEquals(8.525, area.longitudeHi, 1e-9)
        assertEquals(10, area.codeLength)
    }

    @Test
    fun encodeRoundTrip() {
        assertEquals("8FVC9G8F+6X", OpenLocationCode.encode(LatLng(47.3655625, 8.5249375)))
        val points = listOf(LatLng(35.6586, 139.7454), LatLng(-33.8568, 151.2153), LatLng(40.7128, -74.006), LatLng(0.0, 0.0))
        for (p in points) {
            for (len in listOf(10, 11, 12, 15)) {
                val code = OpenLocationCode.encode(p, len)
                val area = OpenLocationCode.decode(code)
                assertTrue(p.lat >= area.latitudeLo && p.lat < area.latitudeHi, "$code lat")
                assertTrue(p.lng >= area.longitudeLo && p.lng < area.longitudeHi, "$code lng")
            }
        }
        assertEquals("8FVC0000+", OpenLocationCode.encode(LatLng(47.3655625, 8.5249375), 4))
    }

    @Test
    fun validity() {
        assertTrue(OpenLocationCode.isFull("8FVC9G8F+6X"))
        assertTrue(OpenLocationCode.isShort("9G8F+6X"))
        assertFalse(OpenLocationCode.isFull("9G8F+6X"))
        assertFalse(OpenLocationCode.isValid("8FVC9G8F6X"))
        assertFalse(OpenLocationCode.isValid("8FVC9G8F+6"))
        assertFalse(OpenLocationCode.isValid("8FV0000+"))
        assertFalse(OpenLocationCode.isValid("8FVC00+"))
        assertFalse(OpenLocationCode.isValid("8FVC0000+12"))
        assertFalse(OpenLocationCode.isValid("8FVC9G8A+6X"))
        assertFalse(OpenLocationCode.isFull("ZZ000000+")) // invalid char
        assertFalse(OpenLocationCode.isFull("X2000000+")) // latitude out of range
        assertFailsWith<IllegalArgumentException> { OpenLocationCode.decode("9G8F+6X") }
    }

    @Test
    fun recoverNearest() {
        assertEquals("8FVC9G8F+6X", OpenLocationCode.recoverNearest("9G8F+6X", LatLng(47.4, 8.6)))
        // Reference across a 1° cell boundary still recovers the nearest match.
        assertEquals("8FVC9G8F+6X", OpenLocationCode.recoverNearest("9G8F+6X", LatLng(46.9, 8.1)))
        assertEquals("8FVC9G8F+6X", OpenLocationCode.recoverNearest("8FVC9G8F+6X", LatLng(0.0, 0.0)))
        val tokyo = OpenLocationCode.encode(LatLng(35.6586, 139.7454))
        assertEquals(tokyo, OpenLocationCode.recoverNearest(tokyo.substring(4), LatLng(35.7, 139.8)))
        assertEquals(tokyo, OpenLocationCode.recoverNearest(tokyo.substring(6), LatLng(35.66, 139.74)))
    }
}
