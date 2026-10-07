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
import io.github.crockalet.haunt.ui.state.StatusUi
import io.github.crockalet.haunt.ui.state.Track
import io.github.crockalet.haunt.ui.state.Geo
import io.github.crockalet.haunt.ui.state.MeasuredLine
import io.github.crockalet.haunt.ui.state.buildMapUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    val joysticks = mutableListOf<LatLng>()
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

    override suspend fun startJoystick(maxSpeed: Speed, from: LatLng) {
        failWith?.let { throw it }
        joysticks += from
    }

    val any: Boolean get() = locations.isNotEmpty() || routes.isNotEmpty() || joysticks.isNotEmpty()
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
    fun longPressAndPlacesOnlyPickUntilStart() {
        val c = RecordingController()
        val commands = RecordingCommands()
        val holder = MapStateHolder(c, commands = commands)
        holder.onMapLongPress(a)
        holder.pick(b, "Somewhere")
        assertTrue(!commands.any && c.calls.isEmpty())
        val ui = holder.uiState
        assertEquals(b, ui.map.pending)
        assertEquals(b, ui.map.camera)
        assertEquals("Somewhere", assertNotNull(ui.pin).title)
        assertEquals("Haunt this spot", ui.startAction)
        assertEquals(false, ui.active)

        holder.start()
        assertEquals(listOf<Pair<LatLng, String?>>(b to "Somewhere"), commands.locations)
        assertNull(holder.local.pendingSpot)
        assertTrue(c.calls.isEmpty())
    }

    @Test
    fun failedCommandShowsNoticeWithHintAndKeepsThePick() {
        val commands = RecordingCommands(failWith = CommandException("Haunt isn't the mock location app", "Pick it in Developer options"))
        val holder = MapStateHolder(RecordingController(), commands = commands)
        holder.onMapLongPress(a)
        assertNull(holder.notice)
        holder.start()
        assertEquals(Notice("Haunt isn't the mock location app", "Pick it in Developer options"), holder.notice)
        assertEquals(a, holder.local.pendingSpot)
        holder.dismissNotice()
        assertNull(holder.notice)
    }

    @Test
    fun switchingModeNeverStartsOrStopsAnything() {
        val running = listOf(
            HauntState.Idle,
            HauntState.Holding(fix, "pin"),
            moving(),
            HauntState.Joystick(fix, Speed.kmh(12.0), 0.0, 0.0),
        )
        for (engine in running) {
            val c = RecordingController(engine)
            val commands = RecordingCommands()
            val holder = MapStateHolder(c, LocalUiState(lastPosition = a, draftRoute = listOf(a, b)), commands = commands)
            for (mode in listOf(MapMode.Joystick, MapMode.Route, MapMode.Pin, MapMode.Joystick, MapMode.Pin)) {
                holder.selectMode(mode)
                holder.joystickInput(90.0, 1.0)
            }
            assertTrue(c.calls.all { it == "joystickInput" }, "$engine: ${c.calls}")
            assertTrue(!commands.any, "$engine")
            assertEquals(engine !is HauntState.Idle, holder.uiState.active)
        }
    }

    @Test
    fun anotherModesSpoofKeepsRunningAndShowsUntilStart() {
        val c = RecordingController(moving())
        val commands = RecordingCommands()
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)), commands = commands)
        holder.selectMode(MapMode.Pin)
        assertEquals(StatusUi.Message("Haunting · r", active = true), holder.uiState.status)
        assertNull(holder.uiState.startAction) // nothing picked: the button is Stop
        holder.selectMode(MapMode.Joystick)
        assertEquals(StatusUi.Message("Haunting · r", active = true), holder.uiState.status)
        assertEquals("Start joystick", holder.uiState.startAction)
        assertEquals(false, assertNotNull(holder.uiState.joystick).live)

        holder.start()
        assertEquals(listOf(a), commands.joysticks)
        assertTrue(c.calls.isEmpty()) // replaced without stopping first
    }

    @Test
    fun startActionPerModeAndState() {
        val holding = HauntState.Holding(fix, "pin")
        val joystick = HauntState.Joystick(fix, Speed.kmh(12.0), 0.0, 0.0)
        fun start(engine: HauntState, local: LocalUiState) = buildMapUiState(engine, local).startAction

        assertNull(start(HauntState.Idle, LocalUiState()))
        assertEquals("Haunt last location", start(HauntState.Idle, LocalUiState(lastPosition = a)))
        assertEquals("Haunt this spot", start(holding, LocalUiState(pendingSpot = b)))
        assertNull(start(holding, LocalUiState()))

        val route = LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b))
        assertNull(start(HauntState.Idle, route.copy(draftRoute = listOf(a))))
        assertEquals("Start route", start(HauntState.Idle, route))
        assertEquals("Start route", start(holding, route))
        assertNull(start(moving(), route))

        val joy = LocalUiState(mode = MapMode.Joystick)
        assertNull(start(HauntState.Idle, joy))
        assertEquals("Start joystick", start(holding, joy))
        assertNull(start(joystick, joy))
        assertEquals("Start joystick", start(joystick, joy.copy(pendingSpot = b)))
    }

    @Test
    fun playButtonOnlyPausesAndResumes() {
        val c = RecordingController()
        val commands = RecordingCommands()
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)), commands = commands)
        holder.playPause()
        assertTrue(!commands.any && c.calls.isEmpty())
        c.state.value = moving()
        holder.playPause()
        assertEquals(listOf("pause"), c.calls)
    }

    @Test
    fun routeRequestCarriesFollowRoadsAndShowsRoutedLine() {
        val c = RecordingController()
        val commands = RecordingCommands()
        val routed = listOf(a, LatLng(0.001, 0.005), b)
        commands.outcome = { RouteOutcome(routed, warning = null) }
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b), followRoads = true, loop = LoopMode.Loop), commands = commands)
        holder.start()
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
        holder.start()
        assertEquals(Notice("Routing failed; using straight lines", error = false), holder.notice)
    }

    @Test
    fun recordedTrackPlaysByItsTimingAtTheChipRate() {
        val commands = RecordingCommands()
        val holder = MapStateHolder(RecordingController(), LocalUiState(rate = 2), commands = commands)
        val route = Route(listOf(a, b), timestampsMillis = listOf(0L, 60_000L), name = "run")
        holder.loadTrack(Track("Morning run", "GPX", route.points, route))
        assertEquals(MapMode.Route, holder.local.mode)
        holder.start()
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
    fun locateOnlyMovesTheCameraInEveryMode() {
        for (mode in MapMode.entries) {
            val c = RecordingController()
            val commands = RecordingCommands()
            val holder = MapStateHolder(c, LocalUiState(mode = mode, draftRoute = listOf(a)), commands = commands)
            holder.locate { b }
            assertEquals(emptyList(), commands.locations, "$mode")
            assertEquals(emptyList(), c.calls, "$mode")
            assertEquals(mode, holder.local.mode)
            assertEquals(listOf(a), holder.local.draftRoute)
            assertNull(holder.local.lastPosition)
            assertNull(holder.local.cameraOverride)
            assertEquals(false, holder.local.locating)
            assertEquals(b, assertNotNull(holder.uiState.map.focus).target)
        }
    }

    @Test
    fun locatingTheSameSpotAgainIsANewFocus() {
        val holder = MapStateHolder(RecordingController())
        holder.locate { a }
        val first = assertNotNull(holder.shown.map.focus)
        holder.locate { a }
        val second = assertNotNull(holder.shown.map.focus)
        assertEquals(a, second.target)
        assertNotEquals(first, second)
    }

    @Test
    fun searchResultInRouteModeAddsAStopAndFocusesIt() {
        val commands = RecordingCommands()
        val holder = MapStateHolder(RecordingController(), LocalUiState(mode = MapMode.Route, draftRoute = listOf(a)), commands = commands)
        holder.pick(b, "Somewhere")
        assertEquals(listOf(a, b), holder.local.draftRoute)
        assertEquals(b, assertNotNull(holder.shown.map.focus).target)
        assertTrue(commands.locations.isEmpty())
    }

    @Test
    fun searchResultInPinModePicksAndFocusesIt() {
        val commands = RecordingCommands()
        val holder = MapStateHolder(RecordingController(), commands = commands)
        holder.pick(b, "Somewhere")
        assertTrue(!commands.any)
        assertEquals(b, holder.local.pendingSpot)
        assertEquals(b, assertNotNull(holder.shown.map.focus).target)
    }

    @Test
    fun longPressDoesNotMoveTheCamera() {
        val holder = MapStateHolder(RecordingController(), LocalUiState(mode = MapMode.Route), commands = RecordingCommands())
        holder.onMapLongPress(a)
        assertEquals(listOf(a), holder.local.draftRoute)
        assertNull(holder.shown.map.focus)
    }

    @Test
    fun joystickWithNoPositionWaitsInsteadOfStarting() {
        val c = RecordingController()
        val commands = RecordingCommands()
        val holder = MapStateHolder(c, LocalUiState(), commands = commands)
        holder.selectMode(MapMode.Joystick)
        holder.joystickInput(90.0, 1.0)
        holder.start()
        assertEquals(MapMode.Joystick, holder.local.mode)
        assertEquals(emptyList(), c.calls)
        assertTrue(!commands.any)
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
    fun joystickStartsFromTheCurrentFixOnStartOnly() {
        val c = RecordingController(HauntState.Holding(Fix(b, timeMillis = 0), "pin"))
        val commands = RecordingCommands()
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Joystick), commands = commands)
        holder.joystickInput(90.0, 1.0)
        assertTrue(c.calls.isEmpty() && !commands.any)
        holder.start()
        assertEquals(listOf(b), commands.joysticks)
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
    fun locateWhileARoutePlaysStopsFollowingTheGhost() {
        val c = RecordingController(moving())
        val holder = MapStateHolder(c, LocalUiState(mode = MapMode.Route, draftRoute = listOf(b)))
        holder.locate { b }
        assertEquals(listOf(b), holder.local.draftRoute)
        assertEquals(emptyList(), c.calls)
        assertEquals(b, holder.uiState.map.camera)
        assertEquals(b, assertNotNull(holder.uiState.map.focus).target)
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

/** Routes along "roads" by adding a detour point after every stop; can be made to fail. */
private class RoadCommands : HauntCommands {
    val routed = mutableListOf<List<LatLng>>()
    val played = mutableListOf<RouteRequest>()
    var failWith: Exception? = null

    override suspend fun setLocation(position: LatLng, accuracy: Float?, label: String?) = Unit

    override suspend fun playRoute(request: RouteRequest): RouteOutcome {
        played += request
        return RouteOutcome(request.routed ?: routeAlongRoads(request.route.points))
    }

    override val canFollowRoads: Boolean get() = true

    override suspend fun routeAlongRoads(stops: List<LatLng>): List<LatLng> {
        routed += stops
        failWith?.let { throw it }
        return stops.flatMapIndexed { i, p -> if (i == stops.lastIndex) listOf(p) else listOf(p, LatLng(p.lat + 0.001, p.lng)) }
    }
}

class RoadsPreviewTest {
    private val a = LatLng(4.2105, 73.5395)
    private val b = LatLng(4.2190, 73.5450)
    private val c = LatLng(4.2220, 73.5410)

    private fun TestScope.holder(commands: RoadCommands, local: LocalUiState = LocalUiState(mode = MapMode.Route)) =
        MapStateHolder(RecordingController(), local, commands = commands, scope = CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun editingStopsDrawsTheRoutedLineAfterAPause() = runTest {
        val commands = RoadCommands()
        val holder = holder(commands)
        holder.onMapLongPress(a)
        holder.onMapLongPress(b)
        assertEquals("Finding roads…", holder.uiState.route?.roadsNote)
        assertEquals(listOf(a, b), holder.uiState.map.route) // straight until the roads arrive
        holder.onMapLongPress(c) // within the debounce: only the last edit is routed
        advanceUntilIdle()
        assertEquals(listOf(listOf(a, b, c)), commands.routed)
        assertEquals(5, holder.uiState.map.route.size)
        assertNull(holder.uiState.route?.roadsNote)
        assertTrue(Geo.length(holder.uiState.map.route) > Geo.length(listOf(a, b, c)))
    }

    @Test
    fun turningFollowRoadsOffShowsStraightLinesAndOnRoutesAgainOnlyIfNeeded() = runTest {
        val commands = RoadCommands()
        val holder = holder(commands, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)))
        advanceUntilIdle()
        assertEquals(3, holder.uiState.map.route.size)
        holder.setFollowRoads(false)
        assertEquals(listOf(a, b), holder.uiState.map.route)
        holder.setFollowRoads(true)
        advanceUntilIdle()
        assertEquals(3, holder.uiState.map.route.size)
        assertEquals(1, commands.routed.size) // the cached line was still good
    }

    @Test
    fun playUsesThePreviewedLineAndDoesNotStartWhilePreviewing() = runTest {
        val commands = RoadCommands()
        val holder = holder(commands, LocalUiState(mode = MapMode.Route, draftRoute = listOf(a, b)))
        advanceUntilIdle()
        assertTrue(commands.played.isEmpty())
        holder.playPause()
        advanceUntilIdle()
        val req = commands.played.single()
        assertEquals(3, req.routed?.size)
        assertEquals(1, commands.routed.size)
    }

    @Test
    fun routingFailureIsShownNotHidden() = runTest {
        val commands = RoadCommands().apply { failWith = CommandException("Couldn't follow roads: can't reach the routing server", "hint") }
        val holder = holder(commands)
        holder.onMapLongPress(a)
        holder.onMapLongPress(b)
        advanceUntilIdle()
        val route = assertNotNull(holder.uiState.route)
        assertEquals("Couldn't follow roads: can't reach the routing server", route.roadsNote)
        assertTrue(route.roadsFailed)
        assertEquals(Notice("Couldn't follow roads: can't reach the routing server", MapStateHolder.ROADS_HINT), holder.notice)
        assertEquals(listOf(a, b), holder.uiState.map.route)
    }

    @Test
    fun recordedTracksAndOtherModesAreNotRouted() = runTest {
        val commands = RoadCommands()
        val holder = holder(commands, LocalUiState(mode = MapMode.Pin, draftRoute = listOf(a, b)))
        advanceUntilIdle()
        holder.loadTrack(Track("run", "GPX", listOf(a, b), Route(listOf(a, b), timestampsMillis = listOf(0L, 60_000L))))
        advanceUntilIdle()
        assertTrue(commands.routed.isEmpty())
        assertNull(holder.uiState.route?.roadsNote)
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
