package io.github.crockalet.haunt.protocol

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import kotlinx.coroutines.CompletableDeferred

val TokyoTower = LatLng(35.6586, 139.7454)
val Shibuya = LatLng(35.6580, 139.7016)

fun fixAt(p: LatLng, t: Long = 1_000L) = Fix(position = p, altitude = 40.0, accuracy = 5f, timeMillis = t)

class FakeHauntApi : HauntApi {
    val calls = mutableListOf<String>()
    var state: HauntState = HauntState.Idle
    var mockAppSelected = true
    var failWith: RpcException? = null
    var crashWith: Exception? = null
    var lastSetLocation: SetLocationParams? = null
    var lastRoute: RoutePlayParams? = null
    var lastMoveTo: MoveToParams? = null
    var lastSpeed: SetSpeedParams? = null
    val favorites = mutableListOf(Favorite("1", "Home", TokyoTower))

    /** When set, [searchPlaces] suspends until completed (to test concurrency/timeouts). */
    var searchGate: CompletableDeferred<Unit>? = null

    private fun record(name: String) {
        calls += name
        crashWith?.let { throw it }
        failWith?.let { throw it }
    }

    override suspend fun hello(params: HelloParams): HelloResult {
        record(Methods.HELLO)
        return HelloResult(Protocol.VERSION, "0.1.0-test", "foss", mockAppSelected)
    }

    override suspend fun status(): StatusResult {
        record(Methods.STATUS)
        return StatusResult(state, fixAt(TokyoTower), mockAppSelected)
    }

    override suspend fun setLocation(params: SetLocationParams): SetLocationResult {
        record(Methods.LOCATION_SET)
        if (!mockAppSelected) throw RpcException.mockAppNotSelected()
        lastSetLocation = params
        val place = params.query?.let {
            if (it == "Tokyo Tower") Place("Tokyo Tower", TokyoTower, "Minato, Tokyo") else throw RpcException.notFound("No place matches \"$it\"")
        }
        val pos = params.position ?: place!!.position
        val fix = Fix(pos, params.altitude, params.accuracy ?: 5f, timeMillis = 1L)
        state = HauntState.Holding(fix, params.label ?: place?.name)
        return SetLocationResult(fix, place)
    }

    override suspend fun stopLocation() {
        record(Methods.LOCATION_STOP)
        state = HauntState.Idle
    }

    override suspend fun playRoute(params: RoutePlayParams): RouteResult {
        record(Methods.ROUTE_PLAY)
        lastRoute = params
        return RouteResult("r1", 1234.0, 600, followRoads = params.followRoads)
    }

    override suspend fun moveTo(params: MoveToParams): RouteResult {
        record(Methods.MOVE_TO)
        lastMoveTo = params
        state = HauntState.Moving(
            fixAt(TokyoTower), "to target", RouteProgress(0.0, 4000.0, 2857), Speed.Walk, LoopMode.Once, paused = false,
        )
        return RouteResult("r2", 4000.0, 2857)
    }

    override suspend fun pause() {
        record(Methods.PLAYBACK_PAUSE)
        if (state !is HauntState.Moving) throw RpcException.invalidState("Nothing is moving")
    }

    override suspend fun resume() = record(Methods.PLAYBACK_RESUME)
    override suspend fun stopPlayback() = record(Methods.PLAYBACK_STOP)

    override suspend fun setSpeed(params: SetSpeedParams) {
        record(Methods.PLAYBACK_SET_SPEED)
        lastSpeed = params
    }

    override suspend fun searchPlaces(params: PlacesSearchParams): List<Place> {
        record(Methods.PLACES_SEARCH)
        searchGate?.await()
        return listOf(Place("Shibuya Station", Shibuya, "Shibuya, Tokyo", "station")).take(params.limit ?: 10)
    }

    override suspend fun listFavorites(): List<Favorite> {
        record(Methods.FAVORITES_LIST)
        return favorites.toList()
    }

    override suspend fun saveFavorite(params: FavoriteSaveParams): Favorite {
        record(Methods.FAVORITES_SAVE)
        val f = Favorite((favorites.size + 1).toString(), params.name, LatLng(params.lat ?: 0.0, params.lng ?: 0.0), params.folder)
        favorites.removeAll { it.name == params.name }
        favorites += f
        return f
    }

    override suspend fun deleteFavorite(params: FavoriteDeleteParams) {
        record(Methods.FAVORITES_DELETE)
        val removed = favorites.removeAll { it.id == params.id || it.name == params.name }
        if (!removed) throw RpcException.notFound("No favourite named \"${params.name ?: params.id}\"")
    }
}
