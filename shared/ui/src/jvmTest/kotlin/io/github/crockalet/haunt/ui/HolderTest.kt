package io.github.crockalet.haunt.ui

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.ui.state.CommandException
import io.github.crockalet.haunt.ui.state.HauntCommands
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.state.Notice
import io.github.crockalet.haunt.ui.state.RouteOutcome
import io.github.crockalet.haunt.ui.state.RouteRequest
import io.github.crockalet.haunt.ui.state.SpeedPreset
import io.github.crockalet.haunt.ui.state.Track
import io.github.crockalet.haunt.ui.state.Geo
import io.github.crockalet.haunt.ui.state.MeasuredLine
import io.github.crockalet.haunt.ui.state.buildMapUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records every command; its state is set by the test. */
private class RecordingController(initial: HauntState = HauntState.Idle) : HauntController {
    override val state = MutableStateFlow(initial)
    override val fixes = MutableSharedFlow<Fix>()
    val calls = mutableListOf<String>()

    override fun setLocation(position: LatLng, altitude: Double?, accuracy: Float?, label: String?) {
        calls += "setLocation"
    }
    override fun playRoute(route: Route, speed: Speed, loop: LoopMode) {
        calls += "playRoute"
    }
    override fun startJoystick(maxSpeed: Speed, from: LatLng?) {
        calls += "startJoystick"
    }
    override fun joystickInput(bearingDeg: Double, magnitude: Double) {
        calls += "joystickInput"
    }
    override fun setSpeed(speed: Speed) {
        calls += "setSpeed ${"%.2f".format(speed.metersPerSecond)}"
    }
    override fun setPlaybackRate(multiplier: Double) {
        calls += "setPlaybackRate $multiplier"
    }
    override fun setLoopMode(loop: LoopMode) {
        calls += "setLoopMode $loop"
    }
    override fun pause() {
        calls += "pause"
    }
    override fun resume() {
        calls += "resume"
    }
    override fun stop() {
        calls += "stop"
    }
}

private class RecordingCommands(var failWith: Exception? = null) : HauntCommands {
    val locations = mutableListOf<Pair<LatLng, String?>>()
    val routes = mutableListOf<RouteRequest>()
    var outcome: (RouteRequest) -> RouteOutcome = { RouteOutcome(it.route.points) }

    override suspend fun setLocation(position: LatLng, accuracy: Float?, label: String?) {
        failWith?.let { throw it }
        locations += position to label
    }

    override suspend fun playRoute(request: RouteRequest): RouteOutcome {
        failWith?.let { throw it }
        routes += request
        return outcome(request)
    }
}

class HolderTest {
    private val a = LatLng(0.0, 0.0)
    private val b = LatLng(0.0, 0.01)
    private val fix = Fix(a, timeMillis = 0)

    private fun moving(playbackRate: Double? = null, loop: LoopMode = LoopMode.Once) = HauntState.Moving(
        fix = fix, routeName = "r", progress = RouteProgress(10.0, 1000.0, 100), speed = Speed.Walk,
        loop = loop, paused = false, playbackRate = playbackRate,
    )

    @Test
    fun rateChipSetsPlaybackRateOnRecordedTracks() {
        val c = RecordingController(moving(playbackRate = 1.0))
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route))
        holder.cycleRate()
        assertEquals(listOf("setPlaybackRate 2.0"), c.calls)
        holder.cycleRate()
        assertEquals("setPlaybackRate 4.0", c.calls.last())
        c.state.value = moving(playbackRate = 4.0)
        assertEquals("4×", assertNotNull(holder.uiState.route).rateLabel)
    }

    @Test
    fun rateChipMultipliesConstantSpeed() {
        val c = RecordingController(moving())
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route))
        holder.cycleRate()
        assertEquals(listOf("setSpeed 2.80"), c.calls)
    }

    @Test
    fun speedPresetsCallSetSpeed() {
        val c = RecordingController(moving(playbackRate = 1.0))
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route))
        holder.setSpeedPreset(SpeedPreset.Drive)
        assertEquals(listOf("setSpeed 13.90"), c.calls)
    }

    @Test
    fun loopChangesLiveWithoutRestarting() {
        val c = RecordingController(moving())
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)))
        holder.setLoop(LoopMode.PingPong)
        assertEquals(listOf("setLoopMode PingPong"), c.calls)
        assertEquals(LoopMode.PingPong, holder.local.loop)
    }

    @Test
    fun customSpeedEditorAppliesWhenCustomSelected() {
        val c = RecordingController(moving())
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route))
        holder.setCustomSpeed(36f) // not selected yet: just remembered
        assertTrue(c.calls.isEmpty())
        holder.setSpeedPreset(SpeedPreset.Custom)
        assertEquals("setSpeed 10.00", c.calls.last())
        holder.setCustomSpeed(72f)
        assertEquals("setSpeed 20.00", c.calls.last())
        holder.setCustomSpeed(1000f)
        assertEquals(150f, assertNotNull(holder.uiState.route).customKmh, 0.01f)
    }

    @Test
    fun longPressAndPlacesGoThroughCommands() {
        val c = RecordingController()
        val commands = RecordingCommands()
        val holder = MapStateHolder(c, commands = commands)
        holder.onMapLongPress(a)
        holder.hauntAt(b, "Somewhere")
        assertEquals(listOf<Pair<LatLng, String?>>(a to "Dropped pin", b to "Somewhere"), commands.locations)
        assertTrue(c.calls.isEmpty())
    }

    @Test
    fun failedCommandShowsNoticeWithHint() {
        val commands = RecordingCommands(failWith = CommandException("Haunt isn't the mock location app", "Pick it in Developer options"))
        val holder = MapStateHolder(RecordingController(), commands = commands)
        holder.onMapLongPress(a)
        assertEquals(Notice("Haunt isn't the mock location app", "Pick it in Developer options"), holder.notice)
        holder.dismissNotice()
        assertNull(holder.notice)
    }

    @Test
    fun routeRequestCarriesFollowRoadsAndShowsRoutedLine() {
        val c = RecordingController()
        val commands = RecordingCommands()
        val routed = listOf(a, LatLng(0.001, 0.005), b)
        commands.outcome = { RouteOutcome(routed, warning = null) }
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b), followRoads = true, loop = LoopMode.Loop), commands = commands)
        holder.playPause()
        val req = commands.routes.single()
        assertTrue(req.followRoads)
        assertEquals(LoopMode.Loop, req.loop)
        assertNull(req.playbackRate)
        c.state.value = moving()
        holder.onEngineState(c.state.value)
        assertEquals(routed, holder.uiState.map.route)
    }

    @Test
    fun routingWarningIsANonErrorNotice() {
        val commands = RecordingCommands().apply { outcome = { RouteOutcome(it.route.points, "Routing failed; using straight lines") } }
        val holder = MapStateHolder(RecordingController(), LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)), commands = commands)
        holder.playPause()
        assertEquals(Notice("Routing failed; using straight lines", error = false), holder.notice)
    }

    @Test
    fun recordedTrackPlaysByItsTimingAtTheChipRate() {
        val commands = RecordingCommands()
        val holder = MapStateHolder(RecordingController(), LocalUiState(rate = 2), commands = commands)
        val route = Route(listOf(a, b), timestampsMillis = listOf(0L, 60_000L), name = "run")
        holder.loadTrack(Track("Morning run", "GPX", route.points, route))
        assertEquals(MapMode.Route, holder.local.mode)
        holder.playPause()
        val req = commands.routes.single()
        assertEquals(2.0, req.playbackRate)
        assertEquals(false, req.followRoads)
        assertEquals(listOf(0L, 60_000L), req.route.timestampsMillis)
    }

    @Test
    fun idleRouteShowsDraftRate() {
        val ui = buildMapUiState(HauntState.Idle, LocalUiState(mode = MapMode.Route, rate = 2))
        assertEquals("2×", assertNotNull(ui.route).rateLabel)
    }

    // --- Locate button ----------------------------------------------------------------------

    @Test
    fun locateInPinModeHauntsMyLocation() {
        val commands = RecordingCommands()
        val holder = MapStateHolder(RecordingController(), LocalUiState(mode = MapMode.Pin), commands = commands)
        holder.locate { b }
        assertEquals(listOf<Pair<LatLng, String?>>(b to MapStateHolder.MY_LOCATION_LABEL), commands.locations)
        assertEquals(false, holder.local.locating)
    }

    @Test
    fun joystickWithNoPositionWaitsInsteadOfStarting() {
        val c = RecordingController()
        val holder = MapStateHolder(c, LocalUiState())
        holder.selectMode(MapMode.Joystick)
        holder.joystickInput(90.0, 1.0)
        assertEquals(MapMode.Joystick, holder.local.mode)
        assertEquals(emptyList(), c.calls)
    }

    @Test
    fun joystickInputGoesToTheEngineWithoutTouchingLocal() {
        val c = RecordingController(HauntState.Joystick(fix, Speed.kmh(12.0), 0.0, 0.0))
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Joystick))
        val before = holder.local
        holder.joystickInput(90.0, 0.5)
        holder.joystickInput(91.0, 0.6)
        holder.joystickInput(91.0, 0.0)
        assertSame(before, holder.local)
        assertEquals(List(3) { "joystickInput" }, c.calls)
    }

    @Test
    fun joystickInputStartsFromTheCurrentFix() {
        val c = RecordingController(HauntState.Holding(Fix(b, timeMillis = 0), "pin"))
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Joystick))
        holder.joystickInput(90.0, 1.0)
        assertEquals(listOf("startJoystick", "joystickInput"), c.calls)
    }

    @Test
    fun slidersOnlyCommitWholeKmh() {
        val c = RecordingController(HauntState.Joystick(fix, Speed.kmh(12.0), 0.0, 0.0))
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Joystick))
        holder.setJoystickMaxSpeed(20.2f)
        val after = holder.local
        holder.setJoystickMaxSpeed(19.8f)
        holder.setJoystickMaxSpeed(20.4f)
        assertSame(after, holder.local)
        assertEquals(20f, holder.local.joystickMaxKmh)
        assertEquals(listOf("setSpeed 5.56"), c.calls)

        holder.setCustomSpeed(36.3f)
        val custom = holder.local
        holder.setCustomSpeed(35.7f)
        assertSame(custom, holder.local)
        assertEquals(36.0, custom.customSpeed.kmh, 1e-9)
    }

    @Test
    fun engineTicksLeaveLocalAloneAndIdleKeepsTheLastFix() {
        val c = RecordingController()
        val holder = MapStateHolder(c)
        holder.onEngineState(HauntState.Holding(Fix(a, timeMillis = 0), "pin"))
        assertEquals(a, holder.local.lastPosition)
        val local = holder.local
        holder.onEngineState(HauntState.Holding(Fix(b, timeMillis = 1_000), "pin"))
        holder.onEngineState(HauntState.Holding(Fix(b, timeMillis = 2_000), "pin"))
        assertSame(local, holder.local)
        assertEquals(b, holder.shown.map.fix)
        assertEquals(b, holder.lastPosition)
        holder.onEngineState(HauntState.Idle)
        assertEquals(b, holder.local.lastPosition)
        assertEquals(b, holder.shown.map.fix)
        assertEquals("Last location", assertNotNull(holder.shown.pin).title)
    }

    @Test
    fun shownFollowsOnEngineStateNotTheFlow() {
        val c = RecordingController()
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)))
        c.state.value = moving()
        assertEquals(false, holder.shown.active)
        holder.onEngineState(c.state.value)
        assertEquals(true, holder.shown.active)
        assertEquals(holder.uiState, holder.shown)
    }

    @Test
    fun locateInJoystickModeRestartsTheStickThere() {
        val c = RecordingController()
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Joystick))
        holder.locate { b }
        assertEquals(listOf("startJoystick"), c.calls)
        assertEquals(b, holder.local.lastPosition)
    }

    @Test
    fun locateInRouteModeBecomesTheFirstStopOnce() {
        val far = LatLng(1.0, 1.0)
        val holder = MapStateHolder(RecordingController(), LocalUiState(mode = MapMode.Route, draftRoute = listOf(far)))
        holder.locate { a }
        assertEquals(listOf(a, far), holder.local.draftRoute)
        // Tapping again (a few metres away) doesn't add a duplicate start.
        holder.locate { LatLng(0.0001, 0.0) }
        assertEquals(listOf(a, far), holder.local.draftRoute)
        assertEquals(LatLng(0.0001, 0.0), holder.local.cameraOverride)
    }

    @Test
    fun locateWhileARoutePlaysOnlyMovesTheCamera() {
        val holder = MapStateHolder(RecordingController(moving()), LocalUiState(mode = MapMode.Route, draftRoute = listOf(b)))
        holder.locate { a }
        assertEquals(listOf(b), holder.local.draftRoute)
        assertEquals(a, holder.local.cameraOverride)
    }

    @Test
    fun locateFailureShowsTheHint() {
        val holder = MapStateHolder(RecordingController(), LocalUiState(mode = MapMode.Pin))
        holder.locate { throw CommandException("Haunt is faking your location right now", "Stop haunting first.") }
        assertEquals(Notice("Haunt is faking your location right now", "Stop haunting first."), holder.notice)
        assertEquals(false, holder.local.locating)
        assertTrue(buildMapUiState(HauntState.Idle, holder.local).locating.not())
    }
}

class MeasuredLineTest {
    private val line = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01), LatLng(0.0, 0.01), LatLng(0.01, 0.01), LatLng(0.01, 0.02))

    @Test
    fun cutsLikeGeoSplit() {
        val measured = MeasuredLine(line)
        assertEquals(Geo.length(line), measured.length, 1e-6)
        val total = Geo.length(line)
        for (m in listOf(-5.0, 0.0, 1.0, 500.0, 1111.9, 1112.0, 1500.0, total / 2, total - 1, total, total + 10)) {
            val expected = Geo.split(line, m).first
            val actual = measured.traveled(m)
            assertEquals(expected.size, actual.size, "at $m")
            expected.zip(actual).forEach { (e, x) ->
                assertEquals(e.lat, x.lat, 1e-9, "at $m")
                assertEquals(e.lng, x.lng, 1e-9, "at $m")
            }
        }
    }

    @Test
    fun shortLines() {
        assertEquals(0.0, MeasuredLine(emptyList()).length)
        assertEquals(listOf(LatLng(1.0, 1.0)), MeasuredLine(listOf(LatLng(1.0, 1.0))).traveled(10.0))
    }
}
