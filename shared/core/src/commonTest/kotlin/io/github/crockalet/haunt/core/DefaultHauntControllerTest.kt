package io.github.crockalet.haunt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultHauntControllerTest {

    private val epoch = 1_700_000_000_000L
    private val origin = LatLng(0.0, 0.0)

    /** Exactly 1000 m due north of [origin]. */
    private val north1km = LatLng(1000.0 / (Geo.EARTH_RADIUS_METERS * PI / 180), 0.0)
    private val straight = Route(listOf(origin, north1km), name = "north")

    private class Harness(val controller: DefaultHauntController, val fixes: MutableList<Fix>)

    private fun TestScope.harness(
        tick: Duration = 1.seconds,
        defaults: HauntDefaults = HauntDefaults(),
    ): Harness {
        val controller = DefaultHauntController(
            scope = backgroundScope,
            tickInterval = tick,
            clock = HauntClock.of(testScheduler.timeSource, epoch),
            defaults = defaults,
        )
        val fixes = mutableListOf<Fix>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.fixes.toList(fixes) }
        runCurrent()
        return Harness(controller, fixes)
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun Harness.moving() = assertIs<HauntState.Moving>(controller.state.value)

    @Test
    fun startsIdleWithoutFixes() = runTest {
        val h = harness()
        advance(5.seconds)
        assertEquals(HauntState.Idle, h.controller.state.value)
        assertTrue(h.fixes.isEmpty())
    }

    @Test
    fun holdingEmitsEveryTickWithFreshTime() = runTest {
        val h = harness()
        val pos = LatLng(35.65858, 139.74543)
        h.controller.setLocation(pos, altitude = 40.0, accuracy = 3f, label = "Tokyo Tower")
        // State is updated synchronously.
        val holding = assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals("Tokyo Tower", holding.label)
        runCurrent()
        assertEquals(1, h.fixes.size)
        advance(3.seconds)
        assertEquals(4, h.fixes.size)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), h.fixes.map { it.timeMillis - epoch })
        h.fixes.forEach {
            assertEquals(pos, it.position)
            assertEquals(40.0, it.altitude)
            assertEquals(3f, it.accuracy)
            assertNull(it.speed)
        }
        assertEquals(epoch + 3000, h.controller.state.value.currentFix!!.timeMillis)
    }

    @Test
    fun tickIntervalChangesLive() = runTest {
        val h = harness()
        h.controller.setLocation(origin)
        advance(2.seconds)
        assertEquals(listOf(0L, 1000L, 2000L), h.fixes.map { it.timeMillis - epoch })
        h.controller.tickInterval = 250.milliseconds
        runCurrent()
        advance(500.milliseconds)
        // A fix at once on the change, then one per new interval.
        assertEquals(listOf(0L, 1000L, 2000L, 2000L, 2250L, 2500L), h.fixes.map { it.timeMillis - epoch })
        assertFailsWith<IllegalArgumentException> { h.controller.tickInterval = Duration.ZERO }
        assertEquals(250.milliseconds, h.controller.tickInterval)
    }

    @Test
    fun defaultsApplyWhenNotGiven() = runTest {
        val h = harness(defaults = HauntDefaults(accuracy = 12f, altitude = 7.0))
        h.controller.setLocation(origin)
        val fix = h.controller.state.value.currentFix!!
        assertEquals(12f, fix.accuracy)
        assertEquals(7.0, fix.altitude)
        h.controller.defaults = HauntDefaults(accuracy = 1f)
        assertEquals(1f, h.controller.state.value.currentFix!!.accuracy)
        assertNull(h.controller.state.value.currentFix!!.altitude)
    }

    @Test
    fun customTickInterval() = runTest {
        val h = harness(tick = 250.milliseconds)
        h.controller.setLocation(origin)
        advance(1.seconds)
        assertEquals(5, h.fixes.size)
    }

    @Test
    fun stopGoesIdleAndStopsFixes() = runTest {
        val h = harness()
        h.controller.setLocation(origin)
        advance(2.seconds)
        assertEquals(3, h.fixes.size)
        h.controller.stop()
        assertEquals(HauntState.Idle, h.controller.state.value)
        advance(5.seconds)
        assertEquals(3, h.fixes.size)
        // And can start again.
        h.controller.setLocation(origin)
        runCurrent()
        assertEquals(4, h.fixes.size)
    }

    @Test
    fun routeProgressBearingAndEta() = runTest {
        val h = harness()
        h.controller.setLocation(origin)
        h.controller.playRoute(straight, Speed(10.0))
        val start = h.moving()
        assertEquals(0.0, start.progress.traveledMeters, 1e-6)
        assertEquals(1000.0, start.progress.totalMeters, 1e-6)
        assertEquals(100L, start.progress.etaSeconds)
        assertEquals("north", start.routeName)
        assertNull(start.playbackRate)

        advance(10.seconds)
        val m = h.moving()
        assertEquals(100.0, m.progress.traveledMeters, 1e-6)
        assertEquals(90L, m.progress.etaSeconds)
        assertEquals(0f, m.fix.bearing!!, 1e-3f)
        assertEquals(10f, m.fix.speed)
        assertEquals(Speed(10.0), m.speed)
        assertEquals(100.0, Geo.distanceMeters(origin, m.fix.position), 1e-3)
        assertFalse(m.paused)
        // One fix per tick, distance increasing.
        assertEquals(11, h.fixes.size)
        val distances = h.fixes.map { Geo.distanceMeters(origin, it.position) }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun onceEndsHoldingAtLastPoint() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0), LoopMode.Once)
        advance(99.seconds)
        assertIs<HauntState.Moving>(h.controller.state.value)
        advance(1.seconds)
        val holding = assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals(north1km, holding.fix.position)
        assertEquals("north", holding.label)
        val count = h.fixes.size
        advance(3.seconds)
        assertEquals(count + 3, h.fixes.size) // keeps streaming while holding
        assertEquals(north1km, h.fixes.last().position)
    }

    @Test
    fun loopRestartsFromStart() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0), LoopMode.Loop)
        advance(105.seconds)
        val m = h.moving()
        assertEquals(50.0, m.progress.traveledMeters, 1e-6)
        assertEquals(LoopMode.Loop, m.loop)
        assertEquals(0f, m.fix.bearing!!, 1e-3f)
    }

    @Test
    fun pingPongReverses() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0), LoopMode.PingPong)
        advance(105.seconds)
        val back = h.moving()
        assertEquals(50.0, back.progress.traveledMeters, 1e-6)
        assertEquals(950.0, Geo.distanceMeters(origin, back.fix.position), 1e-3)
        assertEquals(180f, back.fix.bearing!!, 1e-3f)
        assertEquals(95L, back.progress.etaSeconds)
        advance(100.seconds) // 2050 m → forward again, 50 m in
        val forward = h.moving()
        assertEquals(50.0, Geo.distanceMeters(origin, forward.fix.position), 1e-3)
        assertEquals(0f, forward.fix.bearing!!, 1e-3f)
    }

    @Test
    fun setLoopModeChangesLoopWithoutRestarting() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0), LoopMode.Once)
        advance(30.seconds)
        h.controller.setLoopMode(LoopMode.Loop)
        val m = h.moving()
        assertEquals(LoopMode.Loop, m.loop)
        assertEquals(300.0, m.progress.traveledMeters, 1e-6) // kept its place
        advance(80.seconds) // 1100 m → wrapped instead of finishing
        assertEquals(100.0, h.moving().progress.traveledMeters, 1e-6)
    }

    @Test
    fun setLoopModeFromPingPongBackwardsCarriesOnForwards() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0), LoopMode.PingPong)
        advance(105.seconds) // on the way back, 950 m from the origin
        h.controller.setLoopMode(LoopMode.Once)
        val m = h.moving()
        assertEquals(LoopMode.Once, m.loop)
        assertEquals(950.0, Geo.distanceMeters(origin, m.fix.position), 1e-3)
        assertEquals(0f, m.fix.bearing!!, 1e-3f)
        advance(6.seconds) // reaches the end and holds there
        val held = assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals(north1km, held.fix.position)
    }

    @Test
    fun setLoopModeIgnoredWhenNotPlaying() = runTest {
        val h = harness()
        h.controller.setLocation(origin)
        h.controller.setLoopMode(LoopMode.Loop)
        assertIs<HauntState.Holding>(h.controller.state.value)
    }

    @Test
    fun pauseFreezesPositionButKeepsEmitting() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0))
        advance(10.seconds)
        h.controller.pause()
        val paused = h.moving()
        assertTrue(paused.paused)
        assertEquals(0f, paused.fix.speed)
        val before = h.fixes.size
        advance(5.seconds)
        assertEquals(before + 5, h.fixes.size)
        assertEquals(100.0, h.moving().progress.traveledMeters, 1e-6)
        h.fixes.takeLast(5).forEach { assertEquals(paused.fix.position, it.position) }
        assertTrue(h.fixes.last().timeMillis > paused.fix.timeMillis)

        h.controller.resume()
        assertFalse(h.moving().paused)
        advance(5.seconds)
        assertEquals(150.0, h.moving().progress.traveledMeters, 1e-6)
    }

    @Test
    fun pauseMidTickCountsPartialMovement() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0))
        advance(10.seconds)
        advanceTimeBy(500.milliseconds)
        h.controller.pause()
        assertEquals(105.0, h.moving().progress.traveledMeters, 1e-6)
    }

    @Test
    fun setSpeedChangesRouteSpeed() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0))
        advance(10.seconds)
        h.controller.setSpeed(Speed(20.0))
        advance(10.seconds)
        val m = h.moving()
        assertEquals(300.0, m.progress.traveledMeters, 1e-6)
        assertEquals(35L, m.progress.etaSeconds)
        assertEquals(20f, m.fix.speed)
        assertFailsWith<IllegalArgumentException> { h.controller.setSpeed(Speed(-1.0)) }
    }

    @Test
    fun zeroSpeedHasNoEta() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(0.0))
        advance(10.seconds)
        val m = h.moving()
        assertEquals(0.0, m.progress.traveledMeters)
        assertNull(m.progress.etaSeconds)
    }

    @Test
    fun timedPlaybackFollowsRecordedTiming() = runTest {
        val h = harness()
        // 1000 m in 100 s, but the first half takes 80 s and the second 20 s.
        val mid = LatLng(north1km.lat / 2, 0.0)
        val track = Route(
            points = listOf(origin, mid, north1km),
            timestampsMillis = listOf(epoch, epoch + 80_000, epoch + 100_000),
            name = "gpx",
        )
        h.controller.playRoute(track, Speed.Walk)
        val start = h.moving()
        assertEquals(1.0, start.playbackRate)
        assertEquals(100L, start.progress.etaSeconds)

        advance(40.seconds)
        var m = h.moving()
        assertEquals(250.0, m.progress.traveledMeters, 1e-3)
        assertEquals(6.25f, m.fix.speed!!, 1e-3f)
        assertEquals(60L, m.progress.etaSeconds)

        h.controller.setPlaybackRate(2.0)
        assertEquals(2.0, h.moving().playbackRate)
        advance(20.seconds) // 40 s of track → t = 80 s
        m = h.moving()
        assertEquals(500.0, m.progress.traveledMeters, 1e-3)
        assertEquals(10L, m.progress.etaSeconds)

        advance(5.seconds) // t = 90 s, in the fast half: 25 m/s × 2
        m = h.moving()
        assertEquals(750.0, m.progress.traveledMeters, 1e-3)
        assertEquals(50f, m.fix.speed!!, 1e-3f)
        assertEquals(Speed(50.0).metersPerSecond, m.speed.metersPerSecond, 1e-6)

        advance(5.seconds)
        assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals(2.0, h.controller.playbackRate)
        assertFailsWith<IllegalArgumentException> { h.controller.setPlaybackRate(0.0) }
    }

    @Test
    fun setSpeedOnTimedTrackSwitchesToConstantSpeed() = runTest {
        val h = harness()
        val track = straight.copy(timestampsMillis = listOf(0L, 100_000L))
        h.controller.playRoute(track, Speed.Walk)
        advance(10.seconds)
        assertEquals(100.0, h.moving().progress.traveledMeters, 1e-6)
        h.controller.setSpeed(Speed(1.0))
        val m = h.moving()
        assertNull(m.playbackRate)
        assertEquals(100.0, m.progress.traveledMeters, 1e-6)
        advance(10.seconds)
        assertEquals(110.0, h.moving().progress.traveledMeters, 1e-6)
    }

    @Test
    fun invalidTimestampsFallBackToConstantSpeed() = runTest {
        val h = harness()
        h.controller.playRoute(straight.copy(timestampsMillis = listOf(5L, 1L)), Speed(10.0))
        assertNull(h.moving().playbackRate)
        assertEquals(100L, h.moving().progress.etaSeconds)
    }

    @Test
    fun routeAltitudesAreInterpolated() = runTest {
        val h = harness()
        h.controller.playRoute(straight.copy(altitudes = listOf(0.0, 100.0)), Speed(10.0))
        advance(25.seconds)
        assertEquals(25.0, h.moving().fix.altitude!!, 1e-6)
    }

    @Test
    fun singlePointRouteHolds() = runTest {
        val h = harness()
        h.controller.playRoute(Route(listOf(origin, origin), name = "dot"), Speed.Walk, LoopMode.Loop)
        val holding = assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals(origin, holding.fix.position)
        assertFailsWith<IllegalArgumentException> { h.controller.playRoute(Route(emptyList()), Speed.Walk) }
    }

    @Test
    fun joystickIntegratesPosition() = runTest {
        val h = harness()
        h.controller.setLocation(origin, altitude = 12.0)
        h.controller.startJoystick(Speed(10.0))
        var j = assertIs<HauntState.Joystick>(h.controller.state.value)
        assertEquals(origin, j.fix.position)
        assertEquals(12.0, j.fix.altitude)
        assertEquals(0.0, j.distanceMeters)

        h.controller.joystickInput(90.0, 0.5)
        advance(10.seconds)
        j = assertIs<HauntState.Joystick>(h.controller.state.value)
        assertEquals(50.0, j.distanceMeters, 1e-9)
        assertEquals(90.0, j.headingDeg)
        assertEquals(50.0, Geo.distanceMeters(origin, j.fix.position), 1e-6)
        assertEquals(90.0, Geo.initialBearingDeg(origin, j.fix.position), 1e-6)
        assertEquals(5f, j.fix.speed)
        assertEquals(90f, j.fix.bearing)

        // Releasing the stick keeps heading, stops moving.
        h.controller.joystickInput(180.0, 0.0)
        advance(5.seconds)
        j = assertIs<HauntState.Joystick>(h.controller.state.value)
        assertEquals(50.0, j.distanceMeters, 1e-9)
        assertEquals(90.0, j.headingDeg)
        assertEquals(0f, j.fix.speed)

        // setSpeed changes max speed; magnitude is clamped to 1.
        h.controller.setSpeed(Speed(20.0))
        h.controller.joystickInput(-90.0, 3.0)
        advance(1.seconds)
        j = assertIs<HauntState.Joystick>(h.controller.state.value)
        assertEquals(Speed(20.0), j.maxSpeed)
        assertEquals(270.0, j.headingDeg)
        assertEquals(70.0, j.distanceMeters, 1e-9)
        assertEquals(30.0, Geo.distanceMeters(origin, j.fix.position), 1e-6)

        // Pause freezes.
        h.controller.pause()
        assertTrue(assertIs<HauntState.Joystick>(h.controller.state.value).paused)
        val count = h.fixes.size
        advance(3.seconds)
        assertEquals(count + 3, h.fixes.size)
        assertEquals(70.0, assertIs<HauntState.Joystick>(h.controller.state.value).distanceMeters, 1e-9)
    }

    @Test
    fun joystickFromExplicitPositionAndErrors() = runTest {
        val h = harness()
        assertFailsWith<IllegalStateException> { h.controller.startJoystick(Speed.Walk) }
        h.controller.joystickInput(0.0, 1.0) // ignored when not in joystick mode
        assertEquals(HauntState.Idle, h.controller.state.value)
        h.controller.startJoystick(Speed.Walk, from = LatLng(10.0, 10.0))
        assertEquals(LatLng(10.0, 10.0), h.controller.state.value.currentFix!!.position)
        assertFailsWith<IllegalArgumentException> { h.controller.joystickInput(Double.NaN, 1.0) }
    }

    @Test
    fun joystickTakesOverFromRoute() = runTest {
        val h = harness()
        h.controller.playRoute(straight, Speed(10.0))
        advance(10.seconds)
        h.controller.startJoystick(Speed.Walk)
        val j = assertIs<HauntState.Joystick>(h.controller.state.value)
        assertEquals(100.0, Geo.distanceMeters(origin, j.fix.position), 1e-6)
        assertEquals(0.0, j.headingDeg, 1e-6)
    }

    @Test
    fun invalidCoordinatesRejected() = runTest {
        val h = harness()
        assertFailsWith<IllegalArgumentException> { h.controller.setLocation(LatLng(91.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { h.controller.setLocation(LatLng(0.0, Double.NaN)) }
        assertFailsWith<IllegalArgumentException> {
            h.controller.playRoute(Route(listOf(origin, LatLng(0.0, 181.0))), Speed.Walk)
        }
        assertEquals(HauntState.Idle, h.controller.state.value)
    }

    @Test
    fun commandWhileMovingRestartsTickPhase() = runTest {
        val h = harness()
        h.controller.setLocation(origin)
        advance(1.seconds)
        advanceTimeBy(400.milliseconds)
        h.controller.setLocation(north1km)
        runCurrent()
        assertEquals(north1km, h.fixes.last().position)
        assertEquals(1400L, h.fixes.last().timeMillis - epoch)
        advance(1.seconds)
        assertEquals(2400L, h.fixes.last().timeMillis - epoch)
    }
}
