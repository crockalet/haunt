package io.github.crockalet.haunt.protocol

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.RouteProgress
import kotlinx.serialization.Serializable

// Typed params / results for every method in DESIGN §6.1. All optional fields default to null so
// they are omitted on the wire; servers must ignore unknown keys (ProtocolJson does).

@Serializable
data class HelloParams(
    val protocol: Int = Protocol.VERSION,
    /** Free-form client identifier, e.g. "haunt-cli/0.1.0" or "haunt-mcp/0.1.0 (claude-code)". */
    val client: String? = null,
)

@Serializable
data class HelloResult(
    val protocol: Int,
    val appVersion: String,
    /** Build flavour: "foss" or "play". */
    val flavour: String,
    /** False until Haunt is chosen under Developer options → Select mock location app. */
    val mockAppSelected: Boolean,
)

@Serializable
data class StatusResult(
    @Serializable(with = HauntStateSerializer::class)
    val state: HauntState,
    /** The last injected fix, also when idle (where the device was last sent). */
    val lastFix: Fix? = null,
    val mockAppSelected: Boolean? = null,
)

/** `location.set`: either [lat]+[lng] or [query] (geocoded on the device; may also match a favourite name). */
@Serializable
data class SetLocationParams(
    val lat: Double? = null,
    val lng: Double? = null,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val query: String? = null,
    /** Optional display label (notification, status); defaults to the resolved place name. */
    val label: String? = null,
) {
    /** @throws RpcException with [ErrorCodes.INVALID_PARAMS] when the combination is invalid. */
    fun validate() {
        checkTarget(lat, lng, query)
        accuracy?.let { if (it <= 0f || it.isNaN()) throw RpcException.invalidParams("accuracy must be > 0") }
    }

    val position: LatLng? get() = if (lat != null && lng != null) LatLng(lat, lng) else null
}

/** Result of `location.set`: the fix now being injected, plus the place when [SetLocationParams.query] was used. */
@Serializable
data class SetLocationResult(
    val fix: Fix,
    val place: Place? = null,
)

/**
 * `route.play`: exactly one of [waypoints], [gpx] (GPX 1.1 text) or [kml] (KML text).
 * Speed: [metersPerSecond] for a fixed speed, or [multiplier] to replay a timed track at N× (GPX timestamps).
 * Neither → recorded timing if the track has timestamps, else walking speed.
 */
@Serializable
data class RoutePlayParams(
    val waypoints: List<LatLng>? = null,
    val gpx: String? = null,
    val kml: String? = null,
    val metersPerSecond: Double? = null,
    val multiplier: Double? = null,
    /** Snap waypoints to roads via the configured routing service (falls back to straight lines). */
    val followRoads: Boolean = false,
    val loop: LoopMode = LoopMode.Once,
    val name: String? = null,
) {
    fun validate() {
        val sources = listOfNotNull(waypoints, gpx, kml).size
        if (sources != 1) throw RpcException.invalidParams("exactly one of waypoints, gpx or kml is required")
        if (waypoints != null && waypoints.size < 2) {
            throw RpcException.invalidParams("waypoints needs at least 2 points (use move.to for a single target)")
        }
        waypoints?.forEach { checkLatLng(it.lat, it.lng) }
        checkSpeed(metersPerSecond, multiplier, allowNeither = true)
    }
}

/** `move.to`: travel from the current position to [lat]+[lng] or [query]. */
@Serializable
data class MoveToParams(
    val lat: Double? = null,
    val lng: Double? = null,
    val query: String? = null,
    /** Defaults to walking speed. */
    val metersPerSecond: Double? = null,
    val followRoads: Boolean = false,
) {
    fun validate() {
        checkTarget(lat, lng, query)
        checkSpeed(metersPerSecond, null, allowNeither = true)
    }

    val position: LatLng? get() = if (lat != null && lng != null) LatLng(lat, lng) else null
}

/** Result of `route.play` and `move.to`. */
@Serializable
data class RouteResult(
    val routeId: String,
    val distanceM: Double,
    /** Estimated seconds to the end of the route (first pass when looping); null if unknown. */
    val etaS: Long? = null,
    /** True when the route actually follows roads (false if routing failed and fell back to straight lines). */
    val followRoads: Boolean = false,
    /** Destination place when a query was resolved. */
    val destination: Place? = null,
    /** Non-fatal problem worth telling the user, e.g. "Routing failed; using straight lines". */
    val warning: String? = null,
)

/** `playback.setSpeed`: exactly one of [metersPerSecond] or [multiplier] (of the current speed / track timing). */
@Serializable
data class SetSpeedParams(
    val metersPerSecond: Double? = null,
    val multiplier: Double? = null,
) {
    fun validate() = checkSpeed(metersPerSecond, multiplier, allowNeither = false)
}

@Serializable
data class PlacesSearchParams(
    val query: String,
    /** Bias results around this point (defaults to the current location on the device). */
    val near: LatLng? = null,
    val limit: Int? = null,
) {
    fun validate() {
        if (query.isBlank()) throw RpcException.invalidParams("query must not be blank")
        if (limit != null && limit <= 0) throw RpcException.invalidParams("limit must be > 0")
    }
}

@Serializable
data class Place(
    val name: String,
    val position: LatLng,
    /** One-line address / context, e.g. "Minato, Tokyo, Japan". */
    val address: String? = null,
    /** OSM-ish category, e.g. "station", "attraction". */
    val kind: String? = null,
)

@Serializable
data class Favorite(
    val id: String,
    val name: String,
    val position: LatLng,
    val folder: String? = null,
    /** `#RRGGBB`. */
    val color: String? = null,
    val createdMillis: Long? = null,
)

/** `favorites.save`: saves [lat]+[lng], or the current location when both are omitted. Same name → overwrite. */
@Serializable
data class FavoriteSaveParams(
    val name: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val folder: String? = null,
    val color: String? = null,
) {
    fun validate() {
        if (name.isBlank()) throw RpcException.invalidParams("name must not be blank")
        if ((lat == null) != (lng == null)) throw RpcException.invalidParams("give both lat and lng, or neither")
        if (lat != null && lng != null) checkLatLng(lat, lng)
    }
}

/** `favorites.delete`: by [id] or by exact [name]. */
@Serializable
data class FavoriteDeleteParams(
    val id: String? = null,
    val name: String? = null,
) {
    fun validate() {
        if (id == null && name == null) throw RpcException.invalidParams("id or name is required")
    }
}

/**
 * `subscribe`: which notifications this connection receives. Names are [EventNames] short names
 * (`fix`, `state`, …) or full method names (`event.fix`); `*` means all. Replaces the previous set;
 * an empty list unsubscribes. Connections start unsubscribed.
 */
@Serializable
data class SubscribeParams(val events: List<String>)

@Serializable
data class SubscribeResult(val events: List<String>)

// ----- Notifications (server → client) -----

/** Payloads of the `event.*` notifications. The Android side emits these into [RpcServer]. */
sealed interface HauntEvent {
    /** Every injected fix (~1 Hz). */
    @Serializable
    data class FixEvent(val fix: Fix) : HauntEvent

    /** State changes (mode, pause/resume, speed, …). */
    @Serializable
    data class StateEvent(
        @Serializable(with = HauntStateSerializer::class)
        val state: HauntState,
    ) : HauntEvent

    @Serializable
    data class RouteProgressEvent(val routeId: String? = null, val progress: RouteProgress) : HauntEvent

    /** A route reached its end (Once mode), or was stopped / replaced before arriving ([reason]). */
    @Serializable
    data class RouteFinishedEvent(
        val routeId: String? = null,
        val reason: FinishReason = FinishReason.Arrived,
        val fix: Fix? = null,
    ) : HauntEvent

    /** Something went wrong outside a request (injection failed, mock app deselected, …). */
    @Serializable
    data class ErrorEvent(val code: Int, val message: String, val hint: String? = null) : HauntEvent
}

@Serializable
enum class FinishReason { Arrived, Stopped, Replaced }
