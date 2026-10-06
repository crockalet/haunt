package io.github.crockalet.haunt.ui

import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.state.PlaceSearch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchTest {
    private val tokyo = LatLng(35.6586, 139.7454)

    private class Calls {
        val searches = mutableListOf<Pair<String, LatLng?>>()
        val nearby = mutableListOf<LatLng>()
    }

    private fun TestScope.search(
        calls: Calls,
        latencyMillis: Long = 0,
        fail: Boolean = false,
    ) = PlaceSearch(
        scope = backgroundScope,
        search = { q, near ->
            calls.searches += q to near
            delay(latencyMillis)
            if (fail) throw IOException("offline")
            listOf(Place("$q 1", tokyo), Place("$q 2", tokyo))
        },
        nearby = { p ->
            calls.nearby += p
            listOf(Place("Tokyo Tower", p, subtitle = "Minato, Tokyo"))
        },
        debounceMillis = 300,
    )

    /** advanceUntilIdle ignores backgroundScope work, so advance the clock explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    @Test
    fun debouncesTyping() = runTest {
        val calls = Calls()
        val s = search(calls)
        for (q in listOf("t", "to", "tok", "toky", "tokyo")) {
            s.update(q, tokyo)
            advanceTimeBy(100)
        }
        runCurrent()
        assertTrue(calls.searches.isEmpty())
        assertTrue(s.state.value.searching)
        settle()
        assertEquals(listOf<Pair<String, LatLng?>>("tokyo" to tokyo), calls.searches)
        assertEquals(listOf("tokyo 1", "tokyo 2"), s.state.value.results.map { it.name })
        assertEquals("tokyo", s.state.value.query)
        assertEquals(false, s.state.value.searching)
    }

    @Test
    fun shortOrBlankQueriesDontSearchAndClearResults() = runTest {
        val calls = Calls()
        val s = search(calls)
        s.update("tokyo", null)
        settle()
        s.update(" t ", null)
        settle()
        assertEquals(1, calls.searches.size)
        assertTrue(s.state.value.results.isEmpty())
        assertEquals("t", s.state.value.query)
    }

    @Test
    fun staleRequestIsCancelled() = runTest {
        val calls = Calls()
        val s = search(calls, latencyMillis = 1_000)
        s.update("shibuya", null)
        advanceTimeBy(500) // first request in flight
        s.update("shinjuku", null)
        settle()
        assertEquals(listOf("shibuya", "shinjuku"), calls.searches.map { it.first })
        assertEquals("shinjuku 1", s.state.value.results.first().name)
    }

    @Test
    fun errorsAreReported() = runTest {
        val s = search(Calls(), fail = true)
        s.update("tokyo", null)
        settle()
        assertEquals("offline", s.state.value.error)
        assertTrue(s.state.value.results.isEmpty())
        s.update("", null)
        settle()
        assertNull(s.state.value.error)
    }

    @Test
    fun nearbyIsQueriedOncePerArea() = runTest {
        val calls = Calls()
        val s = search(calls)
        s.update("", tokyo)
        settle()
        s.update("", Geo.destination(tokyo, 90.0, 10.0)) // a few steps away: same area
        settle()
        assertEquals(1, calls.nearby.size)
        assertEquals("Minato, Tokyo", s.state.value.areaFor(tokyo))
        assertEquals(listOf("Tokyo Tower"), s.state.value.nearbyFor(tokyo).map { it.name })

        val far = Geo.destination(tokyo, 90.0, 500.0)
        assertTrue(s.state.value.nearbyFor(far).isEmpty())
        s.update("", far)
        settle()
        assertEquals(2, calls.nearby.size)
        assertEquals("Minato, Tokyo", s.state.value.areaFor(far))
    }
}
