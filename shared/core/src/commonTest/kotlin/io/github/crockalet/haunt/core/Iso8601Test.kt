package io.github.crockalet.haunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Iso8601Test {
    @Test
    fun parsesUtcOffsetsAndFractions() {
        assertEquals(0L, Iso8601.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_123L, Iso8601.parse("2023-11-14T22:13:20.123Z"))
        assertEquals(1_700_000_000_120L, Iso8601.parse("2023-11-14T22:13:20.12Z"))
        assertEquals(1_700_000_000_123L, Iso8601.parse("2023-11-14T22:13:20.123456789Z"))
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-15T07:13:20+09:00"))
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-15T07:13:20+0900"))
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-15T07:13:20+09"))
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-14T17:43:20-04:30"))
        assertEquals(1_700_000_000_000L, Iso8601.parse("2023-11-14T22:13:20"))
        assertEquals(1_700_000_000_000L, Iso8601.parse(" 2023-11-14 22:13:20z "))
        assertEquals(1_699_920_000_000L, Iso8601.parse("2023-11-14"))
        assertEquals(951_782_400_000L, Iso8601.parse("2000-02-29T00:00:00Z"))
        assertEquals(-86_400_000L, Iso8601.parse("1969-12-31T00:00:00Z"))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(Iso8601.parse(""))
        assertNull(Iso8601.parse("yesterday"))
        assertNull(Iso8601.parse("2023-13-01T00:00:00Z"))
        assertNull(Iso8601.parse("2023-02-29T00:00:00Z"))
        assertNull(Iso8601.parse("2023-01-01T25:00:00Z"))
        assertNull(Iso8601.parse("2023-01-01T00:61:00Z"))
    }

    @Test
    fun formatsAndRoundTrips() {
        assertEquals("2023-11-14T22:13:20Z", Iso8601.format(1_700_000_000_000L))
        assertEquals("2023-11-14T22:13:20.005Z", Iso8601.format(1_700_000_000_005L))
        assertEquals("1969-12-31T23:59:59.999Z", Iso8601.format(-1L))
        assertEquals("2000-02-29T00:00:00Z", Iso8601.format(951_782_400_000L))
        for (t in listOf(0L, 1L, 1_234_567_890_123L, 4_102_444_800_000L, -2_208_988_800_000L)) {
            assertEquals(t, Iso8601.parse(Iso8601.format(t)))
        }
    }
}
