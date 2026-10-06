package io.github.crockalet.haunt.android.ui

import io.github.crockalet.haunt.android.control.TrackedController
import io.github.crockalet.haunt.android.net.Router
import io.github.crockalet.haunt.protocol.HauntApi
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.ui.state.CommandException
import io.github.crockalet.haunt.ui.state.HauntCommands
import io.github.crockalet.haunt.ui.state.RouteOutcome
import io.github.crockalet.haunt.ui.state.RouteRequest
import io.github.crockalet.haunt.ui.state.isTimed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The map screen's commands on top of the app's [HauntApi], so the UI gets exactly the checks
 * agents get: mock-app selected and location permission ([RpcException] → [CommandException] with
 * the hint), and the foreground service is started.
 *
 * - Pins and places → `location.set`.
 * - Hand-placed stops → `route.play` with waypoints. With "Follow roads" the stops are routed here
 *   first (same [Router] the API uses) so the UI can draw the road-following line it plays.
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

    override suspend fun setLocation(position: io.github.crockalet.haunt.core.LatLng, accuracy: Float?, label: String?) {
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
        var warning: String? = null
        val points = if (request.followRoads) {
            try {
                router.route(route.points).points.takeIf { it.size >= 2 } ?: route.points
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warning = "Couldn't follow roads (${e.message ?: e::class.simpleName}); using straight lines"
                route.points
            }
        } else {
            route.points
        }
        val result = api.playRoute(
            RoutePlayParams(
                waypoints = points,
                metersPerSecond = request.speed.metersPerSecond,
                loop = request.loop,
                name = route.name,
            ),
        )
        RouteOutcome(points, listOfNotNull(warning, result.warning).joinToString("; ").ifEmpty { null })
    }

    private suspend fun <T> call(block: suspend () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (e: RpcException) {
            throw CommandException(e.message ?: "Haunt couldn't do that", e.hint, e)
        }
    }
}
