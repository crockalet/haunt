package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.currentFix
import kotlin.math.roundToLong

/** Everything the map screen draws. Built by [buildMapUiState] from engine + local UI state. */
@Immutable
data class MapUiState(
    val mode: MapMode,
    val expanded: Boolean,
    /** Something is being faked right now (engine not idle). */
    val active: Boolean,
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
    val canPlay: Boolean,
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
    /** Where the camera should be; changes when the map should follow. */
    val camera: LatLng?,
    /** True when the camera should keep following [camera]. */
    val follow: Boolean,
)

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
    val speedPreset: SpeedPreset = SpeedPreset.Walk,
    val customSpeed: Speed = Speed.kmh(30.0),
    val followRoads: Boolean = true,
    val loop: LoopMode = LoopMode.Once,
    val rate: Int = 1,
    val joystickMaxKmh: Float = 12f,
    val joystickBearing: Double = 0.0,
    val joystickMagnitude: Double = 0.0,
    val activeSinceMillis: Long? = null,
    val trail: List<LatLng> = emptyList(),
    val lastPosition: LatLng? = null,
    /** Camera target set by "Show on map"; cleared when something is haunted. */
    val cameraOverride: LatLng? = null,
    /** The locate button is waiting for the device's real location. */
    val locating: Boolean = false,
)

fun LocalUiState.presetSpeed(): Speed = speedPreset.speed ?: customSpeed

/** Pure mapping from engine state + local UI state to [MapUiState]. */
fun buildMapUiState(
    engine: HauntState,
    local: LocalUiState,
    defaults: HauntDefaults = HauntDefaults(),
): MapUiState {
    val fix = engine.currentFix
    val active = engine !is HauntState.Idle
    val position = fix?.position ?: local.lastPosition

    val pin = if (local.mode == MapMode.Pin) {
        val title = when (engine) {
            is HauntState.Holding -> engine.label ?: "Dropped pin"
            is HauntState.Moving -> engine.routeName ?: "On a route"
            is HauntState.Joystick -> "Joystick"
            HauntState.Idle -> if (position != null) "Last location" else "Nowhere yet"
        }
        val elapsed = if (fix != null && local.activeSinceMillis != null) {
            (fix.timeMillis - local.activeSinceMillis).coerceAtLeast(0) / 1000
        } else null
        PinDetails(
            title = title,
            coordinates = position?.let { Format.coords(it) } ?: "Long-press the map to drop a pin",
            position = position,
            altitude = fix?.altitude?.let { "${it.roundToLong()} m" } ?: "—",
            accuracy = "±${(fix?.accuracy ?: defaults.accuracyMeters).roundToLong()} m",
            updates = "${defaults.updateRateHz} Hz",
            footer = if (active && elapsed != null) {
                "Haunting for ${Format.duration(elapsed)} · GPS, network & fused"
            } else {
                "Not haunting · long-press the map to drop a pin"
            },
        )
    } else null

    val route = if (local.mode == MapMode.Route) {
        val moving = engine as? HauntState.Moving
        val total = moving?.progress?.totalMeters ?: Geo.length(local.draftRoute)
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
            canPlay = moving != null || local.draftRoute.size >= 2,
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
        )
    } else null

    val status: StatusUi = when {
        local.mode == MapMode.Route && route != null && (engine is HauntState.Moving || local.draftRoute.size >= 2) ->
            StatusUi.Progress(route.fraction, route.distance, route.eta)
        local.mode == MapMode.Route -> StatusUi.Message("Long-press the map to add stops", active = false)
        local.mode == MapMode.Joystick && joystick != null ->
            StatusUi.Joystick(joystick.directionLabel, Format.kmh(local.joystickMaxKmh / 3.6))
        engine is HauntState.Holding -> StatusUi.Message("Haunting · ${engine.label ?: Format.coords(engine.fix.position, 4)}", active = true)
        engine is HauntState.Moving -> StatusUi.Message("Haunting · ${engine.routeName ?: "route"}", active = true)
        else -> StatusUi.Message("Long-press the map to haunt a spot", active = false)
    }

    val moving = engine as? HauntState.Moving
    val routePoints = when {
        local.mode != MapMode.Route -> emptyList()
        moving != null && (local.playingRoute?.size ?: 0) >= 2 -> local.playingRoute!!
        else -> local.draftRoute
    }
    val traveledPoints = if (moving != null && routePoints.size >= 2) {
        Geo.split(routePoints, moving.progress.traveledMeters).first
    } else emptyList()

    return MapUiState(
        mode = local.mode,
        expanded = local.expanded,
        active = active,
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
            camera = local.cameraOverride ?: position,
            follow = local.cameraOverride != null || engine is HauntState.Moving || engine is HauntState.Joystick,
        ),
    )
}
