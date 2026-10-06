package io.github.crockalet.haunt.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Observes every handled call, e.g. for the in-app Agent activity log (C3). [error] is null on success. */
fun interface RpcCallListener {
    fun onCall(method: String, params: JsonElement?, error: RpcError?)
}

/**
 * Transport-agnostic JSON-RPC dispatcher for one [HauntApi].
 *
 * One [RpcServer] can serve many connections; call [serve] once per connection:
 * ```
 * val server = RpcServer(api, controllerEvents)          // events: a hot Flow<HauntEvent>
 * // per accepted LocalSocket:
 * server.serve(reader.lineSequence().asFlow().flowOn(IO)) { line -> writer.write(line + "\n"); writer.flush() }
 * ```
 * [serve] returns when [incoming] completes (client disconnected) and in-flight calls finished.
 * Per connection it enforces the `hello` handshake (if [requireHello]), handles `subscribe`, and
 * forwards matching [events] as `event.*` notifications. Requests other than `hello`/`subscribe` run
 * concurrently, so a slow `places.search` doesn't block `status`.
 *
 * @param events hot stream of events (e.g. a SharedFlow); collected once per connection while serving.
 */
class RpcServer(
    private val api: HauntApi,
    private val events: Flow<HauntEvent>,
    private val json: Json = ProtocolJson,
    private val requireHello: Boolean = true,
    private val listener: RpcCallListener? = null,
) {
    private val handlers: Map<String, suspend (JsonElement?) -> JsonElement> = buildMap {
        fun <P, R> on(method: RpcMethod<P, R>, block: suspend (P) -> R) {
            put(method.name) { params -> json.encodeToJsonElement(method.result, block(decodeParams(method.params, params))) }
        }
        on(Rpc.Hello) { error("handled per connection") }
        on(Rpc.Subscribe) { error("handled per connection") }
        on(Rpc.Status) { api.status() }
        on(Rpc.SetLocation) { it.validate(); api.setLocation(it) }
        on(Rpc.StopLocation) { api.stopLocation() }
        on(Rpc.PlayRoute) { it.validate(); api.playRoute(it) }
        on(Rpc.MoveTo) { it.validate(); api.moveTo(it) }
        on(Rpc.Pause) { api.pause() }
        on(Rpc.Resume) { api.resume() }
        on(Rpc.StopPlayback) { api.stopPlayback() }
        on(Rpc.SetSpeed) { it.validate(); api.setSpeed(it) }
        on(Rpc.SearchPlaces) { it.validate(); api.searchPlaces(it) }
        on(Rpc.ListFavorites) { api.listFavorites() }
        on(Rpc.SaveFavorite) { it.validate(); api.saveFavorite(it) }
        on(Rpc.DeleteFavorite) { it.validate(); api.deleteFavorite(it) }
    }

    /** Serves one connection until [incoming] completes. [send] must write one line (it is called serially). */
    suspend fun serve(incoming: Flow<String>, send: suspend (String) -> Unit) = coroutineScope {
        val sendLock = Mutex()
        suspend fun emit(message: JsonRpcMessage) = sendLock.withLock { send(message.encodeLine(json)) }

        val subscriptions = MutableStateFlow<Set<String>>(emptySet())
        var helloDone = !requireHello
        val inFlight = mutableListOf<Job>()

        val eventsJob = launch {
            events.collect { event ->
                val method = EventNames.methodOf(event)
                if (method in subscriptions.value) {
                    val serializer = EventNames.serializerFor(method)!!
                    emit(JsonRpcNotification(method, json.encodeToJsonElement(serializer, event)))
                }
            }
        }

        incoming.collect { line ->
            if (line.isBlank()) return@collect
            val message = try {
                parseRpcMessage(line, json)
            } catch (e: RpcParseException) {
                emit(JsonRpcResponse(id = e.id, error = e.error))
                return@collect
            }
            // Clients don't send responses or notifications to us; ignore them (JSON-RPC: no reply to notifications).
            val request = message as? JsonRpcRequest ?: return@collect

            when {
                request.method == Methods.HELLO -> {
                    val response = handle(request) { params -> hello(params).also { helloDone = true } }
                    emit(response)
                }
                !helloDone -> emit(
                    errorResponse(
                        request,
                        RpcError(ErrorCodes.HANDSHAKE_REQUIRED, "Send \"hello\" first (protocol ${Protocol.VERSION})"),
                    ),
                )
                request.method == Methods.SUBSCRIBE -> emit(
                    handle(request) { params ->
                        val p = decodeParams(SubscribeParams.serializer(), params)
                        val set = p.events.flatMap { name ->
                            if (name == EventNames.ALL_WILDCARD) {
                                EventNames.ALL
                            } else {
                                listOf(EventNames.normalize(name) ?: throw RpcException.invalidParams("unknown event \"$name\" (known: ${EventNames.ALL.joinToString()})"))
                            }
                        }.toSet()
                        subscriptions.value = set
                        json.encodeToJsonElement(SubscribeResult.serializer(), SubscribeResult(set.toList()))
                    },
                )
                else -> {
                    inFlight.removeAll { it.isCompleted }
                    inFlight += launch {
                        val handler = handlers[request.method]
                        val response = if (handler == null) {
                            errorResponse(request, RpcError(ErrorCodes.METHOD_NOT_FOUND, "Method not found: ${request.method}"))
                        } else {
                            handle(request, handler)
                        }
                        emit(response)
                    }
                }
            }
        }
        inFlight.joinAll()
        eventsJob.cancel()
    }

    private suspend fun hello(params: JsonElement?): JsonElement {
        val p = decodeParams(HelloParams.serializer(), params)
        if (p.protocol < Protocol.MIN_SUPPORTED_VERSION || p.protocol > Protocol.VERSION) {
            throw RpcException(
                ErrorCodes.PROTOCOL_MISMATCH,
                "Protocol ${p.protocol} not supported (server speaks ${Protocol.MIN_SUPPORTED_VERSION}..${Protocol.VERSION})",
                json.encodeToJsonElement(
                    ErrorData.serializer(),
                    ErrorData(ErrorHints.PROTOCOL_MISMATCH, Protocol.VERSION, Protocol.MIN_SUPPORTED_VERSION),
                ),
            )
        }
        return json.encodeToJsonElement(HelloResult.serializer(), api.hello(p))
    }

    private suspend fun handle(request: JsonRpcRequest, handler: suspend (JsonElement?) -> JsonElement): JsonRpcResponse {
        val response = try {
            JsonRpcResponse(id = request.id, result = handler(request.params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            errorResponse(request, e.error)
        } catch (e: SerializationException) {
            errorResponse(request, RpcError(ErrorCodes.INVALID_PARAMS, "Invalid params: ${e.message?.lineSequence()?.first()}"))
        } catch (e: IllegalArgumentException) {
            errorResponse(request, RpcError(ErrorCodes.INVALID_PARAMS, "Invalid params: ${e.message}"))
        } catch (e: Exception) {
            errorResponse(request, RpcError(ErrorCodes.INTERNAL_ERROR, "Internal error: ${e.message ?: e::class.simpleName}"))
        }
        listener?.onCall(request.method, request.params, response.error)
        return response
    }

    private fun errorResponse(request: JsonRpcRequest, error: RpcError) = JsonRpcResponse(id = request.id, error = error)

    private fun <P> decodeParams(serializer: KSerializer<P>, params: JsonElement?): P {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as P
        val element = when (params) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> params
            else -> throw RpcException.invalidParams("params must be an object")
        }
        return json.decodeFromJsonElement(serializer, element)
    }
}
