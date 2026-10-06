package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.cli.mcp.DeviceLink
import io.github.crockalet.haunt.cli.mcp.HauntMcpServer
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.protocol.FinishReason
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.RpcException
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** In-process MCP transport pair. */
private class LinkedTransport(private val scope: CoroutineScope) : AbstractTransport() {
    lateinit var peer: LinkedTransport
    private val inbox = Channel<JSONRPCMessage>(Channel.UNLIMITED)

    override suspend fun start() {
        scope.launch { for (m in inbox) _onMessage(m) }
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        peer.inbox.send(message)
    }

    override suspend fun close() {
        inbox.close()
        invokeOnCloseCallback()
    }

    companion object {
        fun pair(scope: CoroutineScope): Pair<LinkedTransport, LinkedTransport> {
            val a = LinkedTransport(scope)
            val b = LinkedTransport(scope)
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

class McpServerTest {
    private val device = FakeDevice()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        device.close()
    }

    private fun withMcp(block: suspend (Client) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val global = GlobalOptions(serial = null, json = false, adbPath = null, deps = device.deps())
            val link = DeviceLink({ global.connector("test") }, null)
            val server = HauntMcpServer(link) { path -> if (path == "t.gpx") "<gpx/>" else throw java.io.IOException("missing") }.build()
            val (clientSide, serverSide) = LinkedTransport.pair(scope)
            server.createSession(serverSide)
            val client = Client(Implementation("test", "0"))
            client.connect(clientSide)
            try {
                block(client)
            } finally {
                link.close()
            }
        }
    }

    private val CallToolResult.text get() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private suspend fun Client.call(name: String, args: Map<String, Any?> = emptyMap()) = callTool(name, args)

    @Test
    fun listsAllToolsWithSchemas() = withMcp { client ->
        val tools = client.listTools().tools.associateBy { it.name }
        val expected = setOf(
            "list_devices", "select_device", "get_status", "set_location", "move_to", "play_route", "pause", "resume",
            "stop", "set_speed", "search_place", "list_favorites", "save_favorite", "delete_favorite", "wait_for_arrival",
        )
        assertEquals(expected, tools.keys)
        assertEquals(listOf("query"), tools.getValue("search_place").inputSchema.required)
        assertTrue("lat" in tools.getValue("set_location").inputSchema.properties!!)
        assertTrue(tools.values.all { !it.description.isNullOrBlank() })
        // Lazy: nothing talked to adb yet.
        assertTrue(device.commands.isEmpty(), "unexpected adb calls: ${device.commands}")
    }

    @Test
    fun setLocationAndStatus() = withMcp { client ->
        val set = client.call("set_location", mapOf("query" to "Tokyo Tower"))
        assertNotEquals(true, set.isError, set.text)
        assertTrue("Holding at Tokyo Tower" in set.text, set.text)
        assertEquals("Tokyo Tower", device.api.lastSetLocation!!.query)

        client.call("set_location", mapOf("lat" to -33.85, "lng" to 151.21, "accuracy" to 3))
        assertEquals(-33.85, device.api.lastSetLocation!!.lat)
        assertEquals(3f, device.api.lastSetLocation!!.accuracy)

        val status = client.call("get_status")
        assertTrue(status.text.startsWith("Holding"), status.text)
        assertEquals("Holding", status.structuredContent!!["state"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // One connection reused across calls.
        assertEquals(1, device.commandsMatching("forward", "tcp:0").size)
    }

    @Test
    fun errorsAreActionableToolErrors() = withMcp { client ->
        device.api.mockAppSelected = false
        val r = client.call("set_location", mapOf("lat" to 1.0, "lng" to 2.0))
        assertEquals(true, r.isError)
        assertTrue("Select mock location app" in r.text, r.text)

        device.api.failWith = RpcException.adbControlDisabled()
        val disabled = client.call("pause")
        assertEquals(true, disabled.isError)
        assertTrue("Allow ADB control" in disabled.text, disabled.text)

        device.api.failWith = null
        val bad = client.call("set_location", mapOf("lat" to "north", "lng" to 2.0))
        assertEquals(true, bad.isError)
        assertTrue("lat must be a number" in bad.text, bad.text)

        val badSpeed = client.call("move_to", mapOf("query" to "x", "speed" to "warp"))
        assertEquals(true, badSpeed.isError)
        assertTrue("invalid speed" in badSpeed.text, badSpeed.text)
    }

    @Test
    fun noDeviceIsAToolError() = withMcp { client ->
        device.devicesOutput = "List of devices attached\n"
        val list = client.call("list_devices")
        assertNotEquals(true, list.isError)
        assertTrue("No Android devices attached" in list.text)
        val status = client.call("get_status")
        assertEquals(true, status.isError)
        assertTrue("No Android device connected" in status.text, status.text)
    }

    @Test
    fun moveToThenWaitForArrival() = withMcp { client ->
        val move = client.call("move_to", mapOf("query" to "Shibuya Station", "speed" to "cycle", "follow_roads" to true))
        assertTrue("Moving to Shibuya Station" in move.text, move.text)
        assertEquals(5.0, device.api.lastMoveTo!!.metersPerSecond)
        assertEquals("r2", move.structuredContent!!["routeId"]!!.jsonPrimitive.content)

        val emitter = scope.launch {
            delay(300)
            device.events.emit(HauntEvent.RouteFinishedEvent("r2", FinishReason.Arrived, Fix(TokyoTower, timeMillis = 9)))
        }
        val wait = client.call("wait_for_arrival", mapOf("timeout_seconds" to 20, "route_id" to "r2"))
        emitter.join()
        assertNotEquals(true, wait.isError, wait.text)
        assertTrue(wait.structuredContent!!["arrived"]!!.jsonPrimitive.boolean, wait.text)
        assertTrue("Route arrived at 35.658600, 139.745400" in wait.text, wait.text)
    }

    @Test
    fun waitForArrivalReturnsWhenIdleOrTimedOut() = withMcp { client ->
        val idle = client.call("wait_for_arrival")
        assertTrue("Not moving (Idle)" in idle.text, idle.text)

        client.call("move_to", mapOf("lat" to 35.66, "lng" to 139.70))
        val timedOut = client.call("wait_for_arrival", mapOf("timeout_seconds" to 1))
        assertEquals(false, timedOut.structuredContent!!["arrived"]!!.jsonPrimitive.boolean)
        assertTrue("Still moving after 1s" in timedOut.text, timedOut.text)
    }

    @Test
    fun playRouteVariants() = withMcp { client ->
        client.call(
            "play_route",
            mapOf(
                "waypoints" to listOf(mapOf("lat" to 1.0, "lng" to 2.0), mapOf("lat" to 1.1, "lng" to 2.1)),
                "speed" to "30kmh",
                "loop" to "pingpong",
            ),
        ).also { assertNotEquals(true, it.isError, it.text) }
        assertEquals(2, device.api.lastRoute!!.waypoints!!.size)

        client.call("play_route", mapOf("file" to "t.gpx", "rate" to 2)).also { assertNotEquals(true, it.isError, it.text) }
        assertEquals("<gpx/>", device.api.lastRoute!!.gpx)
        assertEquals(2.0, device.api.lastRoute!!.multiplier)
        assertEquals("t", device.api.lastRoute!!.name)

        val missing = client.call("play_route", mapOf("file" to "nope.gpx"))
        assertEquals(true, missing.isError)
    }

    @Test
    fun favoritesSearchAndControls() = withMcp { client ->
        val search = client.call("search_place", mapOf("query" to "shibuya", "near_lat" to 35.0, "near_lng" to 139.0))
        assertEquals("Shibuya Station", search.structuredContent!!["places"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(5, device.api.lastSearch!!.limit)

        client.call("save_favorite", mapOf("name" to "Cafe", "lat" to 1.0, "lng" to 2.0))
        assertTrue("Cafe" in client.call("list_favorites").text)
        client.call("delete_favorite", mapOf("name" to "Cafe"))
        assertTrue("Cafe" !in client.call("list_favorites").text)

        client.call("pause")
        client.call("resume")
        client.call("set_speed", mapOf("multiplier" to 2))
        assertEquals(2.0, device.api.lastSpeed!!.multiplier)
        client.call("stop", mapOf("hold" to true))
        client.call("stop")
        assertEquals(
            listOf("playback.pause", "playback.resume", "playback.setSpeed", "playback.stop", "location.stop"),
            device.api.calls.filter { it.startsWith("playback") || it == "location.stop" },
        )
    }
}
