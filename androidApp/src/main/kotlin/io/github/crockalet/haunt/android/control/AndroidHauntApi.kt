package io.github.crockalet.haunt.android.control

import io.github.crockalet.haunt.android.data.FavoritesStore
import io.github.crockalet.haunt.android.net.Geocoder
import io.github.crockalet.haunt.android.net.Router
import io.github.crockalet.haunt.core.CoordinateParseResult
import io.github.crockalet.haunt.core.CoordinateParser
import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.TrackFormats
import io.github.crockalet.haunt.core.Travel
import io.github.crockalet.haunt.core.currentFix
import io.github.crockalet.haunt.protocol.Favorite
import io.github.crockalet.haunt.protocol.FavoriteDeleteParams
import io.github.crockalet.haunt.protocol.FavoriteSaveParams
import io.github.crockalet.haunt.protocol.HauntApi
import io.github.crockalet.haunt.protocol.HelloParams
import io.github.crockalet.haunt.protocol.HelloResult
import io.github.crockalet.haunt.protocol.MoveToParams
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.PlacesSearchParams
import io.github.crockalet.haunt.protocol.Protocol
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RouteResult
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.protocol.SetLocationResult
import io.github.crockalet.haunt.protocol.SetSpeedParams
import io.github.crockalet.haunt.protocol.StatusResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * What [AndroidHauntApi] needs from the platform. The Android implementation lives in
 * [io.github.crockalet.haunt.android.HauntRuntime]; tests use a fake.
 */
interface MockingEnvironment {
    val appVersion: String

    /** "foss" or "play". */
    val flavour: String

    /** Whether Haunt is the selected mock location app. */
    fun mockAppSelected(): Boolean

    /**
     * Throws an [RpcException] if mocking can't start right now: mock app not selected
     * ([RpcException.mockAppNotSelected]) or a missing location permission ([RpcException.unavailable]).
     */
    fun checkCanMock()

    /** Mocking just started or changed: make sure the foreground service runs. */
    fun onMockingStarted()
}

/**
 * [HauntApi] on top of the process-wide controller. Platform-free (everything Android sits behind
 * [MockingEnvironment], [Geocoder], [Router]), so it's unit-tested on the JVM.
 */
class AndroidHauntApi(
    private val controller: TrackedController,
    private val favorites: FavoritesStore,
    private val geocoder: Geocoder,
    private val router: Router,
    private val environment: MockingEnvironment,
) : HauntApi {

    override suspend fun hello(params: HelloParams) = HelloResult(
        protocol = Protocol.VERSION,
        appVersion = environment.appVersion,
        flavour = environment.flavour,
        mockAppSelected = environment.mockAppSelected(),
    )

    override suspend fun status() = StatusResult(
        state = controller.state.value,
        lastFix = controller.lastFix,
        mockAppSelected = environment.mockAppSelected(),
    )

    override suspend fun setLocation(params: SetLocationParams): SetLocationResult {
        environment.checkCanMock()
        val place = if (params.position == null) resolve(params.query!!) else null
        val position = params.position ?: place!!.position
        controller.setLocation(position, params.altitude, params.accuracy, params.label ?: place?.name)
        environment.onMockingStarted()
        val fix = controller.state.value.currentFix ?: throw RpcException.invalidState("Location was stopped concurrently")
        return SetLocationResult(fix, place)
    }

    override suspend fun stopLocation() {
        controller.stop()
    }

    override suspend fun playRoute(params: RoutePlayParams): RouteResult {
        environment.checkCanMock()
        val warnings = mutableListOf<String>()
        var followed = false
        var route: Route = when {
            params.waypoints != null -> {
                val waypoints = params.waypoints!!
                if (params.followRoads) {
                    val routed = routeOrNull(waypoints, warnings, travelFor(params.metersPerSecond))
                    followed = routed != null
                    Route(routed ?: waypoints, name = params.name)
                } else {
                    Route(waypoints, name = params.name)
                }
            }
            else -> {
                val track = parseTrack(params, warnings)
                if (params.followRoads) warnings += "followRoads is ignored for GPX/KML tracks"
                if (params.name != null) track.copy(name = params.name) else track
            }
        }

        val timed = hasTiming(route)
        val speed: Speed
        var rate: Double? = null
        when {
            params.metersPerSecond != null -> {
                speed = Speed(params.metersPerSecond!!)
                route = route.copy(timestampsMillis = null)
            }
            params.multiplier != null -> if (timed) {
                speed = DEFAULT_SPEED
                rate = params.multiplier
            } else {
                speed = Speed(DEFAULT_SPEED.metersPerSecond * params.multiplier!!)
            }
            else -> {
                speed = DEFAULT_SPEED
                if (timed) rate = 1.0
            }
        }
        val routeId = play(route, speed, params.loop, rate)
        return routeResult(routeId, route, followed, null, warnings)
    }

    override suspend fun moveTo(params: MoveToParams): RouteResult {
        environment.checkCanMock()
        val from = controller.state.value.currentFix?.position
            ?: throw RpcException.invalidState("Haunt isn't mocking a location yet; set one first (location.set)")
        val place = if (params.position == null) resolve(params.query!!) else null
        val target = params.position ?: place!!.position
        val warnings = mutableListOf<String>()
        var followed = false
        val points = if (params.followRoads) {
            routeOrNull(listOf(from, target), warnings, travelFor(params.metersPerSecond))?.also { followed = true } ?: listOf(from, target)
        } else {
            listOf(from, target)
        }
        val route = Route(points, name = place?.name)
        val speed = params.metersPerSecond?.let(::Speed) ?: DEFAULT_SPEED
        val routeId = play(route, speed, LoopMode.Once, null)
        return routeResult(routeId, route, followed, place, warnings)
    }

    override suspend fun pause() {
        requireMoving("pause")
        controller.pause()
    }

    override suspend fun resume() {
        requireMoving("resume")
        controller.resume()
    }

    override suspend fun stopPlayback() {
        if (controller.state.value == HauntState.Idle) throw RpcException.invalidState("Nothing is playing")
        controller.holdCurrentPosition()
    }

    override suspend fun setSpeed(params: SetSpeedParams) {
        val mps = params.metersPerSecond
        val multiplier = params.multiplier
        when (val s = controller.state.value) {
            is HauntState.Moving -> when {
                mps != null -> controller.setSpeed(Speed(mps))
                s.playbackRate != null -> controller.setPlaybackRate(s.playbackRate!! * multiplier!!)
                else -> controller.setSpeed(Speed(s.speed.metersPerSecond * multiplier!!))
            }
            is HauntState.Joystick -> controller.setSpeed(Speed(mps ?: (s.maxSpeed.metersPerSecond * multiplier!!)))
            else -> throw RpcException.invalidState("Nothing is moving")
        }
    }

    override suspend fun searchPlaces(params: PlacesSearchParams): List<Place> =
        geocode(params.query, params.limit ?: DEFAULT_SEARCH_LIMIT, params.near ?: controller.lastFix?.position)

    override suspend fun listFavorites(): List<Favorite> = favorites.list()

    override suspend fun saveFavorite(params: FavoriteSaveParams): Favorite {
        val position = if (params.lat != null && params.lng != null) {
            LatLng(params.lat!!, params.lng!!)
        } else {
            controller.state.value.currentFix?.position
                ?: throw RpcException.invalidState("No current location to save; pass lat and lng")
        }
        return io { favorites.save(params.name, position, params.folder, params.color) }
    }

    override suspend fun deleteFavorite(params: FavoriteDeleteParams) {
        io { favorites.delete(params.id, params.name) }
            ?: throw RpcException.notFound("No favourite ${params.id?.let { "with id \"$it\"" } ?: "named \"${params.name}\""}")
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun play(route: Route, speed: Speed, loop: LoopMode, rate: Double?): String {
        val id = try {
            controller.play(route, speed, loop, rate)
        } catch (e: IllegalArgumentException) {
            throw RpcException.invalidParams(e.message ?: "Invalid route")
        }
        environment.onMockingStarted()
        return id
    }

    private fun routeResult(routeId: String, route: Route, followed: Boolean, destination: Place?, warnings: List<String>): RouteResult {
        val state = controller.state.value
        val eta = if (state is HauntState.Moving && controller.currentRouteId == routeId) state.progress.etaSeconds else 0L
        return RouteResult(
            routeId = routeId,
            distanceM = Geo.polylineLength(route.points),
            etaS = eta,
            followRoads = followed,
            destination = destination,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private fun requireMoving(action: String) {
        val s = controller.state.value
        if (s !is HauntState.Moving && s !is HauntState.Joystick) throw RpcException.invalidState("Nothing to $action: not moving")
    }

    /** Coordinates in any supported format, then an exact favourite name, then the geocoder. */
    private suspend fun resolve(query: String): Place {
        val reference = controller.lastFix?.position
        val parsed = CoordinateParser.parse(query, reference)
        if (parsed is CoordinateParseResult.Success) {
            return Place(name = parsed.label ?: query.trim(), position = parsed.position, kind = "coordinates")
        }
        favorites.findByName(query)?.let { return Place(it.name, it.position, kind = "favorite") }
        return geocode(query, 1, reference).firstOrNull() ?: throw RpcException.notFound("No place found for \"$query\"")
    }

    private suspend fun geocode(query: String, limit: Int, near: LatLng?): List<Place> = try {
        geocoder.search(query, limit, near)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw RpcException.unavailable("Place search failed: ${e.message}", SEARCH_HINT)
    } catch (e: IllegalArgumentException) {
        throw RpcException.unavailable("Place search failed: ${e.message}", SEARCH_HINT)
    }

    private fun travelFor(metersPerSecond: Double?) = Travel.forSpeed(metersPerSecond?.let(::Speed) ?: DEFAULT_SPEED)

    private suspend fun routeOrNull(waypoints: List<LatLng>, warnings: MutableList<String>, travel: Travel): List<LatLng>? = try {
        router.route(waypoints, travel).points
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warnings += "Routing failed (${e.message ?: e::class.simpleName}); using straight lines"
        null
    }

    private fun parseTrack(params: RoutePlayParams, warnings: MutableList<String>): Route {
        val routes = try {
            if (params.gpx != null) TrackFormats.parseGpx(params.gpx!!) else TrackFormats.parseKml(params.kml!!)
        } catch (e: IllegalArgumentException) {
            throw RpcException.invalidParams("Could not read the track: ${e.message}")
        }
        val usable = routes.filter { it.points.isNotEmpty() }
        val first = usable.firstOrNull() ?: throw RpcException.invalidParams("The track has no points")
        if (usable.size > 1) warnings += "The file has ${usable.size} tracks; playing the first${first.name?.let { " (\"$it\")" } ?: ""}"
        return first
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        val DEFAULT_SPEED = Speed.Walk
        const val DEFAULT_SEARCH_LIMIT = 5
        const val SEARCH_HINT = "Check the device's internet connection, or the search service URL in Haunt → Settings."

        /** Mirrors the engine's rule for replaying by recorded timestamps. */
        fun hasTiming(route: Route): Boolean {
            val ts = route.timestampsMillis ?: return false
            if (ts.size != route.points.size || ts.size < 2) return false
            for (i in 1 until ts.size) if (ts[i] < ts[i - 1]) return false
            return ts.last() > ts.first()
        }
    }
}

/** Wraps a [HauntApi] so every call fails with ADB_CONTROL_DISABLED while [enabled] returns false. */
class AdbGatedHauntApi(private val delegate: HauntApi, private val enabled: () -> Boolean) : HauntApi {
    private fun check() {
        if (!enabled()) throw RpcException.adbControlDisabled()
    }

    override suspend fun hello(params: HelloParams) = check().let { delegate.hello(params) }
    override suspend fun status() = check().let { delegate.status() }
    override suspend fun setLocation(params: SetLocationParams) = check().let { delegate.setLocation(params) }
    override suspend fun stopLocation() = check().let { delegate.stopLocation() }
    override suspend fun playRoute(params: RoutePlayParams) = check().let { delegate.playRoute(params) }
    override suspend fun moveTo(params: MoveToParams) = check().let { delegate.moveTo(params) }
    override suspend fun pause() = check().let { delegate.pause() }
    override suspend fun resume() = check().let { delegate.resume() }
    override suspend fun stopPlayback() = check().let { delegate.stopPlayback() }
    override suspend fun setSpeed(params: SetSpeedParams) = check().let { delegate.setSpeed(params) }
    override suspend fun searchPlaces(params: PlacesSearchParams) = check().let { delegate.searchPlaces(params) }
    override suspend fun listFavorites() = check().let { delegate.listFavorites() }
    override suspend fun saveFavorite(params: FavoriteSaveParams) = check().let { delegate.saveFavorite(params) }
    override suspend fun deleteFavorite(params: FavoriteDeleteParams) = check().let { delegate.deleteFavorite(params) }
}
