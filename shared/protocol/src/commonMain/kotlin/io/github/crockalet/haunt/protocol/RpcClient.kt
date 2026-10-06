package io.github.crockalet.haunt.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [HauntApi] over a [LineTransport]. Correlates responses by id, exposes `event.*` notifications as
 * [events], applies a per-call [timeout], and fails every pending/future call with
 * [ConnectionLostException] once the transport ends.
 *
 * ```
 * val client = RpcClient(transport, scope)
 * client.hello()                      // required first call
 * client.subscribe(listOf("*"))
 * client.events.collect { … }
 * ```
 * The reader runs in [scope]; [close] (or cancelling the scope) closes the transport.
 */
class RpcClient(
    private val transport: LineTransport,
    scope: CoroutineScope,
    private val timeout: Duration = 30.seconds,
    private val json: Json = ProtocolJson,
) : HauntApi {
    private val lock = Mutex()
    private var nextId = 1L
    private val pending = mutableMapOf<Long, CompletableDeferred<JsonRpcResponse>>()

    private val _events = MutableSharedFlow<HauntEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Notifications from the server (only those requested with [subscribe]). Hot; not replayed. */
    val events: SharedFlow<HauntEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(true)

    /** False once the transport has ended. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var closeCause: ConnectionLostException? = null

    /** Result of the last successful [hello]. */
    var serverInfo: HelloResult? = null
        private set

    private var closedByClient = false

    private val reader: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var cause: Throwable? = null
        try {
            transport.incoming.collect { line -> onLine(line) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            cause = e
        } finally {
            val lost = when {
                closedByClient -> ConnectionLostException("Connection closed by client")
                cause != null -> ConnectionLostException("Connection to Haunt lost: ${cause.message}", cause)
                else -> ConnectionLostException("Connection to Haunt closed")
            }
            withContext(NonCancellable) { shutDown(lost) }
        }
    }

    private suspend fun onLine(line: String) {
        if (line.isBlank()) return
        val message = try {
            parseRpcMessage(line, json)
        } catch (_: RpcParseException) {
            return // Garbage from the server; nothing to correlate it with.
        }
        when (message) {
            is JsonRpcResponse -> {
                val id = message.id.idKey() as? Long ?: return
                val deferred = lock.withLock { pending.remove(id) }
                deferred?.complete(message)
            }
            is JsonRpcNotification -> {
                val serializer = EventNames.serializerFor(message.method) ?: return // forward compatible
                val event = try {
                    json.decodeFromJsonElement(serializer, message.params ?: JsonObject(emptyMap()))
                } catch (_: SerializationException) {
                    return
                } catch (_: IllegalArgumentException) {
                    return
                }
                _events.emit(event)
            }
            is JsonRpcRequest -> Unit // Servers don't call clients.
        }
    }

    private suspend fun shutDown(cause: ConnectionLostException) {
        val toFail = lock.withLock {
            if (closeCause != null) return
            closeCause = cause
            pending.values.toList().also { pending.clear() }
        }
        _connected.value = false
        transport.close()
        toFail.forEach { it.completeExceptionally(cause) }
    }

    /** Closes the connection; pending and future calls fail with [ConnectionLostException]. */
    fun close() {
        closedByClient = true
        transport.close()
        reader.cancel()
    }

    /** Sends a request and awaits its raw result. Throws [RpcException] on error responses. */
    suspend fun call(method: String, params: JsonElement?, timeout: Duration = this.timeout): JsonElement {
        val deferred = CompletableDeferred<JsonRpcResponse>()
        val id = lock.withLock {
            closeCause?.let { throw ConnectionLostException(it.message ?: "Connection lost", it) }
            nextId++.also { pending[it] = deferred }
        }
        try {
            transport.send(JsonRpcRequest(JsonPrimitive(id), method, params).encodeLine(json))
        } catch (e: CancellationException) {
            lock.withLock { pending.remove(id) }
            throw e
        } catch (e: Exception) {
            lock.withLock { pending.remove(id) }
            throw ConnectionLostException("Connection to Haunt lost: ${e.message}", e)
        }
        val response = try {
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            lock.withLock { pending.remove(id) }
            throw RpcTimeoutException(method, timeout.inWholeMilliseconds)
        }
        response.error?.let { throw RpcException(it) }
        return response.result ?: JsonNull
    }

    /** Typed call through a [RpcMethod] descriptor. */
    suspend fun <P, R> call(method: RpcMethod<P, R>, params: P, timeout: Duration = this.timeout): R {
        val encoded = if (method.params == Unit.serializer()) null else json.encodeToJsonElement(method.params, params)
        val result = call(method.name, encoded, timeout)
        @Suppress("UNCHECKED_CAST")
        if (method.result == Unit.serializer()) return Unit as R
        return try {
            json.decodeFromJsonElement(method.result, result)
        } catch (e: SerializationException) {
            throw RpcException(ErrorCodes.INTERNAL_ERROR, "Unexpected '${method.name}' result: ${e.message}")
        }
    }

    /** Handshake; must be the first call. Stores [serverInfo]. */
    suspend fun hello(client: String? = null): HelloResult = hello(HelloParams(Protocol.VERSION, client))

    override suspend fun hello(params: HelloParams): HelloResult = call(Rpc.Hello, params).also { serverInfo = it }

    /** Chooses which `event.*` notifications this connection receives (`*` = all, empty = none). */
    suspend fun subscribe(events: List<String>): SubscribeResult = call(Rpc.Subscribe, SubscribeParams(events))

    override suspend fun status(): StatusResult = call(Rpc.Status, Unit)
    override suspend fun setLocation(params: SetLocationParams): SetLocationResult = call(Rpc.SetLocation, params)
    override suspend fun stopLocation() = call(Rpc.StopLocation, Unit)
    override suspend fun playRoute(params: RoutePlayParams): RouteResult = call(Rpc.PlayRoute, params)
    override suspend fun moveTo(params: MoveToParams): RouteResult = call(Rpc.MoveTo, params)
    override suspend fun pause() = call(Rpc.Pause, Unit)
    override suspend fun resume() = call(Rpc.Resume, Unit)
    override suspend fun stopPlayback() = call(Rpc.StopPlayback, Unit)
    override suspend fun setSpeed(params: SetSpeedParams) = call(Rpc.SetSpeed, params)
    override suspend fun searchPlaces(params: PlacesSearchParams): List<Place> = call(Rpc.SearchPlaces, params)
    override suspend fun listFavorites(): List<Favorite> = call(Rpc.ListFavorites, Unit)
    override suspend fun saveFavorite(params: FavoriteSaveParams): Favorite = call(Rpc.SaveFavorite, params)
    override suspend fun deleteFavorite(params: FavoriteDeleteParams) = call(Rpc.DeleteFavorite, params)
}
