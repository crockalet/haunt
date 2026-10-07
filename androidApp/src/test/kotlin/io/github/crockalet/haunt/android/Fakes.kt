package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.control.MockingEnvironment
import io.github.crockalet.haunt.android.control.TrackedController
import io.github.crockalet.haunt.android.net.Geocoder
import io.github.crockalet.haunt.android.net.Router
import io.github.crockalet.haunt.android.net.RoutedPath
import io.github.crockalet.haunt.android.net.RoutingException
import io.github.crockalet.haunt.core.DefaultHauntController
import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.HauntClock
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.RpcException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.IOException
import kotlin.time.Duration

const val EPOCH = 1_700_000_000_000L

class Harness(val controller: TrackedController, val engine: DefaultHauntController, val events: MutableList<HauntEvent>)

fun TestScope.harness(): Harness {
    val engine = DefaultHauntController(scope = backgroundScope, clock = HauntClock.of(testScheduler.timeSource, EPOCH))
    val events = mutableListOf<HauntEvent>()
    var n = 0
    val controller = TrackedController(engine, { synchronized(events) { events += it } }, { "route${++n}" })
    runCurrent()
    return Harness(controller, engine, events)
}

fun TestScope.advance(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

class FakeGeocoder(var places: Map<String, Place> = emptyMap(), var fail: Boolean = false) : Geocoder {
    val calls = mutableListOf<Triple<String, Int, LatLng?>>()
    override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> {
        calls += Triple(query, limit, near)
        if (fail) throw IOException("offline")
        return places.filterKeys { it.equals(query, ignoreCase = true) }.values.take(limit)
    }
}

class FakeRouter(var fail: Boolean = false, var failWith: Exception? = null) : Router {
    val calls = mutableListOf<List<LatLng>>()
    override suspend fun route(waypoints: List<LatLng>): RoutedPath {
        calls += waypoints
        failWith?.let { throw it }
        if (fail) throw RoutingException("Routing failed: NoRoute")
        // A detour via a midpoint shifted east.
        val a = waypoints.first()
        val b = waypoints.last()
        val mid = LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2 + 0.001)
        val points = listOf(a, mid, b)
        return RoutedPath(points, Geo.polylineLength(points))
    }
}

class FakeEnvironment(var selected: Boolean = true, var permission: Boolean = true) : MockingEnvironment {
    var started = 0
    override val appVersion = "9.9.9"
    override val flavour = "foss"
    override fun mockAppSelected() = selected
    override fun checkCanMock() {
        if (!selected) throw RpcException.mockAppNotSelected()
        if (!permission) throw RpcException.unavailable("no permission", "grant it")
    }
    override fun onMockingStarted() {
        started++
    }
}
