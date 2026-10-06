package io.github.crockalet.haunt.protocol

import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.RouteProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RpcClientServerTest {
    private class Harness(scope: CoroutineScope, requireHello: Boolean = true) {
        val api = FakeHauntApi()
        val events = MutableSharedFlow<HauntEvent>()
        val logged = mutableListOf<Pair<String, Int?>>()
        val server = RpcServer(api, events, requireHello = requireHello, listener = { m, _, e -> logged += m to e?.code })
        val pipe = InMemoryPipe()
        val serverJob = scope.launch {
            server.serve(pipe.serverIncoming, pipe::serverSend)
            pipe.serverHangUp()
        }
        val client = RpcClient(pipe.client, scope, timeout = 5.seconds)
    }

    private fun TestScope.harness(requireHello: Boolean = true) = Harness(backgroundScope, requireHello)

    private suspend fun assertRpcError(code: Int, block: suspend () -> Unit): RpcException {
        val e = assertFailsWith<RpcException> { block() }
        assertEquals(code, e.code, "expected code $code, got ${e.code}: ${e.message}")
        return e
    }

    @Test
    fun helloThenCalls() = runTest {
        val h = harness()
        val hello = h.client.hello("test")
        assertEquals(Protocol.VERSION, hello.protocol)
        assertEquals("foss", hello.flavour)
        assertEquals(hello, h.client.serverInfo)

        val set = h.client.setLocation(SetLocationParams(TokyoTower.lat, TokyoTower.lng, altitude = 40.0, accuracy = 3f))
        assertEquals(TokyoTower, set.fix.position)
        assertEquals(3f, set.fix.accuracy)
        assertNull(set.place)

        val status = h.client.status()
        assertIs<HauntState.Holding>(status.state)
        assertEquals(TokyoTower, status.lastFix?.position)

        val byQuery = h.client.setLocation(SetLocationParams(query = "Tokyo Tower"))
        assertEquals("Tokyo Tower", byQuery.place?.name)

        val route = h.client.playRoute(RoutePlayParams(waypoints = listOf(TokyoTower, Shibuya), metersPerSecond = 5.0, followRoads = true, loop = LoopMode.Loop))
        assertEquals("r1", route.routeId)
        assertTrue(route.followRoads)
        assertEquals(LoopMode.Loop, h.api.lastRoute?.loop)

        val move = h.client.moveTo(MoveToParams(query = "Shibuya"))
        assertEquals(2857L, move.etaS)
        h.client.pause()
        h.client.resume()
        h.client.setSpeed(SetSpeedParams(multiplier = 2.0))
        assertEquals(2.0, h.api.lastSpeed?.multiplier)
        h.client.stopPlayback()
        h.client.stopLocation()
        assertEquals("Shibuya Station", h.client.searchPlaces(PlacesSearchParams("shibuya")).single().name)
        assertEquals("Work", h.client.saveFavorite(FavoriteSaveParams("Work", 1.0, 2.0)).name)
        assertEquals(listOf("Home", "Work"), h.client.listFavorites().map { it.name })
        h.client.deleteFavorite(FavoriteDeleteParams(name = "Work"))
        assertEquals(listOf("Home"), h.client.listFavorites().map { it.name })

        assertEquals(
            listOf(
                Methods.HELLO, Methods.LOCATION_SET, Methods.STATUS, Methods.LOCATION_SET, Methods.ROUTE_PLAY, Methods.MOVE_TO,
                Methods.PLAYBACK_PAUSE, Methods.PLAYBACK_RESUME, Methods.PLAYBACK_SET_SPEED, Methods.PLAYBACK_STOP,
                Methods.LOCATION_STOP, Methods.PLACES_SEARCH, Methods.FAVORITES_SAVE, Methods.FAVORITES_LIST,
                Methods.FAVORITES_DELETE, Methods.FAVORITES_LIST,
            ),
            h.api.calls,
        )
        assertTrue(h.logged.all { it.second == null })
    }

    @Test
    fun apiErrorsAreMappedToCodesWithHints() = runTest {
        val h = harness()
        h.client.hello()
        h.api.mockAppSelected = false
        val e = assertRpcError(ErrorCodes.MOCK_APP_NOT_SELECTED) { h.client.setLocation(SetLocationParams(1.0, 2.0)) }
        assertEquals(ErrorHints.MOCK_APP_NOT_SELECTED, e.hint)

        assertRpcError(ErrorCodes.NOT_FOUND) { h.client.deleteFavorite(FavoriteDeleteParams(name = "nope")) }
        assertRpcError(ErrorCodes.INVALID_STATE) { h.client.pause() }

        h.api.failWith = RpcException.adbControlDisabled()
        val disabled = assertRpcError(ErrorCodes.ADB_CONTROL_DISABLED) { h.client.status() }
        assertEquals(ErrorHints.ADB_CONTROL_DISABLED, disabled.hint)

        h.api.failWith = null
        h.api.crashWith = IllegalStateException("boom")
        val internal = assertRpcError(ErrorCodes.INTERNAL_ERROR) { h.client.status() }
        assertTrue("boom" in internal.message!!)
        assertEquals(Methods.STATUS to ErrorCodes.INTERNAL_ERROR, h.logged.last())
    }

    @Test
    fun invalidParamsAreRejectedBeforeReachingTheApi() = runTest {
        val h = harness()
        h.client.hello()
        assertRpcError(ErrorCodes.INVALID_PARAMS) { h.client.setLocation(SetLocationParams(lat = 100.0, lng = 0.0)) }
        assertRpcError(ErrorCodes.INVALID_PARAMS) { h.client.playRoute(RoutePlayParams()) }
        // Missing required field / wrong type / non-object params.
        assertRpcError(ErrorCodes.INVALID_PARAMS) { h.client.call(Methods.PLACES_SEARCH, JsonObject(emptyMap())) }
        assertRpcError(ErrorCodes.INVALID_PARAMS) {
            h.client.call(Methods.LOCATION_SET, JsonObject(mapOf("lat" to JsonPrimitive("north"), "lng" to JsonPrimitive(1))))
        }
        assertRpcError(ErrorCodes.INVALID_PARAMS) { h.client.call(Methods.LOCATION_SET, JsonPrimitive(3)) }
        assertEquals(listOf(Methods.HELLO), h.api.calls)
    }

    @Test
    fun unknownMethod() = runTest {
        val h = harness()
        h.client.hello()
        val e = assertRpcError(ErrorCodes.METHOD_NOT_FOUND) { h.client.call("teleport", null) }
        assertTrue("teleport" in e.message!!)
        // Connection still usable.
        h.client.status()
    }

    @Test
    fun handshakeIsRequiredAndVersionChecked() = runTest {
        val h = harness()
        assertRpcError(ErrorCodes.HANDSHAKE_REQUIRED) { h.client.status() }
        val mismatch = assertRpcError(ErrorCodes.PROTOCOL_MISMATCH) { h.client.hello(HelloParams(protocol = 99)) }
        assertEquals(Protocol.VERSION, mismatch.error.details?.serverProtocol)
        assertRpcError(ErrorCodes.HANDSHAKE_REQUIRED) { h.client.status() }
        h.client.hello()
        h.client.status()
    }

    @Test
    fun handshakeCanBeDisabled() = runTest {
        val h = harness(requireHello = false)
        h.client.status()
    }

    @Test
    fun subscriptionsFilterNotifications() = runTest {
        val h = harness()
        h.client.hello()
        h.events.subscriptionCount.first { it > 0 }

        val received = async { h.client.events.take(3).toList() }
        // Not subscribed yet: dropped.
        h.events.emit(HauntEvent.FixEvent(fixAt(Shibuya)))

        assertEquals(listOf(EventNames.FIX, EventNames.ROUTE_FINISHED), h.client.subscribe(listOf("fix", "event.routeFinished")).events)
        h.events.emit(HauntEvent.StateEvent(HauntState.Idle)) // filtered out
        h.events.emit(HauntEvent.FixEvent(fixAt(TokyoTower)))
        h.events.emit(HauntEvent.RouteFinishedEvent("r1"))

        h.client.subscribe(listOf("*"))
        h.events.emit(HauntEvent.RouteProgressEvent("r1", RouteProgress(1.0, 2.0, 3)))

        h.client.subscribe(emptyList())
        h.events.emit(HauntEvent.FixEvent(fixAt(Shibuya))) // unsubscribed

        val events = received.await()
        assertEquals(
            listOf(
                HauntEvent.FixEvent(fixAt(TokyoTower)),
                HauntEvent.RouteFinishedEvent("r1"),
                HauntEvent.RouteProgressEvent("r1", RouteProgress(1.0, 2.0, 3)),
            ),
            events,
        )
        assertRpcError(ErrorCodes.INVALID_PARAMS) { h.client.subscribe(listOf("bogus")) }
    }

    @Test
    fun requestsRunConcurrently() = runTest {
        val h = harness()
        h.client.hello()
        val gate = CompletableDeferred<Unit>()
        h.api.searchGate = gate
        val search = async { h.client.searchPlaces(PlacesSearchParams("x")) }
        // A slow search doesn't block status.
        assertIs<HauntState.Idle>(h.client.status().state)
        gate.complete(Unit)
        assertEquals(1, search.await().size)
    }

    @Test
    fun timeoutFailsTheCallButNotTheConnection() = runTest {
        val h = harness()
        h.client.hello()
        h.api.searchGate = CompletableDeferred()
        assertRpcError(ErrorCodes.TIMEOUT) { h.client.searchPlaces(PlacesSearchParams("x")) }
        assertTrue(h.client.connected.value)
        h.client.status()
    }

    @Test
    fun connectionLossFailsPendingAndFutureCalls() = runTest {
        val h = harness()
        h.client.hello()
        h.api.searchGate = CompletableDeferred()
        val pending = async { runCatching { h.client.searchPlaces(PlacesSearchParams("x")) } }
        h.client.status() // make sure the search is in flight
        h.pipe.serverHangUp()
        assertIs<ConnectionLostException>(pending.await().exceptionOrNull())
        h.client.connected.first { !it }
        assertRpcError(ErrorCodes.CONNECTION_LOST) { h.client.status() }
    }

    @Test
    fun clientCloseEndsTheServerSession() = runTest {
        val h = harness()
        h.client.hello()
        h.client.close()
        h.serverJob.join()
        assertFalse(h.client.connected.value)
        assertRpcError(ErrorCodes.CONNECTION_LOST) { h.client.status() }
    }

    // ----- raw wire behaviour -----

    private suspend fun rawExchange(vararg lines: String, requireHello: Boolean = false): List<JsonElement> {
        val out = mutableListOf<String>()
        RpcServer(FakeHauntApi(), MutableSharedFlow(), requireHello = requireHello).serve(flowOf(*lines)) { out += it }
        return out.map { ProtocolJson.parseToJsonElement(it) }
    }

    private fun JsonElement.errorCode() = jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    @Test
    fun malformedJsonGetsParseErrorWithNullId() = runTest {
        val (reply) = rawExchange("{oops")
        assertEquals(ErrorCodes.PARSE_ERROR, reply.errorCode())
        assertEquals("null", reply.jsonObject["id"].toString())
    }

    @Test
    fun invalidRequestsKeepTheirId() = runTest {
        val replies = rawExchange("""{"jsonrpc":"1.0","id":5,"method":"status"}""", "42", "")
        assertEquals(2, replies.size) // blank line ignored
        assertEquals(ErrorCodes.INVALID_REQUEST, replies[0].errorCode())
        assertEquals(5, replies[0].jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals(ErrorCodes.INVALID_REQUEST, replies[1].errorCode())
    }

    @Test
    fun notificationsFromClientAreIgnoredAndStringIdsEchoed() = runTest {
        val replies = rawExchange(
            """{"jsonrpc":"2.0","method":"status"}""",
            """{"jsonrpc":"2.0","id":"abc","method":"status"}""",
        )
        val reply = replies.single().jsonObject
        assertEquals("abc", reply["id"]!!.jsonPrimitive.content)
        assertEquals("Idle", reply["result"]!!.jsonObject["state"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun voidMethodsReturnEmptyObject() = runTest {
        val reply = rawExchange("""{"jsonrpc":"2.0","id":1,"method":"location.stop"}""").single().jsonObject
        assertEquals(JsonObject(emptyMap()), reply["result"])
    }

    @Test
    fun clientIgnoresGarbageAndUnknownNotifications() = runTest {
        val pipe = InMemoryPipe()
        val client = RpcClient(pipe.client, backgroundScope)
        val first = async { client.events.first() }
        pipe.serverSend("garbage")
        pipe.serverSend("""{"jsonrpc":"2.0","method":"event.unknown","params":{}}""")
        pipe.serverSend("""{"jsonrpc":"2.0","method":"event.fix","params":{"bad":1}}""")
        pipe.serverSend(JsonRpcNotification(EventNames.FIX, ProtocolJson.encodeToJsonElement(HauntEvent.FixEvent.serializer(), HauntEvent.FixEvent(fixAt(Shibuya)))).encodeLine())
        assertEquals(HauntEvent.FixEvent(fixAt(Shibuya)), first.await())
        assertTrue(client.connected.value)
    }
}
