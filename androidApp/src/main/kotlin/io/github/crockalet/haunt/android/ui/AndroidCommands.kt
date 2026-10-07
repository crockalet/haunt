package io.github.crockalet.haunt.android.ui

import io.github.crockalet.haunt.android.control.TrackedController
import io.github.crockalet.haunt.android.net.HttpException
import io.github.crockalet.haunt.android.net.Router
import io.github.crockalet.haunt.android.net.RoutingException
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.protocol.HauntApi
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.ui.state.CommandException
import io.github.crockalet.haunt.ui.state.HauntCommands
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.state.RouteOutcome
import io.github.crockalet.haunt.ui.state.RouteRequest
import io.github.crockalet.haunt.ui.state.isTimed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The map screen's commands on top of the app's [HauntApi], so the UI gets exactly the checks
 * agents get: mock-app selected and location permission ([RpcException] → [CommandException] with
 * the hint), and the foreground service is started.
 *
 * - Pins and places → `location.set`.
 * - Hand-placed stops → `route.play` with waypoints. With "Follow roads" the stops are routed here
 *   first (same [Router] the API uses; [routeAlongRoads] also feeds the map's preview) so the UI
 *   draws the line it plays. A routing failure is an error, not a silent straight-line fallback.
 * - Recorded tracks (timestamps or altitudes, which waypoints can't carry) → after the same
 *   [checkCanMock], straight to the tracked [controller] (route ids and finish events stay right).
 */
class AndroidCommands(
    private val api: HauntApi,
    private val controller: TrackedController,
    private val router: Router,
    private val checkCanMock: () -> Unit,
    private val onMockingStarted: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HauntCommands {

    override suspend fun setLocation(position: LatLng, accuracy: Float?, label: String?) {
        call { api.setLocation(SetLocationParams(lat = position.lat, lng = position.lng, accuracy = accuracy, label = label)) }
    }

    override suspend fun playRoute(request: RouteRequest): RouteOutcome = call {
        val route = request.route
        if (route.points.size < 2) throw CommandException("A route needs at least two stops")
        if (!request.followRoads && (route.isTimed || route.altitudes != null)) {
            checkCanMock()
            try {
                controller.play(route, request.speed, request.loop, request.playbackRate.takeIf { route.isTimed })
            } catch (e: IllegalArgumentException) {
                throw CommandException(e.message ?: "Invalid track")
            }
            onMockingStarted()
            return@call RouteOutcome(route.points)
        }

        checkCanMock() // fail fast, before any network call
        // Asked for roads but got none: say so rather than quietly playing straight lines.
        val points = if (request.followRoads) request.routed?.takeIf { it.size >= 2 } ?: routeAlongRoads(route.points) else route.points
        val result = api.playRoute(
            RoutePlayParams(
                waypoints = points,
                metersPerSecond = request.speed.metersPerSecond,
                loop = request.loop,
                name = route.name,
            ),
        )
        RouteOutcome(points, result.warning)
    }

    override val canFollowRoads: Boolean get() = true

    override suspend fun routeAlongRoads(stops: List<LatLng>): List<LatLng> = withContext(dispatcher) {
        try {
            router.route(stops).points.takeIf { it.size >= 2 } ?: throw RoutingException("Routing returned an empty line")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CommandException("Couldn't follow roads: ${describeRoutingFailure(e)}", MapStateHolder.ROADS_HINT, e)
        }
    }

    private fun describeRoutingFailure(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> "can't reach the routing server"
        is SocketTimeoutException -> "the routing server didn't answer in time"
        is HttpException -> if (e.status == 429) "the routing server is busy (HTTP 429), try again shortly" else e.message ?: "HTTP ${e.status}"
        else -> e.message ?: e::class.simpleName ?: "unknown error"
    }

    private suspend fun <T> call(block: suspend () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (e: RpcException) {
            throw CommandException(e.message ?: "Haunt couldn't do that", e.hint, e)
        }
    }
}
