package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.currentFix

/**
 * State holder (presenter) for the map screen. Owns UI-only state ([LocalUiState]) and turns user
 * intents into [HauntController] calls. Engine state is the source of truth for what is being
 * faked; this class only remembers what the engine doesn't (mode, expanded card, presets, draft
 * route…). Call [observe] once from composition (done by `HauntApp`) and read [uiState].
 */
@Stable
class MapStateHolder(
    val controller: HauntController,
    initial: LocalUiState = LocalUiState(),
    defaults: HauntDefaults = HauntDefaults(),
) {
    var local by mutableStateOf(initial)
        private set
    var defaults by mutableStateOf(defaults)

    /** Current UI state; reads engine state from [HauntController.state]. */
    val uiState: MapUiState
        get() = buildMapUiState(controller.state.value, local, defaults)

    /** Track engine transitions that matter to the UI (active-since timer, joystick trail). */
    fun onEngineState(state: HauntState) {
        val fix = state.currentFix
        var l = local
        l = when {
            state is HauntState.Idle -> l.copy(activeSinceMillis = null)
            l.activeSinceMillis == null && fix != null -> l.copy(activeSinceMillis = fix.timeMillis)
            else -> l
        }
        if (fix != null) {
            l = l.copy(lastPosition = fix.position)
            if (state is HauntState.Joystick) {
                val last = l.trail.lastOrNull()
                if (last == null || Geo.distance(last, fix.position) > 3.0) l = l.copy(trail = (l.trail + fix.position).takeLast(200))
            } else if (l.trail.isNotEmpty()) {
                l = l.copy(trail = emptyList())
            }
        }
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
        if (mode == MapMode.Joystick) {
            controller.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from = engine.currentFix?.position ?: local.lastPosition)
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
            MapMode.Pin -> controller.setLocation(position, accuracy = defaults.accuracyMeters, label = "Dropped pin")
            MapMode.Route -> addStop(position)
            MapMode.Joystick -> controller.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from = position)
        }
    }

    /** Haunt a place (from search, library…): switches to Pin mode. */
    fun hauntAt(position: LatLng, label: String?) {
        if (local.mode == MapMode.Route && controller.state.value !is HauntState.Moving) {
            addStop(position)
            return
        }
        local = local.copy(mode = MapMode.Pin, cameraOverride = null)
        controller.setLocation(position, accuracy = defaults.accuracyMeters, label = label)
    }

    /** Move the camera to [position] without haunting it. */
    fun showOnMap(position: LatLng) {
        local = local.copy(cameraOverride = position)
    }

    // --- Route -------------------------------------------------------------------------------

    fun addStop(position: LatLng) {
        local = local.copy(mode = MapMode.Route, draftRoute = local.draftRoute + position, draftName = null)
    }

    fun clearDraft() {
        local = local.copy(draftRoute = emptyList(), draftName = null)
    }

    /** Load a whole route (e.g. a GPX track from the library) into Route mode. */
    fun loadRoute(points: List<LatLng>, name: String?) {
        local = local.copy(mode = MapMode.Route, draftRoute = points, draftName = name)
    }

    /** Pause / resume, or start the draft route when nothing is playing. */
    fun playPause() {
        when (val s = controller.state.value) {
            is HauntState.Moving -> if (s.paused) controller.resume() else controller.pause()
            else -> if (local.draftRoute.size >= 2) {
                controller.playRoute(Route(local.draftRoute, name = local.draftName), routeSpeed(), local.loop)
            }
        }
    }

    fun setSpeedPreset(preset: SpeedPreset) {
        local = local.copy(speedPreset = preset)
        if (controller.state.value is HauntState.Moving) controller.setSpeed(routeSpeed())
    }

    fun setFollowRoads(follow: Boolean) {
        local = local.copy(followRoads = follow)
    }

    fun setLoop(loop: LoopMode) {
        local = local.copy(loop = loop)
        val s = controller.state.value
        if (s is HauntState.Moving && s.loop != loop) {
            // Loop mode is fixed per playback in the engine API; restart from the current fix.
            controller.playRoute(Route(local.draftRoute, name = local.draftName), routeSpeed(), loop)
        }
    }

    /** Cycles playback rate 1× → 2× → 4× → 1×. */
    fun cycleRate() {
        local = local.copy(rate = when (local.rate) { 1 -> 2; 2 -> 4; else -> 1 })
        if (controller.state.value is HauntState.Moving) controller.setSpeed(routeSpeed())
    }

    private fun routeSpeed() = Speed(local.presetSpeed().metersPerSecond * local.rate)

    // --- Joystick ----------------------------------------------------------------------------

    fun joystickInput(bearingDeg: Double, magnitude: Double) {
        local = local.copy(joystickBearing = bearingDeg, joystickMagnitude = magnitude)
        if (controller.state.value !is HauntState.Joystick) {
            controller.startJoystick(Speed.kmh(local.joystickMaxKmh.toDouble()), from = local.lastPosition)
        }
        controller.joystickInput(bearingDeg, magnitude)
    }

    fun setJoystickMaxSpeed(kmh: Float) {
        local = local.copy(joystickMaxKmh = kmh)
        if (controller.state.value is HauntState.Joystick) controller.setSpeed(Speed.kmh(kmh.toDouble()))
    }

    // --- Stop --------------------------------------------------------------------------------

    fun stop() {
        controller.stop()
    }
}

/** Remembers a [MapStateHolder] for [controller] and keeps it in sync with engine state. */
@Composable
fun rememberMapStateHolder(
    controller: HauntController,
    initial: LocalUiState = LocalUiState(),
    defaults: HauntDefaults = HauntDefaults(),
): MapStateHolder {
    val holder = remember(controller) { MapStateHolder(controller, initial, defaults) }
    val engine by controller.state.collectAsState()
    LaunchedEffect(holder, engine) { holder.onEngineState(engine) }
    return holder
}


/** Collects engine state and returns the current [MapUiState]. */
@Composable
fun MapStateHolder.collectUiState(): MapUiState {
    val engine by controller.state.collectAsState()
    return buildMapUiState(engine, local, defaults)
}
