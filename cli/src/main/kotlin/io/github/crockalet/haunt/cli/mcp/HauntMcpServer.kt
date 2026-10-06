package io.github.crockalet.haunt.cli.mcp

import io.github.crockalet.haunt.cli.CLI_VERSION
import io.github.crockalet.haunt.cli.Format
import io.github.crockalet.haunt.cli.GlobalOptions
import io.github.crockalet.haunt.cli.HauntFailure
import io.github.crockalet.haunt.cli.adb.AdbDevice
import io.github.crockalet.haunt.cli.connect.Connector
import io.github.crockalet.haunt.cli.connect.HauntSession
import io.github.crockalet.haunt.cli.parseRate
import io.github.crockalet.haunt.cli.parseSpeed
import io.github.crockalet.haunt.cli.quietLibraryLogging
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.currentFix
import io.github.crockalet.haunt.protocol.ErrorHints
import io.github.crockalet.haunt.protocol.Favorite
import io.github.crockalet.haunt.protocol.FinishReason
import io.github.crockalet.haunt.protocol.FavoriteDeleteParams
import io.github.crockalet.haunt.protocol.FavoriteSaveParams
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.HelloResult
import io.github.crockalet.haunt.protocol.MoveToParams
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.PlacesSearchParams
import io.github.crockalet.haunt.protocol.ProtocolJson
import io.github.crockalet.haunt.protocol.RouteResult
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RpcClient
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.protocol.SetLocationResult
import io.github.crockalet.haunt.protocol.SetSpeedParams
import io.github.crockalet.haunt.protocol.StatusResult
import io.github.crockalet.haunt.protocol.typeName
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** Events the MCP session listens to (needed by `wait_for_arrival`). */
private val MCP_EVENTS = listOf("routeFinished", "state", "error")

/**
 * Lazily-connected, self-healing device session for the MCP server. Connects on first use and
 * reconnects after the connection drops (app restarted, cable re-plugged).
 */
class DeviceLink(
    private val connectorFactory: () -> Connector,
    private var serial: String?,
) : AutoCloseable {
    private val mutex = Mutex()
    private var session: HauntSession? = null

    suspend fun session(): HauntSession = mutex.withLock {
        session?.takeIf { it.connected }?.let { return it }
        session?.close()
        session = null
        val s = connectorFactory().connect(serial)
        try {
            s.client.subscribe(MCP_EVENTS)
        } catch (e: Throwable) {
            s.close()
            throw e
        }
        session = s
        s
    }

    suspend fun client(): RpcClient = session().client

    fun devices(): List<AdbDevice> = connectorFactory().devices()

    suspend fun select(serial: String) = mutex.withLock {
        session?.close()
        session = null
        this.serial = serial
    }

    val selectedSerial: String? get() = serial

    override fun close() {
        session?.close()
        session = null
    }
}

/** Haunt's MCP tools on top of a [DeviceLink]. */
class HauntMcpServer(
    private val link: DeviceLink,
    private val readFile: (String) -> String = { File(it).readText() },
) {
    fun build(): Server = Server(
        Implementation(name = "haunt", version = CLI_VERSION, title = "Haunt (fake GPS for Android)"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        INSTRUCTIONS,
    ) {
        registerTools(this)
    }

    private fun registerTools(server: Server) {
        server.tool(
            "list_devices",
            "List Android devices visible to adb with their state (\"device\" = usable, \"unauthorized\" = accept the USB debugging prompt). " +
                "Use select_device when several are attached.",
            readOnly = true,
        ) {
            val devices = withContext(Dispatchers.IO) { link.devices() }
            val text = if (devices.isEmpty()) {
                "No Android devices attached. Connect a phone with USB debugging enabled (or `adb connect <ip>:<port>`)."
            } else {
                devices.joinToString("\n") { d ->
                    "${d.serial} · ${d.state}${d.model?.let { " · $it" } ?: ""}${if (d.serial == link.selectedSerial) " (selected)" else ""}"
                }
            }
            ok(text, buildJsonObject { put("devices", encode(ListSerializer(AdbDevice.serializer()), devices)) })
        }

        server.tool(
            "select_device",
            "Choose which device later tools control (by adb serial from list_devices). Only needed when several devices are attached.",
            properties = { string("serial", "adb serial, e.g. emulator-5554 or R58M123ABC") },
            required = listOf("serial"),
        ) { args ->
            val serial = args.string("serial") ?: throw IllegalArgumentException("serial is required")
            link.select(serial)
            val s = link.session()
            ok("Connected to ${s.device.displayName} (Haunt ${s.hello.appVersion}).", encode(HelloResult.serializer(), s.hello))
        }

        server.tool(
            "get_status",
            "Get Haunt's current state: Idle (not mocking), Holding (fixed fake location), Moving (route with progress, speed, ETA) " +
                "or Joystick, plus the current fake fix and whether Haunt is the selected mock location app.",
            readOnly = true,
        ) {
            val s = link.session()
            val status = s.client.status()
            val text = buildString {
                append(Format.state(status.state))
                if (status.state.currentFix == null) status.lastFix?.let { append("\nlast fix: ${Format.fix(it)}") }
                append("\ndevice: ${s.device.displayName}, Haunt ${s.hello.appVersion} (${s.hello.flavour})")
                if (status.mockAppSelected == false) append("\nWARNING: Haunt is not the selected mock location app. ${ErrorHints.MOCK_APP_NOT_SELECTED}")
            }
            ok(text, encode(StatusResult.serializer(), status))
        }

        server.tool(
            "set_location",
            "Teleport: hold a fixed fake GPS location. Give lat+lng (WGS84 decimal degrees) or a free-text query " +
                "(a place name or address, geocoded on the phone). Replaces any running route.",
            properties = {
                number("lat", "Latitude, -90..90")
                number("lng", "Longitude, -180..180")
                string("query", "Place name or address instead of lat/lng, e.g. \"Tokyo Tower\"")
                number("altitude", "Altitude in metres (optional)")
                number("accuracy", "Horizontal accuracy in metres (optional, default 5)")
            },
        ) { args ->
            val result = link.client().setLocation(
                SetLocationParams(
                    lat = args.double("lat"),
                    lng = args.double("lng"),
                    altitude = args.double("altitude"),
                    accuracy = args.double("accuracy")?.toFloat(),
                    query = args.string("query"),
                ),
            )
            val where = result.place?.let { Format.place(it) } ?: Format.latLng(result.fix.position)
            ok("Holding at $where.", encode(SetLocationResult.serializer(), result))
        }

        server.tool(
            "move_to",
            "Travel from the current fake position to a target at a given speed. Returns immediately with distance and ETA; " +
                "call wait_for_arrival to block until it gets there. Target: lat+lng or query (place name, geocoded on the phone).",
            properties = {
                number("lat", "Target latitude")
                number("lng", "Target longitude")
                string("query", "Target place name or address instead of lat/lng")
                speedProperty()
                boolean("follow_roads", "Follow roads using the phone's routing service (default false = straight line)")
            },
        ) { args ->
            val result = link.client().moveTo(
                MoveToParams(
                    lat = args.double("lat"),
                    lng = args.double("lng"),
                    query = args.string("query"),
                    metersPerSecond = args.string("speed")?.let { parseSpeed(it).metersPerSecond },
                    followRoads = args.bool("follow_roads") ?: false,
                ),
            )
            ok(Format.route(result), encode(RouteResult.serializer(), result))
        }

        server.tool(
            "play_route",
            "Play a route: either waypoints (at least 2), a GPX/KML document as text, or a path to a local .gpx/.kml file. " +
                "Returns immediately with distance and ETA; use wait_for_arrival to block until it finishes.",
            properties = {
                putJsonObject("waypoints") {
                    put("type", "array")
                    put("description", "Ordered points [{lat, lng}, …], at least 2")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("lat") { put("type", "number") }
                            putJsonObject("lng") { put("type", "number") }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("lat")); add(JsonPrimitive("lng")) })
                    }
                }
                string("gpx", "GPX 1.1 document text")
                string("kml", "KML document text")
                string("file", "Path to a .gpx or .kml file on this computer")
                speedProperty()
                number("rate", "Replay a timed GPX track at this multiple of its recorded speed, e.g. 2 (instead of speed)")
                boolean("follow_roads", "Snap the path between waypoints to roads")
                enum("loop", "once (default), loop (restart at the end) or pingpong (back and forth)", listOf("once", "loop", "pingpong"))
                string("name", "Route name shown on the phone")
            },
        ) { args ->
            val waypoints = (args["waypoints"] as? JsonArray)?.map { p ->
                val o = p.jsonObject
                LatLng(o.double("lat") ?: throw IllegalArgumentException("waypoint lat missing"), o.double("lng") ?: throw IllegalArgumentException("waypoint lng missing"))
            }
            var gpx = args.string("gpx")
            var kml = args.string("kml")
            args.string("file")?.let { path ->
                val text = try {
                    withContext(Dispatchers.IO) { readFile(path) }
                } catch (e: IOException) {
                    throw IllegalArgumentException("cannot read $path: ${e.message}")
                }
                if (path.endsWith(".kml", ignoreCase = true) || "<kml" in text.take(2000)) kml = text else gpx = text
            }
            val result = link.client().playRoute(
                RoutePlayParams(
                    waypoints = waypoints,
                    gpx = gpx,
                    kml = kml,
                    metersPerSecond = args.string("speed")?.let { parseSpeed(it).metersPerSecond },
                    multiplier = args.double("rate"),
                    followRoads = args.bool("follow_roads") ?: false,
                    loop = when (args.string("loop")?.lowercase()) {
                        null, "once" -> LoopMode.Once
                        "loop" -> LoopMode.Loop
                        "pingpong", "ping-pong" -> LoopMode.PingPong
                        else -> throw IllegalArgumentException("loop must be once, loop or pingpong")
                    },
                    name = args.string("name") ?: args.string("file")?.let { File(it).nameWithoutExtension },
                ),
            )
            ok(Format.route(result, "Playing"), encode(RouteResult.serializer(), result))
        }

        server.tool("pause", "Pause the running route; the fake location stays where it is.") {
            link.client().pause()
            ok("Paused.")
        }

        server.tool("resume", "Resume a paused route.") {
            link.client().resume()
            ok("Resumed.")
        }

        server.tool(
            "stop",
            "Stop mocking so the phone returns to its real GPS. With hold=true, only stop moving and keep holding the current fake position.",
            properties = { boolean("hold", "Keep holding the current fake position instead of stopping mocking") },
        ) { args ->
            val hold = args.bool("hold") ?: false
            if (hold) link.client().stopPlayback() else link.client().stopLocation()
            ok(if (hold) "Stopped moving; holding the current position." else "Stopped mocking; the phone uses its real location again.")
        }

        server.tool(
            "set_speed",
            "Change the speed of the running route: a speed (walk, cycle, drive, \"30kmh\", \"5mps\", \"20mph\") or a multiplier of the current speed / recorded timing.",
            properties = {
                speedProperty()
                number("multiplier", "e.g. 2 for twice as fast, 0.5 for half speed")
            },
        ) { args ->
            val params = SetSpeedParams(
                metersPerSecond = args.string("speed")?.let { parseSpeed(it).metersPerSecond },
                multiplier = args.double("multiplier") ?: args.string("multiplier")?.let(::parseRate),
            )
            link.client().setSpeed(params)
            ok("Speed updated.")
        }

        server.tool(
            "search_place",
            "Search places by name or address (geocoded on the phone, biased near the current location unless near_lat/near_lng are given). " +
                "Use the returned coordinates with set_location or move_to.",
            properties = {
                string("query", "What to search for, e.g. \"Shibuya Station\" or \"coffee\"")
                number("near_lat", "Bias results around this latitude")
                number("near_lng", "Bias results around this longitude")
                integer("limit", "Maximum results (default 5)")
            },
            required = listOf("query"),
            readOnly = true,
        ) { args ->
            val nearLat = args.double("near_lat")
            val nearLng = args.double("near_lng")
            val places = link.client().searchPlaces(
                PlacesSearchParams(
                    query = args.string("query") ?: throw IllegalArgumentException("query is required"),
                    near = if (nearLat != null && nearLng != null) LatLng(nearLat, nearLng) else null,
                    limit = args.int("limit") ?: 5,
                ),
            )
            val text = if (places.isEmpty()) "No results." else places.mapIndexed { i, p -> "${i + 1}. ${Format.place(p)}" }.joinToString("\n")
            ok(text, buildJsonObject { put("places", encode(ListSerializer(Place.serializer()), places)) })
        }

        server.tool("list_favorites", "List saved favourite places.", readOnly = true) {
            val favorites = link.client().listFavorites()
            val text = if (favorites.isEmpty()) "No favourites." else favorites.joinToString("\n") { "${it.name} · ${Format.latLng(it.position)}" }
            ok(text, buildJsonObject { put("favorites", encode(ListSerializer(Favorite.serializer()), favorites)) })
        }

        server.tool(
            "save_favorite",
            "Save a favourite place by name at lat/lng, or at the current fake location when lat/lng are omitted. Same name overwrites.",
            properties = {
                string("name", "Favourite name")
                number("lat", "Latitude (optional)")
                number("lng", "Longitude (optional)")
                string("folder", "Folder (optional)")
            },
            required = listOf("name"),
        ) { args ->
            val f = link.client().saveFavorite(
                FavoriteSaveParams(
                    name = args.string("name") ?: throw IllegalArgumentException("name is required"),
                    lat = args.double("lat"),
                    lng = args.double("lng"),
                    folder = args.string("folder"),
                ),
            )
            ok("Saved ${f.name} at ${Format.latLng(f.position)}.", encode(Favorite.serializer(), f))
        }

        server.tool(
            "delete_favorite",
            "Delete a saved favourite by name.",
            properties = { string("name", "Favourite name") },
            required = listOf("name"),
            destructive = true,
        ) { args ->
            val name = args.string("name") ?: throw IllegalArgumentException("name is required")
            link.client().deleteFavorite(FavoriteDeleteParams(name = name))
            ok("Deleted $name.")
        }

        server.tool(
            "wait_for_arrival",
            "Block until the running route or move_to finishes, or until the timeout. Returns arrived=true with the final position, " +
                "or arrived=false with current progress on timeout (call again to keep waiting). Returns immediately if nothing is moving.",
            properties = {
                integer("timeout_seconds", "Maximum seconds to wait (default 120, max 3600)")
                string("route_id", "Only wait for this routeId (from move_to / play_route)")
            },
            readOnly = true,
        ) { args ->
            val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(1, 3600)
            val routeId = args.string("route_id")
            val client = link.client()
            coroutineScope {
                val finished = async(start = CoroutineStart.UNDISPATCHED) {
                    client.events.filterIsInstance<HauntEvent.RouteFinishedEvent>()
                        .filter { routeId == null || it.routeId == null || it.routeId == routeId }
                        .first()
                }
                val status = client.status()
                val state = status.state
                if (state !is HauntState.Moving) {
                    finished.cancel()
                    val at = state.currentFix?.let { " at ${Format.latLng(it.position)}" } ?: ""
                    return@coroutineScope ok(
                        "Not moving (${state.typeName})$at.",
                        buildJsonObject {
                            put("arrived", true)
                            put("state", state.typeName)
                            state.currentFix?.let { put("fix", encode(Fix.serializer(), it)) }
                        },
                    )
                }
                val note = if (state.paused) " (route is paused; call resume)" else ""
                val event = withTimeoutOrNull(timeout.seconds) { finished.await() }
                if (event == null) {
                    finished.cancel()
                    val now = runCatching { client.status().state }.getOrNull()
                    val progress = (now as? HauntState.Moving)?.let { Format.state(it).lineSequence().first() } ?: now?.let { Format.state(it) } ?: "unknown"
                    ok(
                        "Still moving after ${timeout}s$note: $progress",
                        buildJsonObject {
                            put("arrived", false)
                            now?.let { put("state", it.typeName) }
                            (now as? HauntState.Moving)?.let {
                                put("traveledMeters", it.progress.traveledMeters)
                                put("totalMeters", it.progress.totalMeters)
                                it.progress.etaSeconds?.let { eta -> put("etaSeconds", eta) }
                            }
                        },
                    )
                } else {
                    val at = event.fix?.let { " at ${Format.latLng(it.position)}" } ?: ""
                    ok(
                        "Route ${event.reason.name.lowercase()}$at.",
                        buildJsonObject {
                            put("arrived", event.reason == FinishReason.Arrived)
                            put("reason", event.reason.name)
                            event.routeId?.let { put("routeId", it) }
                            event.fix?.let { put("fix", encode(Fix.serializer(), it)) }
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val INSTRUCTIONS =
            "Haunt fakes the GPS location of an Android phone connected over adb. Typical flow: get_status → " +
                "set_location (teleport) or move_to / play_route (travel at a speed) → wait_for_arrival. " +
                "Coordinates are WGS84 decimal degrees; speeds accept walk, cycle, drive or values like \"30kmh\". " +
                "If a tool says Haunt is not the selected mock location app, ask the user to open Developer options → " +
                "Select mock location app → Haunt. If it says ADB control is disabled, ask them to enable it in Haunt → Settings."
    }
}

// ----- tool registration helpers -----

private class SchemaBuilder(val props: JsonObjectBuilder) {
    fun string(name: String, description: String) = prop(name, "string", description)
    fun number(name: String, description: String) = prop(name, "number", description)
    fun integer(name: String, description: String) = prop(name, "integer", description)
    fun boolean(name: String, description: String) = prop(name, "boolean", description)
    fun enum(name: String, description: String, values: List<String>) = props.putJsonObject(name) {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    fun speedProperty() = string("speed", "walk (default, 5 km/h), cycle (18 km/h), drive (50 km/h), or e.g. \"30kmh\", \"5mps\", \"20mph\"")

    fun putJsonObject(name: String, block: JsonObjectBuilder.() -> Unit) = props.putJsonObject(name, block)

    private fun prop(name: String, type: String, description: String) = props.putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}

private fun Server.tool(
    name: String,
    description: String,
    properties: SchemaBuilder.() -> Unit = {},
    required: List<String> = emptyList(),
    readOnly: Boolean = false,
    destructive: Boolean = false,
    handler: suspend (JsonObject) -> CallToolResult,
) {
    val props = buildJsonObject { SchemaBuilder(this).properties() }
    addTool(
        name = name,
        description = description,
        inputSchema = ToolSchema(properties = props, required = required.ifEmpty { null }),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = readOnly,
            destructiveHint = destructive,
            openWorldHint = true,
        ),
    ) { request ->
        try {
            handler(request.arguments ?: JsonObject(emptyMap()))
        } catch (e: HauntFailure) {
            toolError(e.describeInline())
        } catch (e: RpcException) {
            toolError(HauntFailure.from(e).describeInline())
        } catch (e: IllegalArgumentException) {
            toolError("Invalid arguments: ${e.message}")
        }
    }
}

private fun ok(text: String, structured: JsonElement? = null) = CallToolResult(
    content = listOf(TextContent(text)),
    structuredContent = structured as? JsonObject,
)

private fun toolError(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)

private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = ProtocolJson.encodeToJsonElement(serializer, value)

/** Lenient argument readers: LLMs sometimes send numbers as strings. */
private fun JsonObject.prim(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.string(key: String): String? = prim(key)?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.double(key: String): Double? = prim(key)?.let {
    it.doubleOrNull ?: throw IllegalArgumentException("$key must be a number")
}

private fun JsonObject.int(key: String): Int? = prim(key)?.let {
    it.intOrNull ?: it.doubleOrNull?.toInt() ?: throw IllegalArgumentException("$key must be an integer")
}

private fun JsonObject.bool(key: String): Boolean? = prim(key)?.let {
    it.booleanOrNull ?: throw IllegalArgumentException("$key must be true or false")
}

/** Runs the MCP server on stdio until stdin closes. */
suspend fun runMcpServer(global: GlobalOptions) {
    val link = DeviceLink({ global.connector("haunt-mcp/$CLI_VERSION") }, global.serial)
    val server = HauntMcpServer(link, global.deps.readFile).build()
    quietLibraryLogging()
    val (input, output) = global.deps.mcpStreams ?: run {
        // stdout carries MCP frames only: hand the transport the raw stream and send any stray println to stderr.
        System.out.flush()
        val stdout = FileOutputStream(FileDescriptor.out)
        System.setOut(System.err)
        System.`in` to stdout
    }
    val transport = StdioServerTransport(input = input.asSource().buffered(), output = output.asSink().buffered())
    val closed = CompletableDeferred<Unit>()
    val session = server.createSession(transport)
    session.onClose { closed.complete(Unit) }
    try {
        closed.await()
    } finally {
        link.close()
    }
}
