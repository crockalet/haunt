package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.location.RealFix
import io.github.crockalet.haunt.android.location.RealFixes
import io.github.crockalet.haunt.core.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealFixesTest {
    private val now = 1_760_000_000_000L
    private fun fix(acc: Float?, ageMs: Long, mock: Boolean = false, lat: Double = 35.0) =
        RealFix(LatLng(lat, 139.0), acc, now - ageMs, mock)

    @Test
    fun picksTheMostAccurateRecentRealFix() {
        val gps = fix(5f, 30_000, lat = 1.0)
        val network = fix(40f, 5_000, lat = 2.0)
        assertEquals(gps, RealFixes.best(listOf(network, gps), now))
    }

    @Test
    fun ignoresMockAndStaleFixes() {
        val mock = fix(3f, 1_000, mock = true)
        val stale = fix(3f, RealFixes.MAX_AGE_MILLIS + 1)
        val fromTheFuture = fix(3f, -60_000)
        val ok = fix(50f, 10_000, lat = 9.0)
        assertEquals(ok, RealFixes.best(listOf(mock, stale, fromTheFuture, ok), now))
        assertNull(RealFixes.best(listOf(mock, stale), now))
        assertNull(RealFixes.best(emptyList(), now))
    }

    @Test
    fun unknownAccuracyLosesAndRecencyBreaksTies() {
        val unknown = fix(null, 1_000, lat = 1.0)
        val older = fix(10f, 60_000, lat = 2.0)
        val newer = fix(10f, 20_000, lat = 3.0)
        assertEquals(newer, RealFixes.best(listOf(unknown, older, newer), now))
        assertEquals(unknown, RealFixes.best(listOf(unknown), now))
    }
}
