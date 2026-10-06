package io.github.crockalet.haunt.protocol

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SerializationTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): String {
        val text = ProtocolJson.encodeToString(serializer, value)
        assertEquals(value, ProtocolJson.decodeFromString(serializer, text), "round-trip of $text")
        assertFalse('\n' in text, "must be a single line")
        return text
    }

    private val moving = HauntState.Moving(
        fix = Fix(TokyoTower, 40.0, 5f, bearing = 90f, speed = 1.4f, timeMillis = 42),
        routeName = "walk",
        progress = RouteProgress(100.0, 1000.0, 640),
        speed = Speed.Walk,
        loop = LoopMode.PingPong,
        paused = true,
    )

    @Test
    fun paramsAndResultsRoundTrip() {
        roundTrip(HelloParams.serializer(), HelloParams(1, "haunt-cli/0.1"))
        roundTrip(HelloResult.serializer(), HelloResult(1, "0.1.0", "play", mockAppSelected = false))
        roundTrip(SetLocationParams.serializer(), SetLocationParams(1.0, 2.0, altitude = 3.0, accuracy = 4f))
        roundTrip(SetLocationParams.serializer(), SetLocationParams(query = "Tokyo Tower"))
        roundTrip(SetLocationResult.serializer(), SetLocationResult(fixAt(TokyoTower), Place("Tokyo Tower", TokyoTower)))
        roundTrip(
            RoutePlayParams.serializer(),
            RoutePlayParams(waypoints = listOf(TokyoTower, Shibuya), metersPerSecond = 5.0, followRoads = true, loop = LoopMode.Loop),
        )
        roundTrip(RoutePlayParams.serializer(), RoutePlayParams(gpx = "<gpx/>", multiplier = 2.0))
        roundTrip(MoveToParams.serializer(), MoveToParams(query = "Shibuya", metersPerSecond = 1.4, followRoads = true))
        roundTrip(RouteResult.serializer(), RouteResult("r", 10.0, 7, true, Place("x", Shibuya), "fell back"))
        roundTrip(SetSpeedParams.serializer(), SetSpeedParams(multiplier = 2.0))
        roundTrip(PlacesSearchParams.serializer(), PlacesSearchParams("cafe", Shibuya, 5))
        roundTrip(ListSerializer(Place.serializer()), listOf(Place("a", TokyoTower, "addr", "kind")))
        roundTrip(ListSerializer(Favorite.serializer()), listOf(Favorite("1", "Home", TokyoTower, "f", "#2F6BFF", 5)))
        roundTrip(FavoriteSaveParams.serializer(), FavoriteSaveParams("Home", 1.0, 2.0))
        roundTrip(FavoriteDeleteParams.serializer(), FavoriteDeleteParams(name = "Home"))
        roundTrip(SubscribeParams.serializer(), SubscribeParams(listOf("fix", "event.state")))
    }

    @Test
    fun nullsAreOmittedAndDefaultsEncoded() {
        val text = ProtocolJson.encodeToString(SetLocationParams.serializer(), SetLocationParams(query = "x"))
        assertEquals("""{"query":"x"}""", text)
        val route = ProtocolJson.encodeToString(RoutePlayParams.serializer(), RoutePlayParams(gpx = "g"))
        assertEquals("""{"gpx":"g","followRoads":false,"loop":"Once"}""", route)
    }

    @Test
    fun everyStateRoundTripsWithShortDiscriminator() {
        val states = listOf(
            HauntState.Idle,
            HauntState.Holding(fixAt(TokyoTower), "Tokyo Tower"),
            moving,
            HauntState.Joystick(fixAt(Shibuya), Speed.Cycle, 45.0, 12.5),
        )
        for (state in states) {
            val text = roundTrip(StatusResult.serializer(), StatusResult(state, fixAt(TokyoTower), true))
            val obj = ProtocolJson.parseToJsonElement(text).jsonObject["state"]!!.jsonObject
            assertEquals(state.typeName, obj["type"]!!.jsonPrimitive.content)
        }
        val movingJson = ProtocolJson.encodeToJsonElement(HauntStateSerializer, moving).jsonObject
        // Speed is a value class: plain m/s on the wire.
        assertEquals(JsonPrimitive(1.4), movingJson["speed"])
        assertEquals(JsonPrimitive("PingPong"), movingJson["loop"])
    }

    @Test
    fun eventsRoundTrip() {
        val events = listOf(
            HauntEvent.FixEvent(fixAt(TokyoTower)),
            HauntEvent.StateEvent(moving),
            HauntEvent.RouteProgressEvent("r1", RouteProgress(1.0, 2.0, null)),
            HauntEvent.RouteFinishedEvent("r1", FinishReason.Stopped, fixAt(Shibuya)),
            HauntEvent.ErrorEvent(ErrorCodes.MOCK_APP_NOT_SELECTED, "nope", ErrorHints.MOCK_APP_NOT_SELECTED),
        )
        for (event in events) {
            val method = EventNames.methodOf(event)
            roundTrip(EventNames.serializerFor(method)!!, event)
        }
    }

    @Test
    fun envelopesRoundTripThroughLines() {
        val request = JsonRpcRequest(JsonPrimitive(7), Methods.STATUS)
        assertEquals("""{"id":7,"method":"status","jsonrpc":"2.0"}""", request.encodeLine())
        assertEquals(request, parseRpcMessage(request.encodeLine()))

        val stringId = JsonRpcRequest(JsonPrimitive("abc"), Methods.LOCATION_SET, JsonObject(mapOf("lat" to JsonPrimitive(1.0))))
        assertEquals(stringId, parseRpcMessage(stringId.encodeLine()))

        val notification = JsonRpcNotification(EventNames.FIX, JsonObject(emptyMap()))
        assertEquals(notification, parseRpcMessage(notification.encodeLine()))

        val ok = JsonRpcResponse(JsonPrimitive(7), result = JsonObject(emptyMap()))
        assertEquals(ok, parseRpcMessage(ok.encodeLine()))

        val nullResult = parseRpcMessage("""{"jsonrpc":"2.0","id":1,"result":null}""")
        assertIs<JsonRpcResponse>(nullResult)
        assertEquals(JsonNull, nullResult.result)

        val err = JsonRpcResponse(JsonNull, error = RpcError(ErrorCodes.PARSE_ERROR, "bad"))
        val errLine = err.encodeLine()
        assertTrue(""""id":null""" in errLine, errLine)
        assertEquals(err, parseRpcMessage(errLine))
    }

    @Test
    fun malformedLinesAreRejectedWithTheRightCode() {
        fun code(line: String) = assertFailsWith<RpcParseException> { parseRpcMessage(line) }.error.code
        assertEquals(ErrorCodes.PARSE_ERROR, code("{not json"))
        assertEquals(ErrorCodes.INVALID_REQUEST, code("[1,2]"))
        assertEquals(ErrorCodes.INVALID_REQUEST, code("""{"id":1,"method":"status"}""")) // no jsonrpc
        assertEquals(ErrorCodes.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":1,"method":5}"""))
        assertEquals(ErrorCodes.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":{},"method":"status"}"""))
        assertEquals(ErrorCodes.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":1}"""))
        val withId = assertFailsWith<RpcParseException> { parseRpcMessage("""{"jsonrpc":"1.0","id":9,"method":"x"}""") }
        assertEquals(JsonPrimitive(9), withId.id)
    }

    @Test
    fun errorDataCarriesHints() {
        val e = RpcException.mockAppNotSelected()
        assertEquals(ErrorHints.MOCK_APP_NOT_SELECTED, e.error.details?.hint)
        val decoded = RpcException(ProtocolJson.decodeFromString(RpcError.serializer(), ProtocolJson.encodeToString(RpcError.serializer(), e.error)))
        assertEquals(ErrorHints.MOCK_APP_NOT_SELECTED, decoded.hint)
        // Default hint for well-known codes even without data.
        assertEquals(ErrorHints.ADB_CONTROL_DISABLED, RpcException(ErrorCodes.ADB_CONTROL_DISABLED, "off").hint)
    }

    @Test
    fun eventNamesNormalise() {
        assertEquals(EventNames.FIX, EventNames.normalize("fix"))
        assertEquals(EventNames.ROUTE_FINISHED, EventNames.normalize("event.routeFinished"))
        assertEquals(EventNames.ROUTE_FINISHED, EventNames.normalize("routefinished"))
        assertEquals(null, EventNames.normalize("nope"))
    }

    @Test
    fun validation() {
        fun invalid(block: () -> Unit) =
            assertEquals(ErrorCodes.INVALID_PARAMS, assertFailsWith<RpcException> { block() }.code)
        invalid { SetLocationParams().validate() }
        invalid { SetLocationParams(lat = 1.0).validate() }
        invalid { SetLocationParams(lat = 91.0, lng = 0.0).validate() }
        invalid { SetLocationParams(lat = 1.0, lng = 2.0, query = "x").validate() }
        invalid { SetLocationParams(query = " ").validate() }
        invalid { SetLocationParams(lat = 1.0, lng = 2.0, accuracy = 0f).validate() }
        SetLocationParams(lat = -33.8, lng = 151.2).validate()
        invalid { RoutePlayParams().validate() }
        invalid { RoutePlayParams(gpx = "a", kml = "b").validate() }
        invalid { RoutePlayParams(waypoints = listOf(TokyoTower)).validate() }
        invalid { RoutePlayParams(gpx = "a", metersPerSecond = 1.0, multiplier = 2.0).validate() }
        invalid { RoutePlayParams(gpx = "a", metersPerSecond = -1.0).validate() }
        RoutePlayParams(waypoints = listOf(TokyoTower, LatLng(0.0, 0.0))).validate()
        invalid { SetSpeedParams().validate() }
        invalid { MoveToParams().validate() }
        invalid { FavoriteSaveParams("x", lat = 1.0).validate() }
        invalid { FavoriteDeleteParams().validate() }
        invalid { PlacesSearchParams("").validate() }
    }
}
