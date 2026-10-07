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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * State holder (presenter) for the map screen. Owns UI-only state ([LocalUiState]) and turns user
 * intents into [HauntController] calls. Engine state is the source of truth for what is being
 * faked; this class only remembers what the engine doesn't (mode, expanded card, presets, draft
 * route…). Feed it engine states with [onEngineState] (done by `rememberHauntAppState`) and read [shown]
 * from composition; [uiState] builds the same from the controller's current state.
 *
 * Starting to fake a location (pin, place, route) goes through [commands], which may suspend and
 * fail (permissions, mock app not selected…); failures become a [notice]. Live tweaks (pause,
 * speed, rate, loop, joystick) go straight to the [controller].
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
    var local by mutableStateOf(initial)
        private set
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
        val engine = controller.state.value
        if (local.mode == MapMode.Joystick && engine is HauntState.Joystick) {
            // Leaving joystick: hold where we are.
            controller.setLocation(engine.fix.position, label = "Joystick stop")
        }
        local = local.copy(mode = mode)
        val start = engine.currentFix?.position ?: local.lastPosition
        // With nowhere to start from, wait for a long-press or locate to place the stick.
        if (mode == MapMode.Joystick && start != null) {
            controller.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from = start)
        }
    }

    fun toggleExpanded() {
        local = local.copy(expanded = !local.expanded)
    }

    fun setExpanded(expanded: Boolean) {
        local = local.copy(expanded = expanded)
    }

    // --- Pin ---------------------------------------------------------------------------------

    /** Long-press on the map. Pin mode: haunt there. Route mode: add a stop. */
    fun onMapLongPress(position: LatLng) {
        local = local.copy(cameraOverride = null)
        when (local.mode) {
            MapMode.Pin -> launchCommand { commands.setLocation(position, defaults.accuracyMeters, "Dropped pin") }
            MapMode.Route -> addStop(position)
            MapMode.Joystick -> controller.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from = position)
        }
    }

    /**
     * Haunt a place (from search, library…) and move the camera to it: switches to Pin mode, or adds
     * it as a stop in Route mode while no route plays.
     */
    fun hauntAt(position: LatLng, label: String?) {
        focus(position)
        if (local.mode == MapMode.Route && controller.state.value !is HauntState.Moving) {
            addStop(position)
            return
        }
        local = local.copy(mode = MapMode.Pin, cameraOverride = null)
        launchCommand { commands.setLocation(position, defaults.accuracyMeters, label) }
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

    /** Pause / resume, or start the draft route when nothing is playing. */
    fun playPause() {
        when (val s = controller.state.value) {
            is HauntState.Moving -> if (s.paused) controller.resume() else controller.pause()
            else -> if (local.draftRoute.size >= 2) startRoute()
        }
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
        )
        launchCommand {
            val outcome = commands.playRoute(request)
            local = local.copy(playingRoute = outcome.points)
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

    /** Live stick input. Goes straight to the engine: the pad draws its own knob, so [local] isn't touched. */
    fun joystickInput(bearingDeg: Double, magnitude: Double) {
        val s = controller.state.value
        if (s !is HauntState.Joystick) {
            val start = s.currentFix?.position ?: lastPosition ?: return
            controller.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from = start)
        }
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
