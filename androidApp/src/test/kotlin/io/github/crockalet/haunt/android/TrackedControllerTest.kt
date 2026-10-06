package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.control.EventHub
import io.github.crockalet.haunt.android.control.EventPipeline
import io.github.crockalet.haunt.android.control.StateEventDeriver
import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.protocol.FinishReason
import io.github.crockalet.haunt.protocol.HauntEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrackedControllerTest {
    private val origin = LatLng(0.0, 0.0)
    private val north100m = LatLng(100.0 / (Geo.EARTH_RADIUS_METERS * PI / 180), 0.0)
    private val route = Route(listOf(origin, north100m), name = "north")

    private fun Harness.finished() = synchronized(events) { events.filterIsInstance<HauntEvent.RouteFinishedEvent>() }

    @Test
    fun arrivalIsReportedWithRouteId() = runTest {
        val h = harness()
        val id = h.controller.play(route, Speed(10.0))
        assertEquals("route1", id)
        assertEquals(id, h.controller.currentRouteId)
        advance(5.seconds)
        h.controller.onStateObserved()
        assertTrue(h.finished().isEmpty())
        advance(6.seconds)
        assertIs<HauntState.Holding>(h.controller.state.value)
        h.controller.onStateObserved()
        val f = h.finished().single()
        assertEquals(id, f.routeId)
        assertEquals(FinishReason.Arrived, f.reason)
        assertEquals(north100m.lat, f.fix!!.position.lat, 1e-9)
        assertNull(h.controller.currentRouteId)
        // Idempotent.
        h.controller.onStateObserved()
        assertEquals(1, h.finished().size)
    }

    @Test
    fun arrivalBeforeNextCommandIsNotReportedAsReplaced() = runTest {
        val h = harness()
        h.controller.play(route, Speed(10.0))
        advance(20.seconds) // arrived, but nobody observed the state yet
        h.controller.play(route, Speed(10.0))
        assertEquals(listOf("route1" to FinishReason.Arrived), h.finished().map { it.routeId to it.reason })
        assertEquals("route2", h.controller.currentRouteId)
    }

    @Test
    fun newRouteOrLocationReplaces() = runTest {
        val h = harness()
        h.controller.play(route, Speed(1.0))
        h.controller.play(route, Speed(1.0))
        h.controller.setLocation(origin)
        assertEquals(
            listOf("route1" to FinishReason.Replaced, "route2" to FinishReason.Replaced),
            h.finished().map { it.routeId to it.reason },
        )
        h.controller.play(route, Speed(1.0))
        h.controller.startJoystick(Speed.Walk)
        assertEquals("route3" to FinishReason.Replaced, h.finished().last().let { it.routeId to it.reason })
    }

    @Test
    fun stopAndHoldReportStopped() = runTest {
        val h = harness()
        h.controller.play(route, Speed(1.0))
        advance(10.seconds)
        assertTrue(h.controller.holdCurrentPosition())
        val holding = assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals("north", holding.label)
        assertEquals(10.0, Geo.distanceMeters(origin, holding.fix.position), 0.5)
        assertEquals(FinishReason.Stopped, h.finished().single().reason)
        assertFalse(h.controller.holdCurrentPosition())

        h.controller.play(route, Speed(1.0))
        h.controller.stop()
        assertEquals("route2" to FinishReason.Stopped, h.finished().last().let { it.routeId to it.reason })
        assertEquals(HauntState.Idle, h.controller.state.value)
        // lastFix survives stop.
        assertEquals(origin, h.controller.lastFix!!.position)
    }

    @Test
    fun zeroLengthRouteArrivesImmediately() = runTest {
        val h = harness()
        val id = h.controller.play(Route(listOf(origin, origin)), Speed.Walk)
        assertEquals(id to FinishReason.Arrived, h.finished().single().let { it.routeId to it.reason })
    }

    @Test
    fun pipelineEmitsFixStateProgressAndFinished() = runTest {
        val h = harness()
        val hub = EventHub()
        val collected = mutableListOf<HauntEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.events.toList(collected) }
        val tracked = io.github.crockalet.haunt.android.control.TrackedController(h.engine, hub::emit) { "r" }
        EventPipeline(backgroundScope, tracked, hub, StateEventDeriver(1000) { testScheduler.currentTime })
        runCurrent()
        tracked.play(route, Speed(10.0))
        advance(12.seconds)

        val types = collected.map { it::class.simpleName }
        assertTrue("FixEvent" in types)
        val states = collected.filterIsInstance<HauntEvent.StateEvent>().map { it.state::class.simpleName }
        assertEquals(listOf("Idle", "Moving", "Holding"), states)
        val progress = collected.filterIsInstance<HauntEvent.RouteProgressEvent>()
        assertTrue(progress.size in 9..12, "one progress per tick, got ${progress.size}")
        assertTrue(progress.all { it.routeId == "r" })
        val finished = collected.filterIsInstance<HauntEvent.RouteFinishedEvent>().single()
        assertEquals("r" to FinishReason.Arrived, finished.routeId to finished.reason)
        // routeFinished comes before the Holding state event.
        assertTrue(collected.indexOf(finished) < collected.indexOfLast { it is HauntEvent.StateEvent })
    }

    @Test
    fun deriverDedupesStateAndThrottlesProgress() {
        var now = 0L
        val d = StateEventDeriver(1000) { now }
        val fix = io.github.crockalet.haunt.core.Fix(origin, timeMillis = 1)
        fun moving(traveled: Double, paused: Boolean = false, t: Long = 1) = HauntState.Moving(
            fix.copy(timeMillis = t), "r", RouteProgress(traveled, 100.0, 10), Speed.Walk, LoopMode.Once, paused,
        )
        assertEquals(2, d.onState(moving(0.0), "id").size) // state + progress
        now = 300
        assertTrue(d.onState(moving(1.0, t = 2), "id").isEmpty()) // throttled, fix time change isn't a state change
        now = 1000
        val e = d.onState(moving(2.0, t = 3), "id").single()
        assertIs<HauntEvent.RouteProgressEvent>(e)
        now = 3000
        assertEquals(2, d.onState(moving(2.0, paused = true), "id").size) // pause is a state change
        now = 5000
        assertTrue(d.onState(moving(2.0, paused = true, t = 9), "id").isEmpty()) // no progress while paused
        assertEquals(1, d.onState(HauntState.Holding(fix), null).size)
        assertTrue(d.onState(HauntState.Holding(fix.copy(timeMillis = 99)), null).isEmpty())
        assertEquals(1, d.onState(HauntState.Holding(fix, label = "x"), null).size)
    }
}
