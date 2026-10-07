package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.Travel
import io.github.crockalet.haunt.core.currentFix
import kotlin.math.roundToLong

/** Everything the map screen draws. Built by [buildMapUiState] from engine + local UI state. */
@Immutable
data class MapUiState(
    val mode: MapMode,
    val expanded: Boolean,
    /** Something is being faked right now (engine not idle). */
    val active: Boolean,
    /**
     * What Start would do in the selected mode ("Start route"…), when that differs from what is
     * running; null when there is nothing to start. Nothing is faked until the user presses Start.
     */
    val startAction: String?,
    val status: StatusUi,
    val searchPlaceholder: String,
    val pin: PinDetails?,
    val route: RouteDetails?,
    val joystick: JoystickDetails?,
    val map: MapContent,
    /** The locate button is waiting for a real fix. */
    val locating: Boolean = false,
)

/** Content of the status chip under the search pill (collapsed state only). */
@Immutable
sealed interface StatusUi {
    data class Message(val text: String, val active: Boolean) : StatusUi
    data class Progress(val fraction: Float, val distance: String, val eta: String?) : StatusUi
    data class Joystick(val direction: String, val speed: String) : StatusUi
}

@Immutable
data class PinDetails(
    val title: String,
    val coordinates: String,
    val position: LatLng?,
    val altitude: String,
    val accuracy: String,
    val updates: String,
    val footer: String,
)

@Immutable
data class RouteDetails(
    val title: String,
    val fraction: Float,
    val distance: String,
    val eta: String?,
    val speedPreset: SpeedPreset,
    val speedLabel: String,
    val followRoads: Boolean,
    val loop: LoopMode,
    /** Rate chip text ("1×"); the recorded-track multiplier while one plays by its timestamps. */
    val rateLabel: String,
    /** Value of the Custom speed editor (km/h). */
    val customKmh: Float,
    val playing: Boolean,
    /** A route is playing or paused: the toolbar shows pause / resume. */
    val started: Boolean,
    /** Under the Follow roads switch: "Finding roads…" or why routing failed; null when there's nothing to say. */
    val roadsNote: String? = null,
)

@Immutable
data class JoystickDetails(
    val heading: String,
    val speed: String,
    val moved: String,
    val maxSpeedKmh: Float,
    val directionLabel: String,
    val bearingDeg: Double,
    val magnitude: Double,
    val size: JoystickSize = JoystickSize.Medium,
    val floating: Boolean = false,
    /** Pad offset from its default spot, in dp. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** The engine is in joystick mode, so the pad steers; otherwise it waits for Start (faded knob, no input). */
    val live: Boolean = true,
    /** [floating] is on but Android doesn't allow drawing over other apps yet. */
    val floatingNeedsPermission: Boolean = false,
)

/** What the map layer shows. */
@Immutable
data class MapContent(
    val fix: LatLng?,
    val accuracyMeters: Float?,
    val moving: Boolean,
    val showHalo: Boolean,
    /** Route shown on the map (playing route, or the draft being built). */
    val route: List<LatLng>,
    /** Travelled part of [route] (solid); the rest is drawn dotted. */
    val traveled: List<LatLng>,
    /** Trail behind the joystick ghost (dotted). */
    val trail: List<LatLng>,
    /** Spot picked for Start (pin or joystick start) but not haunted yet. */
    val pending: LatLng? = null,
    /** Where the camera should be; changes when the map should follow. */
    val camera: LatLng?,
    /** True when the camera should keep following [camera]. */
    val follow: Boolean,
    /** Latest one-off camera move; the map animates to it once per new [CameraFocus.id]. */
    val focus: CameraFocus? = null,
)

/** A request to move the camera to [target] once, without following it. [id] makes asking again for the same spot a new request. */
@Immutable
data class CameraFocus(val target: LatLng, val id: Int)

/** UI-only state the engine doesn't know about (mode, card, presets…). */
@Immutable
data class LocalUiState(
    val mode: MapMode = MapMode.Pin,
    val expanded: Boolean = false,
    val draftRoute: List<LatLng> = emptyList(),
    val draftName: String? = null,
    /** Full track behind the draft when it came from the library (timestamps, altitudes). */
    val draftTrack: Route? = null,
    /** Polyline actually being played (road-following), drawn instead of the stops while moving. */
    val playingRoute: List<LatLng>? = null,
    /** Road-following line for the draft's stops while Follow roads is on (see [currentRoads]). */
    val roads: RoadsPreview? = null,
    val speedPreset: SpeedPreset = SpeedPreset.Walk,
    val customSpeed: Speed = Speed.kmh(30.0),
    val followRoads: Boolean = true,
    val loop: LoopMode = LoopMode.Once,
    val rate: Int = 1,
    val joystickMaxKmh: Float = 12f,
    /** Where the pad's knob rests (previews). Live stick input goes straight to the engine, not here. */
    val joystickBearing: Double = 0.0,
    val joystickMagnitude: Double = 0.0,
    val activeSinceMillis: Long? = null,
    val trail: List<LatLng> = emptyList(),
    /**
     * Where the ghost was last: the first fix seen, the locate button's result, and the last fix
     * when the engine goes idle. Not updated on every fix; while active the engine's fix wins.
     */
    val lastPosition: LatLng? = null,
    /** Camera target set by "Show on map"; cleared when something is haunted. */
    val cameraOverride: LatLng? = null,
    /** Spot picked on the map, in search or the library: Pin mode haunts it, Joystick mode starts there, on Start. */
    val pendingSpot: LatLng? = null,
    val pendingLabel: String? = null,
    /** Last camera move asked for by the locate button, a search result or a new stop. */
    val cameraFocus: CameraFocus? = null,
    /** The locate button is waiting for the device's real location. */
    val locating: Boolean = false,
)

fun LocalUiState.presetSpeed(): Speed = speedPreset.speed ?: customSpeed

/** Picked by the preset, not the playback rate: walking at 4× still routes on footpaths. */
val LocalUiState.travel: Travel get() = Travel.forSpeed(presetSpeed())

/** Road-following line through [stops]: loading while both [points] and [error] are null. */
@Immutable
data class RoadsPreview(
    val stops: List<LatLng>,
    val travel: Travel,
    val points: List<LatLng>? = null,
    val error: String? = null,
)

/** The draft's stops get routed along roads (hand-placed stops only; recorded tracks keep their path). */
val LocalUiState.followsRoads: Boolean
    get() = followRoads && draftTrack == null && draftRoute.size >= 2

/** [LocalUiState.roads] if it's for the current stops and Follow roads is on. */
fun LocalUiState.currentRoads(): RoadsPreview? = roads?.takeIf { followsRoads && it.stops == draftRoute && it.travel == travel }

/** Pure mapping from engine state + local UI state to [MapUiState]. */
fun buildMapUiState(
    engine: HauntState,
    local: LocalUiState,
    defaults: HauntDefaults = HauntDefaults(),
    canDrawOverlays: Boolean = true,
): MapUiState = buildMapUiState(engine, local, defaults, ::MeasuredLine, canDrawOverlays)

/** [buildMapUiState] with [measure] supplying (typically cached) route measurements. */
internal fun buildMapUiState(
    engine: HauntState,
    local: LocalUiState,
    defaults: HauntDefaults,
    measure: (List<LatLng>) -> MeasuredLine,
    canDrawOverlays: Boolean = true,
): MapUiState {
    val fix = engine.currentFix
    val active = engine !is HauntState.Idle
    val position = fix?.position ?: local.lastPosition
    val pending = local.pendingSpot.takeIf { local.mode != MapMode.Route }

    val startAction = when (local.mode) {
        MapMode.Pin -> when {
            pending != null -> "Haunt this spot"
            !active && position != null -> "Haunt last location"
            else -> null
        }
        MapMode.Route -> "Start route".takeIf { engine !is HauntState.Moving && local.draftRoute.size >= 2 }
        MapMode.Joystick -> "Start joystick".takeIf {
            (engine !is HauntState.Joystick || pending != null) && (pending ?: position) != null
        }
    }

    val pin = if (local.mode == MapMode.Pin) {
        val title = when {
            pending != null -> local.pendingLabel ?: "Dropped pin"
            else -> when (engine) {
                is HauntState.Holding -> engine.label ?: "Dropped pin"
                is HauntState.Moving -> engine.routeName ?: "On a route"
                is HauntState.Joystick -> "Joystick"
                HauntState.Idle -> if (position != null) "Last location" else "Nowhere yet"
            }
        }
        val elapsed = if (fix != null && local.activeSinceMillis != null) {
            (fix.timeMillis - local.activeSinceMillis).coerceAtLeast(0) / 1000
        } else null
        val shownAt = pending ?: position
        PinDetails(
            title = title,
            coordinates = shownAt?.let { Format.coords(it) } ?: "Long-press the map to pick a spot",
            position = shownAt,
            altitude = fix?.altitude?.takeIf { pending == null }?.let { "${it.roundToLong()} m" } ?: "—",
            accuracy = "±${(fix?.accuracy ?: defaults.accuracyMeters).roundToLong()} m",
            updates = "${defaults.updateRateHz} Hz",
            footer = when {
                pending != null && active -> "Still haunting the old spot · press Start to move here"
                pending != null -> "Not haunting yet · press Start to haunt this spot"
                active && elapsed != null -> "Haunting for ${Format.duration(elapsed)} · GPS, network & fused"
                else -> "Not haunting · long-press the map to pick a spot"
            },
        )
    } else null

    val roads = local.currentRoads()
    val draftLine = roads?.points ?: local.draftRoute
    val route = if (local.mode == MapMode.Route) {
        val moving = engine as? HauntState.Moving
        val total = moving?.progress?.totalMeters ?: measure(draftLine).length
        val traveled = moving?.progress?.traveledMeters ?: 0.0
        val speed = moving?.speed ?: local.presetSpeed()
        RouteDetails(
            title = moving?.routeName ?: local.draftName ?: when (local.draftRoute.size) {
                0 -> "New route"
                1 -> "1 stop"
                else -> "${local.draftRoute.size} stops"
            },
            fraction = if (total > 0) (traveled / total).toFloat() else 0f,
            distance = Format.progress(traveled, total),
            eta = moving?.progress?.etaSeconds?.let { "ETA ${Format.duration(it)}" }
                ?: if (total > 0) "ETA ${Format.duration((total / speed.metersPerSecond).roundToLong())}" else null,
            speedPreset = local.speedPreset,
            speedLabel = Format.kmh(local.presetSpeed().metersPerSecond),
            followRoads = local.followRoads,
            loop = moving?.loop ?: local.loop,
            rateLabel = Format.rate(moving?.playbackRate ?: local.rate.toDouble()),
            customKmh = local.customSpeed.kmh.toFloat(),
            playing = moving != null && !moving.paused,
            started = moving != null,
            roadsNote = roads?.let { it.error ?: if (it.points == null) "Finding roads…" else null },
        )
    } else null

    val joystick = if (local.mode == MapMode.Joystick) {
        val j = engine as? HauntState.Joystick
        val heading = j?.headingDeg ?: local.joystickBearing
        val speedMs = fix?.speed?.toDouble() ?: (local.joystickMagnitude * local.joystickMaxKmh / 3.6)
        JoystickDetails(
            heading = "${Format.compass(heading)} ${heading.roundToLong()}°",
            speed = Format.kmh(speedMs),
            moved = Format.distance(j?.distanceMeters ?: 0.0),
            maxSpeedKmh = local.joystickMaxKmh,
            directionLabel = Format.compass(heading),
            bearingDeg = local.joystickBearing,
            magnitude = local.joystickMagnitude,
            size = defaults.joystickSize,
            floating = defaults.floatingJoystick,
            offsetX = defaults.joystickOffsetX,
            offsetY = defaults.joystickOffsetY,
            live = j != null,
            floatingNeedsPermission = defaults.floatingJoystick && !canDrawOverlays,
        )
    } else null

    // What is running comes first, so switching mode never hides an active spoof.
    val status: StatusUi = when {
        local.mode == MapMode.Route && route != null && engine is HauntState.Moving ->
            StatusUi.Progress(route.fraction, route.distance, route.eta)
        local.mode == MapMode.Joystick && joystick != null && engine is HauntState.Joystick ->
            StatusUi.Joystick(joystick.directionLabel, Format.kmh(local.joystickMaxKmh / 3.6))
        engine is HauntState.Holding -> StatusUi.Message("Haunting · ${engine.label ?: Format.coords(engine.fix.position, 4)}", active = true)
        engine is HauntState.Moving -> StatusUi.Message("Haunting · ${engine.routeName ?: "route"}", active = true)
        engine is HauntState.Joystick -> StatusUi.Message("Haunting · joystick", active = true)
        local.mode == MapMode.Route && route != null && local.draftRoute.size >= 2 ->
            StatusUi.Progress(route.fraction, route.distance, route.eta)
        local.mode == MapMode.Route -> StatusUi.Message("Long-press the map to add stops", active = false)
        local.mode == MapMode.Joystick && startAction == null ->
            StatusUi.Message("Long-press the map to place the joystick", active = false)
        pending != null || local.mode == MapMode.Joystick -> StatusUi.Message("Ready · press Start", active = false)
        else -> StatusUi.Message("Long-press the map to pick a spot", active = false)
    }

    val moving = engine as? HauntState.Moving
    val routePoints = when {
        local.mode != MapMode.Route -> emptyList()
        moving != null && (local.playingRoute?.size ?: 0) >= 2 -> local.playingRoute!!
        else -> draftLine
    }
    val traveledPoints = if (moving != null && routePoints.size >= 2) {
        measure(routePoints).traveled(moving.progress.traveledMeters)
    } else emptyList()

    return MapUiState(
        mode = local.mode,
        expanded = local.expanded,
        active = active,
        startAction = startAction,
        status = status,
        searchPlaceholder = if (local.mode == MapMode.Route) "Add a stop" else "Search or paste coordinates",
        pin = pin,
        route = route,
        joystick = joystick,
        locating = local.locating,
        map = MapContent(
            fix = position,
            accuracyMeters = fix?.accuracy,
            moving = engine is HauntState.Moving || engine is HauntState.Joystick,
            showHalo = local.mode == MapMode.Pin && position != null,
            route = routePoints,
            traveled = traveledPoints,
            trail = if (local.mode == MapMode.Joystick) local.trail else emptyList(),
            pending = pending,
            camera = local.cameraOverride ?: pending ?: position,
            follow = local.cameraOverride != null || pending != null || engine is HauntState.Moving || engine is HauntState.Joystick,
            focus = local.cameraFocus,
        ),
    )
}

/** A polyline with its running lengths, so cutting it on every engine tick doesn't re-measure the whole route. */
internal class MeasuredLine(val points: List<LatLng>) {
    private val cumulative = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val length: Double get() = if (points.isEmpty()) 0.0 else cumulative[points.lastIndex]

    /** The part up to [meters] along the line, ending at the cut point: `Geo.split(points, meters).first`. */
    fun traveled(meters: Double): List<LatLng> {
        if (points.size < 2) return points
        val m = meters.coerceAtLeast(0.0)
        if (m > length) return points
        var lo = 0
        var hi = points.lastIndex - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid + 1] >= m) hi = mid else lo = mid + 1
        }
        val seg = cumulative[lo + 1] - cumulative[lo]
        val cut = if (seg == 0.0) points[lo] else Geo.interpolate(points[lo], points[lo + 1], (m - cumulative[lo]) / seg)
        return points.subList(0, lo + 1) + cut
    }
}
