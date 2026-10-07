package io.github.crockalet.haunt.ui.state

import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed

/** What the map screen asks for when it starts playing a route. */
data class RouteRequest(
    val route: Route,
    /** Constant speed; ignored while a timed track is replayed by its timestamps. */
    val speed: Speed,
    val loop: LoopMode,
    /** Snap the stops to roads (waypoint routes only). */
    val followRoads: Boolean,
    /** Non-null for a recorded track: replay it by its timestamps at this multiplier. */
    val playbackRate: Double?,
    /** Road-following line already fetched for these stops (the map's preview); routed again when null. */
    val routed: List<LatLng>? = null,
)

/** What was actually played: the (possibly road-following) polyline and an optional warning. */
data class RouteOutcome(val points: List<LatLng>, val warning: String? = null)

/** A command failed; [hint] tells the user how to fix it. Shown as a [Notice]. */
class CommandException(message: String, val hint: String? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * The commands that *start* faking a location; the map screen only sends them when the user presses
 * Start. They come here instead of straight to the controller so the app can run its checks first
 * (mock-app selected, permissions), geocode, route along roads and start its service. Fine-grained,
 * live controls (pause, speed, stick input) still go to the [HauntController].
 *
 * Implementations throw [CommandException] (or any exception; its message is shown).
 */
interface HauntCommands {
    suspend fun setLocation(position: LatLng, accuracy: Float?, label: String?)

    suspend fun playRoute(request: RouteRequest): RouteOutcome

    suspend fun startJoystick(maxSpeed: Speed, from: LatLng)

    /** Whether [routeAlongRoads] works; without it the map previews straight lines between stops. */
    val canFollowRoads: Boolean get() = false

    /**
     * The road-following line through [stops], for the map's preview while stops are edited.
     * Throws (with a message worth showing) when no route could be computed.
     */
    suspend fun routeAlongRoads(stops: List<LatLng>): List<LatLng> = stops
}

/** Calls the controller directly (previews, tests, desktop). Straight lines only. */
class ControllerCommands(private val controller: HauntController) : HauntCommands {
    override suspend fun setLocation(position: LatLng, accuracy: Float?, label: String?) {
        controller.setLocation(position, accuracy = accuracy, label = label)
    }

    override suspend fun playRoute(request: RouteRequest): RouteOutcome {
        request.playbackRate?.let(controller::setPlaybackRate)
        controller.playRoute(request.route, request.speed, request.loop)
        return RouteOutcome(request.route.points)
    }

    override suspend fun startJoystick(maxSpeed: Speed, from: LatLng) {
        controller.startJoystick(maxSpeed, from)
    }
}

/** Mirrors the engine's rule for replaying a route by its recorded timestamps. */
val Route.isTimed: Boolean
    get() {
        val ts = timestampsMillis ?: return false
        if (ts.size != points.size || ts.size < 2) return false
        for (i in 1 until ts.size) if (ts[i] < ts[i - 1]) return false
        return ts.last() > ts.first()
    }
