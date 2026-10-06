package io.github.crockalet.haunt.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** JSON-RPC method names (client → server). */
object Methods {
    const val HELLO = "hello"
    const val STATUS = "status"
    const val LOCATION_SET = "location.set"
    const val LOCATION_STOP = "location.stop"
    const val ROUTE_PLAY = "route.play"
    const val MOVE_TO = "move.to"
    const val PLAYBACK_PAUSE = "playback.pause"
    const val PLAYBACK_RESUME = "playback.resume"
    const val PLAYBACK_STOP = "playback.stop"
    const val PLAYBACK_SET_SPEED = "playback.setSpeed"
    const val PLACES_SEARCH = "places.search"
    const val FAVORITES_LIST = "favorites.list"
    const val FAVORITES_SAVE = "favorites.save"
    const val FAVORITES_DELETE = "favorites.delete"
    const val SUBSCRIBE = "subscribe"

    val ALL: List<String> = listOf(
        HELLO, STATUS, LOCATION_SET, LOCATION_STOP, ROUTE_PLAY, MOVE_TO,
        PLAYBACK_PAUSE, PLAYBACK_RESUME, PLAYBACK_STOP, PLAYBACK_SET_SPEED,
        PLACES_SEARCH, FAVORITES_LIST, FAVORITES_SAVE, FAVORITES_DELETE, SUBSCRIBE,
    )
}

/** Notification method names (server → client) and their short names used by `subscribe`. */
object EventNames {
    const val FIX = "event.fix"
    const val STATE = "event.state"
    const val ROUTE_PROGRESS = "event.routeProgress"
    const val ROUTE_FINISHED = "event.routeFinished"
    const val ERROR = "event.error"

    const val ALL_WILDCARD = "*"

    val ALL: List<String> = listOf(FIX, STATE, ROUTE_PROGRESS, ROUTE_FINISHED, ERROR)

    /** `fix` → `event.fix`; full names pass through; unknown names → null. */
    fun normalize(name: String): String? {
        val full = if (name.startsWith("event.")) name else "event.$name"
        return ALL.firstOrNull { it.equals(full, ignoreCase = true) }
    }

    fun methodOf(event: HauntEvent): String = when (event) {
        is HauntEvent.FixEvent -> FIX
        is HauntEvent.StateEvent -> STATE
        is HauntEvent.RouteProgressEvent -> ROUTE_PROGRESS
        is HauntEvent.RouteFinishedEvent -> ROUTE_FINISHED
        is HauntEvent.ErrorEvent -> ERROR
    }

    @Suppress("UNCHECKED_CAST")
    fun serializerFor(method: String): KSerializer<HauntEvent>? = when (method) {
        FIX -> HauntEvent.FixEvent.serializer()
        STATE -> HauntEvent.StateEvent.serializer()
        ROUTE_PROGRESS -> HauntEvent.RouteProgressEvent.serializer()
        ROUTE_FINISHED -> HauntEvent.RouteFinishedEvent.serializer()
        ERROR -> HauntEvent.ErrorEvent.serializer()
        else -> null
    } as KSerializer<HauntEvent>?
}

/**
 * A typed method descriptor: name + param/result serializers. [Unit] means "no params" (sent as `{}`,
 * ignored on receipt) / "no result" (sent as `{}`).
 */
class RpcMethod<P, R>(
    val name: String,
    val params: KSerializer<P>,
    val result: KSerializer<R>,
)

/** Typed descriptors for every method, shared by [RpcServer] and [RpcClient]. */
object Rpc {
    val Hello = RpcMethod(Methods.HELLO, HelloParams.serializer(), HelloResult.serializer())
    val Status = RpcMethod(Methods.STATUS, Unit.serializer(), StatusResult.serializer())
    val SetLocation = RpcMethod(Methods.LOCATION_SET, SetLocationParams.serializer(), SetLocationResult.serializer())
    val StopLocation = RpcMethod(Methods.LOCATION_STOP, Unit.serializer(), Unit.serializer())
    val PlayRoute = RpcMethod(Methods.ROUTE_PLAY, RoutePlayParams.serializer(), RouteResult.serializer())
    val MoveTo = RpcMethod(Methods.MOVE_TO, MoveToParams.serializer(), RouteResult.serializer())
    val Pause = RpcMethod(Methods.PLAYBACK_PAUSE, Unit.serializer(), Unit.serializer())
    val Resume = RpcMethod(Methods.PLAYBACK_RESUME, Unit.serializer(), Unit.serializer())
    val StopPlayback = RpcMethod(Methods.PLAYBACK_STOP, Unit.serializer(), Unit.serializer())
    val SetSpeed = RpcMethod(Methods.PLAYBACK_SET_SPEED, SetSpeedParams.serializer(), Unit.serializer())
    val SearchPlaces = RpcMethod(Methods.PLACES_SEARCH, PlacesSearchParams.serializer(), ListSerializer(Place.serializer()))
    val ListFavorites = RpcMethod(Methods.FAVORITES_LIST, Unit.serializer(), ListSerializer(Favorite.serializer()))
    val SaveFavorite = RpcMethod(Methods.FAVORITES_SAVE, FavoriteSaveParams.serializer(), Favorite.serializer())
    val DeleteFavorite = RpcMethod(Methods.FAVORITES_DELETE, FavoriteDeleteParams.serializer(), Unit.serializer())
    val Subscribe = RpcMethod(Methods.SUBSCRIBE, SubscribeParams.serializer(), SubscribeResult.serializer())
}

// ----- Validation helpers (throw INVALID_PARAMS) -----

internal fun checkLatLng(lat: Double, lng: Double) {
    if (lat.isNaN() || lat < -90.0 || lat > 90.0) throw RpcException.invalidParams("lat must be within -90..90 (got $lat)")
    if (lng.isNaN() || lng < -180.0 || lng > 180.0) throw RpcException.invalidParams("lng must be within -180..180 (got $lng)")
}

internal fun checkTarget(lat: Double?, lng: Double?, query: String?) {
    val hasCoords = lat != null || lng != null
    when {
        hasCoords && query != null -> throw RpcException.invalidParams("give either lat/lng or query, not both")
        hasCoords -> {
            if (lat == null || lng == null) throw RpcException.invalidParams("both lat and lng are required")
            checkLatLng(lat, lng)
        }
        query == null -> throw RpcException.invalidParams("lat/lng or query is required")
        query.isBlank() -> throw RpcException.invalidParams("query must not be blank")
    }
}

internal fun checkSpeed(metersPerSecond: Double?, multiplier: Double?, allowNeither: Boolean) {
    if (metersPerSecond != null && multiplier != null) {
        throw RpcException.invalidParams("give either metersPerSecond or multiplier, not both")
    }
    if (!allowNeither && metersPerSecond == null && multiplier == null) {
        throw RpcException.invalidParams("metersPerSecond or multiplier is required")
    }
    metersPerSecond?.let {
        if (it.isNaN() || it <= 0.0 || it > 350.0) throw RpcException.invalidParams("metersPerSecond must be in (0, 350]")
    }
    multiplier?.let {
        if (it.isNaN() || it <= 0.0 || it > 1000.0) throw RpcException.invalidParams("multiplier must be in (0, 1000]")
    }
}
