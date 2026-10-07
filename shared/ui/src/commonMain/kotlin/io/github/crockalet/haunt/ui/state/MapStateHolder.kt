package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.currentFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * State holder (presenter) for the map screen. Owns UI-only state ([LocalUiState]) and turns user
 * intents into [HauntController] calls. Engine state is the source of truth for what is being
 * faked; this class only remembers what the engine doesn't (mode, expanded card, presets, draft
 * route…). Feed it engine states with [onEngineState] (done by `rememberHauntAppState`) and read [shown]
 * from composition; [uiState] builds the same from the controller's current state.
 *
 * Picking a spot, adding stops or switching mode only prepares: faking starts on [start] and ends
 * on [stop], never as a side effect. Switching mode leaves whatever runs untouched until Start.
 * Starting goes through [commands], which may suspend and fail (permissions, mock app not
 * selected…); failures become a [notice]. Live tweaks (pause, speed, rate, loop, stick input) of
 * what is running go straight to the [controller].
 *
 * @param scope runs [commands]; the default runs them synchronously until they suspend.
 */
@Stable
class MapStateHolder(
    val controller: HauntController,
    initial: LocalUiState = LocalUiState(),
    defaults: HauntDefaults = HauntDefaults(),
    private val commands: HauntCommands = ControllerCommands(controller),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) {
    private var localState by mutableStateOf(initial)

    var local: LocalUiState
        get() = localState
        private set(value) {
            localState = value
            refreshRoads(value)
        }
    var defaults by mutableStateOf(defaults)

    /** Banner over the map (errors with a hint, routing warnings…); null when nothing to say. */
    var notice by mutableStateOf<Notice?>(null)
        private set

    fun showNotice(notice: Notice) {
        this.notice = notice
    }

    fun dismissNotice() {
        notice = null
    }

    /** Engine state as of the last [onEngineState]; written together with [local] so a tick recomposes once. */
    private var engine by mutableStateOf(controller.state.value)

    /** Position of the newest fix seen; committed to [LocalUiState.lastPosition] when the engine goes idle. */
    private var lastFix: LatLng? = null

    private var measured: MeasuredLine? = null

    private fun measure(points: List<LatLng>): MeasuredLine =
        measured?.takeIf { it.points == points } ?: MeasuredLine(points).also { measured = it }

    /** Stops the road preview is (being) fetched for; null when none is wanted. */
    private var roadsTarget: List<LatLng>? = null
    private var roadsJob: Job? = null

    init {
        refreshRoads(initial)
    }

    private val shownState = derivedStateOf(structuralEqualityPolicy()) { buildMapUiState(engine, local, defaults, ::measure) }
    private val mapContentState = derivedStateOf(structuralEqualityPolicy()) { shownState.value.map }
    private val expandedState = derivedStateOf(structuralEqualityPolicy()) { local.expanded }

    /** Current UI state; reads engine state from [HauntController.state]. */
    val uiState: MapUiState
        get() = buildMapUiState(controller.state.value, local, defaults, ::measure)

    /** What the map screen shows (snapshot state): the last engine state passed to [onEngineState], plus [local]. */
    val shown: MapUiState
        get() = shownState.value

    /** Just the map layer's part of [shown]; only changes when what the map draws does. */
    val mapContent: MapContent
        get() = mapContentState.value

    /** The details card is open. */
    val expanded: Boolean
        get() = expandedState.value

    /** Where the ghost is, or was when it was last seen (snapshot state). */
    val lastPosition: LatLng?
        get() = engine.currentFix?.position ?: local.lastPosition

    /**
     * Track engine transitions that matter to the UI (active-since timer, joystick trail, last
     * position). A plain fix update while holding or on a route leaves [local] untouched.
     */
    fun onEngineState(state: HauntState) {
        val fix = state.currentFix
        var l = local
        l = when {
            state is HauntState.Idle -> l.copy(activeSinceMillis = null)
            l.activeSinceMillis == null && fix != null -> l.copy(activeSinceMillis = fix.timeMillis)
            else -> l
        }
        if (state !is HauntState.Moving && l.playingRoute != null) l = l.copy(playingRoute = null)
        if (fix == null) {
            lastFix?.let { if (l.lastPosition != it) l = l.copy(lastPosition = it) }
        } else {
            lastFix = fix.position
            if (l.lastPosition == null) l = l.copy(lastPosition = fix.position)
            if (state is HauntState.Joystick) {
                val last = l.trail.lastOrNull()
                if (last == null || Geo.distance(last, fix.position) > 3.0) l = l.copy(trail = (l.trail + fix.position).takeLast(200))
            } else if (l.trail.isNotEmpty()) {
                l = l.copy(trail = emptyList())
            }
        }
        engine = state
        if (l != local) local = l
    }

    // --- Mode & card -------------------------------------------------------------------------

    fun selectMode(mode: MapMode) {
        if (mode == local.mode) return
        local = local.copy(mode = mode)
    }

    fun toggleExpanded() {
        local = local.copy(expanded = !local.expanded)
    }

    fun setExpanded(expanded: Boolean) {
        local = local.copy(expanded = expanded)
    }

    // --- Pin ---------------------------------------------------------------------------------

    /** Long-press on the map. Pin mode: pick the spot. Route mode: add a stop. Joystick mode: where Start puts the stick. */
    fun onMapLongPress(position: LatLng) {
        local = local.copy(cameraOverride = null)
        when (local.mode) {
            MapMode.Pin, MapMode.Joystick -> pickSpot(position, "Dropped pin")
            MapMode.Route -> addStop(position)
        }
    }

    /**
     * A place from search or the library. Route mode (no route playing) adds it as a stop; otherwise
     * it becomes the pending spot in Pin mode. Moves the camera to it. Nothing is haunted until [start].
     */
    fun pick(position: LatLng, label: String?) {
        focus(position)
        if (local.mode == MapMode.Route && controller.state.value !is HauntState.Moving) {
            addStop(position)
            return
        }
        local = local.copy(mode = MapMode.Pin, cameraOverride = null)
        pickSpot(position, label)
    }

    private fun pickSpot(position: LatLng, label: String?) {
        local = local.copy(pendingSpot = position, pendingLabel = label)
    }

    /**
     * Locate button: asks [locateMe] for the device's real location and moves the camera there.
     * Never haunts anything. Ignored while one is running.
     */
    fun locate(locateMe: suspend () -> LatLng) {
        if (local.locating) return
        local = local.copy(locating = true)
        scope.launch {
            try {
                val position = locateMe()
                local = local.copy(locating = false)
                val engine = controller.state.value
                // Otherwise the camera would snap straight back to the moving ghost.
                if (engine is HauntState.Moving || engine is HauntState.Joystick) showOnMap(position)
                focus(position)
            } catch (e: CancellationException) {
                local = local.copy(locating = false)
                throw e
            } catch (e: Exception) {
                local = local.copy(locating = false)
                showNotice(e.toNotice())
            }
        }
    }

    /** Move the camera to [position] without haunting it. */
    fun showOnMap(position: LatLng) {
        local = local.copy(cameraOverride = position)
    }

    /** Animate the camera to [position] once, even if it was the last focus too. */
    private fun focus(position: LatLng) {
        local = local.copy(cameraFocus = CameraFocus(position, (local.cameraFocus?.id ?: 0) + 1))
    }

    // --- Route -------------------------------------------------------------------------------

    fun addStop(position: LatLng) {
        local = local.copy(mode = MapMode.Route, draftRoute = local.draftRoute + position, draftName = null, draftTrack = null)
    }

    fun clearDraft() {
        local = local.copy(draftRoute = emptyList(), draftName = null, draftTrack = null)
    }

    /** Load a whole route into Route mode. */
    fun loadRoute(points: List<LatLng>, name: String?) {
        local = local.copy(mode = MapMode.Route, draftRoute = points, draftName = name, draftTrack = null)
    }

    /**
     * Load a library track into Route mode. A recorded track ([Track.route] with timestamps) keeps
     * its timing and is replayed by it at the rate chip's multiplier.
     */
    fun loadTrack(track: Track) {
        val route = track.route?.takeIf { it.points.size >= 2 }
        local = local.copy(
            mode = MapMode.Route,
            draftRoute = route?.points ?: track.points,
            draftName = track.name,
            draftTrack = route?.copy(name = route.name ?: track.name),
        )
    }

    /** Pause / resume the playing route. */
    fun playPause() {
        val s = controller.state.value as? HauntState.Moving ?: return
        if (s.paused) controller.resume() else controller.pause()
    }

    // --- Start -------------------------------------------------------------------------------

    /**
     * The Start button: fakes what the selected mode has ready ([MapUiState.startAction]): the pending
     * spot (or the last location) in Pin mode, the draft in Route mode, the stick from the pending
     * spot or the current position in Joystick mode. Whatever ran before is replaced without a gap.
     */
    fun start() {
        val engine = controller.state.value
        if (buildMapUiState(engine, local, defaults, ::measure).startAction == null) return
        val pending = local.pendingSpot
        val position = engine.currentFix?.position ?: local.lastPosition
        when (local.mode) {
            MapMode.Pin -> {
                val label = if (pending != null) local.pendingLabel else null
                val at = pending ?: position ?: return
                launchCommand {
                    commands.setLocation(at, defaults.accuracyMeters, label)
                    clearPending(pending)
                }
            }
            MapMode.Route -> startRoute()
            MapMode.Joystick -> {
                val from = pending ?: position ?: return
                launchCommand {
                    commands.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from)
                    clearPending(pending)
                }
            }
        }
    }

    /** Forgets the pending spot once it is haunted, unless another one was picked meanwhile. */
    private fun clearPending(started: LatLng?) {
        if (started != null && local.pendingSpot == started) local = local.copy(pendingSpot = null, pendingLabel = null)
    }

    private fun startRoute() {
        val track = local.draftTrack
        val timed = track?.isTimed == true
        val request = RouteRequest(
            route = track ?: Route(local.draftRoute, name = local.draftName),
            speed = routeSpeed(),
            loop = local.loop,
            // Recorded tracks already follow their path; only hand-placed stops get routed.
            followRoads = local.followRoads && track == null,
            playbackRate = if (timed) local.rate.toDouble() else null,
            routed = local.currentRoads()?.points,
        )
        launchCommand {
            val outcome = commands.playRoute(request)
            val stops = request.route.points
            // Keep the routed line on the map once the route ends, too.
            val roads = if (request.followRoads && request.routed == null && local.draftRoute == stops) RoadsPreview(stops, outcome.points) else local.roads
            local = local.copy(playingRoute = outcome.points, roads = roads)
            outcome.warning?.let { showNotice(Notice(it, error = false)) }
        }
    }

    /** Speed presets switch the playing route (a timed track too) to that constant speed. */
    fun setSpeedPreset(preset: SpeedPreset) {
        local = local.copy(speedPreset = preset)
        if (controller.state.value is HauntState.Moving) controller.setSpeed(routeSpeed())
    }

    /** Value of the Custom speed editor, in whole km/h; applied live when Custom is the selected preset. */
    fun setCustomSpeed(kmh: Float) {
        val clamped = kmh.coerceIn(CustomSpeedRangeKmh.start, CustomSpeedRangeKmh.endInclusive).roundToInt()
        val speed = Speed.kmh(clamped.toDouble())
        // A drag reports every pixel; only whole km/h (what the label shows) are worth a recomposition.
        if (speed == local.customSpeed) return
        local = local.copy(customSpeed = speed)
        if (local.speedPreset == SpeedPreset.Custom && controller.state.value is HauntState.Moving) {
            controller.setSpeed(routeSpeed())
        }
    }

    fun setFollowRoads(follow: Boolean) {
        local = local.copy(followRoads = follow)
    }

    /**
     * Keeps [LocalUiState.roads] in step with the draft: whenever the stops (or Follow roads) change
     * in Route mode, fetch the road-following line after a short pause, cancelling any older fetch.
     */
    private fun refreshRoads(l: LocalUiState) {
        if (!commands.canFollowRoads) return
        val stops = l.draftRoute.takeIf { l.mode == MapMode.Route && l.followsRoads }
        if (stops == roadsTarget) return
        roadsTarget = stops
        roadsJob?.cancel()
        roadsJob = null
        if (stops == null || l.roads?.let { it.stops == stops && it.points != null } == true) return
        localState = l.copy(roads = RoadsPreview(stops))
        roadsJob = scope.launch {
            delay(ROADS_DEBOUNCE_MILLIS)
            val result = try {
                RoadsPreview(stops, points = commands.routeAlongRoads(stops))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RoadsPreview(stops, error = e.message ?: "Couldn't follow roads")
            }
            if (roadsTarget != stops) return@launch
            local = local.copy(roads = result)
            if (result.error != null) showNotice(Notice(result.error, ROADS_HINT))
        }
    }

    /** Changes the loop mode; a playing route keeps going from where it is. */
    fun setLoop(loop: LoopMode) {
        local = local.copy(loop = loop)
        val s = controller.state.value
        if (s is HauntState.Moving && s.loop != loop) controller.setLoopMode(loop)
    }

    /**
     * Cycles the rate 1× → 2× → 4× → 1×. A recorded track replayed by its timestamps gets it as its
     * playback rate; constant-speed playback multiplies the preset speed.
     */
    fun cycleRate() {
        local = local.copy(rate = when (local.rate) { 1 -> 2; 2 -> 4; else -> 1 })
        when (val s = controller.state.value) {
            is HauntState.Moving -> if (s.playbackRate != null) {
                controller.setPlaybackRate(local.rate.toDouble())
            } else {
                controller.setSpeed(routeSpeed())
            }
            else -> Unit
        }
    }

    private fun routeSpeed() = Speed(local.presetSpeed().metersPerSecond * local.rate)

    // --- Joystick ----------------------------------------------------------------------------

    /**
     * Live stick input. Goes straight to the engine: the pad draws its own knob, so [local] isn't
     * touched. Ignored until the joystick has been started.
     */
    fun joystickInput(bearingDeg: Double, magnitude: Double) {
        if (controller.state.value !is HauntState.Joystick) return
        controller.joystickInput(bearingDeg, magnitude)
    }

    /** Max speed slider, in whole km/h. */
    fun setJoystickMaxSpeed(kmh: Float) {
        val rounded = kmh.roundToInt().toFloat()
        if (rounded == local.joystickMaxKmh) return
        local = local.copy(joystickMaxKmh = rounded)
        if (controller.state.value is HauntState.Joystick) controller.setSpeed(Speed.kmh(rounded.toDouble()))
    }

    // --- Stop --------------------------------------------------------------------------------

    fun stop() {
        controller.stop()
    }

    private fun launchCommand(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice(e.toNotice())
            }
        }
    }

    companion object {
        /** Range of the Custom speed editor (km/h). */
        val CustomSpeedRangeKmh = 1f..150f

        /** Pause after the last stop edit before the road preview is fetched. */
        const val ROADS_DEBOUNCE_MILLIS = 400L

        const val ROADS_HINT = "Turn off Follow roads to use straight lines, or check the routing server in Settings"
    }
}

/** A failed command as a banner: [CommandException] hints are kept. */
fun Throwable.toNotice(): Notice = when (this) {
    is CommandException -> Notice(message ?: "Something went wrong", hint)
    else -> Notice(message ?: this::class.simpleName ?: "Something went wrong")
}

/** Remembers a [MapStateHolder] for [controller] and keeps it in sync with engine state. */
@Composable
fun rememberMapStateHolder(
    controller: HauntController,
    initial: LocalUiState = LocalUiState(),
    defaults: HauntDefaults = HauntDefaults(),
): MapStateHolder {
    val holder = remember(controller) { MapStateHolder(controller, initial, defaults) }
    // Collected outside composition so a tick doesn't recompose the caller.
    LaunchedEffect(holder) { controller.state.collect(holder::onEngineState) }
    return holder
}

/** The current [MapUiState] ([MapStateHolder.shown]); engine state arrives through [MapStateHolder.onEngineState]. */
@Composable
fun MapStateHolder.collectUiState(): MapUiState = shown
