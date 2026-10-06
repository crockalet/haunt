package io.github.crockalet.haunt.protocol

/**
 * Everything an agent can ask Haunt to do (DESIGN §6.1). The Android app implements this on top of
 * `HauntController`; [RpcClient] implements it over the wire for the CLI / MCP server.
 *
 * Implementations signal failures by throwing [RpcException] (see [ErrorCodes] and the
 * `RpcException.*` factories, e.g. [RpcException.mockAppNotSelected]). Any other exception becomes
 * [ErrorCodes.INTERNAL_ERROR]. Params have already passed their `validate()` when [RpcServer] calls in.
 *
 * `subscribe` is not part of this interface: it is per-connection and handled by [RpcServer].
 */
interface HauntApi {
    /** Handshake; [RpcServer] has already checked protocol compatibility. */
    suspend fun hello(params: HelloParams): HelloResult

    suspend fun status(): StatusResult

    /** `location.set`: hold a fixed location (geocode [SetLocationParams.query] when given). */
    suspend fun setLocation(params: SetLocationParams): SetLocationResult

    /** `location.stop`: stop mocking entirely (back to Idle, test providers removed). */
    suspend fun stopLocation()

    /** `route.play`: start a route / track; returns immediately with distance and ETA. */
    suspend fun playRoute(params: RoutePlayParams): RouteResult

    /** `move.to`: travel from the current position to a target; returns immediately with ETA. */
    suspend fun moveTo(params: MoveToParams): RouteResult

    /** `playback.pause`. Throws [ErrorCodes.INVALID_STATE] when nothing is moving. */
    suspend fun pause()

    /** `playback.resume`. */
    suspend fun resume()

    /** `playback.stop`: stop moving but keep holding the current position. */
    suspend fun stopPlayback()

    /** `playback.setSpeed`. */
    suspend fun setSpeed(params: SetSpeedParams)

    /** `places.search`. */
    suspend fun searchPlaces(params: PlacesSearchParams): List<Place>

    /** `favorites.list`. */
    suspend fun listFavorites(): List<Favorite>

    /** `favorites.save`. */
    suspend fun saveFavorite(params: FavoriteSaveParams): Favorite

    /** `favorites.delete`. Throws [ErrorCodes.NOT_FOUND] if no such favourite. */
    suspend fun deleteFavorite(params: FavoriteDeleteParams)
}

/**
 * A bidirectional, line-oriented connection (one JSON-RPC message per line). Socket implementations live
 * in the CLI (TCP via `adb forward`) and the Android app (`LocalSocket`).
 */
interface LineTransport {
    /** Lines received from the peer, without terminators. Completes on EOF; throws on I/O failure. Collect once. */
    val incoming: kotlinx.coroutines.flow.Flow<String>

    /** Sends one line (the transport appends the newline). Throws if the connection is closed. */
    suspend fun send(line: String)

    /** Closes the connection; [incoming] completes. Idempotent. */
    fun close()
}
