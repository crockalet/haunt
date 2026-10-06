package io.github.crockalet.haunt.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.github.crockalet.haunt.cli.adb.Adb
import io.github.crockalet.haunt.cli.adb.AdbDevice
import io.github.crockalet.haunt.cli.adb.AdbLocator
import io.github.crockalet.haunt.cli.adb.ProcessRunner
import io.github.crockalet.haunt.cli.adb.SystemProcessRunner
import io.github.crockalet.haunt.cli.connect.Connector
import io.github.crockalet.haunt.cli.connect.HauntSession
import io.github.crockalet.haunt.cli.connect.SocketLineTransport
import io.github.crockalet.haunt.cli.connect.TransportFactory
import io.github.crockalet.haunt.cli.mcp.runMcpServer
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.currentFix
import io.github.crockalet.haunt.protocol.EventNames
import io.github.crockalet.haunt.protocol.FavoriteDeleteParams
import io.github.crockalet.haunt.protocol.FavoriteSaveParams
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.HelloResult
import io.github.crockalet.haunt.protocol.MoveToParams
import io.github.crockalet.haunt.protocol.PlacesSearchParams
import io.github.crockalet.haunt.protocol.ProtocolJson
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.protocol.SetSpeedParams
import io.github.crockalet.haunt.protocol.StatusResult
import io.github.crockalet.haunt.protocol.Favorite
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.RouteResult
import io.github.crockalet.haunt.protocol.SetLocationResult
import io.github.crockalet.haunt.protocol.ErrorHints
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

const val CLI_VERSION = "0.1.0"

/** External dependencies, swappable in tests. */
class CliDeps(
    val locator: AdbLocator = AdbLocator(),
    val runner: ProcessRunner = SystemProcessRunner,
    val transports: TransportFactory = TransportFactory { SocketLineTransport.connect(it) },
    val readFile: (String) -> String = { File(it).readText() },
    /** Used by `haunt mcp`; null = real stdin/stdout. */
    val mcpStreams: Pair<java.io.InputStream, java.io.OutputStream>? = null,
    val fastRetries: Boolean = false,
)

/** Global options, shared with subcommands through the Clikt context. */
class GlobalOptions(val serial: String?, val json: Boolean, val adbPath: String?, val deps: CliDeps) {
    fun adb(): Adb = Adb(deps.locator.locate(adbPath), deps.runner)

    fun connector(clientName: String = "haunt-cli/$CLI_VERSION"): Connector = if (deps.fastRetries) {
        Connector(adb(), deps.transports, clientName, retryDelay = 1.milliseconds, startupWait = 5.milliseconds)
    } else {
        Connector(adb(), deps.transports, clientName)
    }
}

class HauntCli(private val deps: CliDeps = CliDeps()) : SuspendingCliktCommand("haunt") {
    private val serial by option(
        "-s", "--serial",
        help = "Device serial (see `haunt devices`). Defaults to \$ANDROID_SERIAL or the only connected device.",
        envvar = "ANDROID_SERIAL",
    )
    private val json by option("--json", help = "Machine-readable JSON output on stdout.").flag()
    private val adb by option("--adb", help = "Path to the adb executable.", metavar = "PATH")

    init {
        versionOption(CLI_VERSION)
        subcommands(
            DevicesCommand(), StatusCommand(), SetCommand(), GoCommand(), PlayCommand(),
            PauseCommand(), ResumeCommand(), StopCommand(), SpeedCommand(), SearchCommand(),
            WatchCommand(), FavCommand(), McpCommand(),
        )
    }

    override fun help(context: Context) =
        "Control Haunt (fake GPS for Android) over adb. Exit codes: 0 ok, 1 error, 2 not connected / no device, " +
            "3 mock location app not selected, 4 ADB control disabled in Haunt."

    override suspend fun run() {
        currentContext.data[Context.DEFAULT_OBJ_KEY] = GlobalOptions(serial, json, adb, deps)
    }
}

/** Base for subcommands: global options, `--json`, error → exit-code mapping. */
abstract class HauntCommand(name: String) : SuspendingCliktCommand(name) {
    protected val global by requireObject<GlobalOptions>()
    private val localJson by option("--json", help = "Machine-readable JSON output on stdout.").flag()
    protected val json: Boolean get() = localJson || global.json

    protected abstract suspend fun execute()

    override suspend fun run() {
        val failure = try {
            execute()
            null
        } catch (e: HauntFailure) {
            e
        } catch (e: RpcException) {
            HauntFailure.from(e)
        }
        if (failure != null) {
            if (json) {
                echo(
                    buildJsonObject {
                        put(
                            "error",
                            buildJsonObject {
                                put("message", failure.message)
                                failure.hint?.let { put("hint", it) }
                                failure.rpcCode?.let { put("code", it) }
                                put("exit", failure.exit.code)
                            },
                        )
                    }.toString(),
                )
            } else {
                echo("error: ${failure.describe()}", err = true)
            }
            throw ProgramResult(failure.exit.code)
        }
    }

    protected suspend fun <T> withSession(block: suspend (HauntSession) -> T): T {
        val session = global.connector().connect(global.serial)
        try {
            return block(session)
        } finally {
            session.close()
        }
    }

    protected fun <T> printJson(serializer: KSerializer<T>, value: T) = echo(ProtocolJson.encodeToString(serializer, value))

    protected fun printJson(element: JsonElement) = echo(element.toString())

    protected fun okJson() = printJson(buildJsonObject { put("ok", true) })
}

/** Converts option values with [parse], turning IllegalArgumentException into a usage error. */
private fun <T : Any> RawOption.parsed(parse: (String) -> T) = convert { text ->
    try {
        parse(text)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid value")
    }
}

private fun RawOption.number() = parsed { parseNumber(it) ?: throw IllegalArgumentException("\"$it\" is not a number") }

private fun targetOf(args: List<String>): Target = try {
    parseTarget(args)
} catch (e: IllegalArgumentException) {
    throw BadParameterValue(e.message ?: "invalid target")
}

class DevicesCommand : HauntCommand("devices") {
    override fun help(context: Context) = "List devices visible to adb."

    override suspend fun execute() {
        val devices = global.adb().devices()
        if (json) {
            printJson(ListSerializer(AdbDevice.serializer()), devices)
        } else {
            devices.forEach { d ->
                echo(listOfNotNull(d.serial.padEnd(24), d.state.padEnd(13), d.model).joinToString(" ").trimEnd())
            }
        }
        if (devices.isEmpty()) {
            if (json) throw ProgramResult(ExitCode.NOT_CONNECTED.code) // `[]` is the whole JSON answer
            throw HauntFailure.notConnected(
                "No Android devices attached",
                "Connect a phone with USB debugging enabled (or `adb connect <ip>:<port>`).",
            )
        }
    }
}

class StatusCommand : HauntCommand("status") {
    override fun help(context: Context) = "Show what Haunt is doing on the device."

    override suspend fun execute() = withSession { s ->
        val status = s.client.status()
        if (json) {
            printJson(
                buildJsonObject {
                    put("device", ProtocolJson.encodeToJsonElement(AdbDevice.serializer(), s.device))
                    put("app", ProtocolJson.encodeToJsonElement(HelloResult.serializer(), s.hello))
                    ProtocolJson.encodeToJsonElement(StatusResult.serializer(), status).jsonObject.forEach { (k, v) -> put(k, v) }
                },
            )
        } else {
            echo(Format.state(status.state))
            if (status.state.currentFix == null) status.lastFix?.let { echo("  last ${Format.fix(it)}") }
            echo("device ${s.device.displayName} · Haunt ${s.hello.appVersion} (${s.hello.flavour})")
            if (status.mockAppSelected == false || !s.hello.mockAppSelected) {
                echo("warning: Haunt is not the selected mock location app\nhint: ${ErrorHints.MOCK_APP_NOT_SELECTED}", err = true)
            }
        }
    }
}

class SetCommand : HauntCommand("set") {
    override fun help(context: Context) =
        "Hold a fixed location: `haunt set 35.6586 139.7454`, `haunt set \"35.6586, 139.7454\"` or `haunt set \"Tokyo Tower\"` (geocoded on the device)."

    private val target by argument("TARGET", help = "lat lng, \"lat,lng\" or a place name").multiple(required = true)
    private val alt by option("--alt", help = "Altitude in metres.").number()
    private val acc by option("--acc", help = "Horizontal accuracy in metres (default 5).").number()
    private val label by option("--label", help = "Label shown in the notification and status.")

    override suspend fun execute() {
        val t = targetOf(target)
        val params = when (t) {
            is Target.Coordinates -> SetLocationParams(t.position.lat, t.position.lng, alt, acc?.toFloat(), label = label)
            is Target.Query -> SetLocationParams(altitude = alt, accuracy = acc?.toFloat(), query = t.text, label = label)
        }
        withSession { s ->
            val result = s.client.setLocation(params)
            if (json) {
                printJson(SetLocationResult.serializer(), result)
            } else {
                val name = result.place?.let { " · ${Format.place(it)}" } ?: label?.let { " · $it" } ?: ""
                echo("Holding ${Format.latLng(result.fix.position)}$name")
            }
        }
    }
}

class GoCommand : HauntCommand("go") {
    override fun help(context: Context) =
        "Travel from the current position to a place or coordinates: `haunt go \"Shibuya Station\" --speed walk --roads`."

    private val target by argument("TARGET", help = "lat lng, \"lat,lng\" or a place name").multiple(required = true)
    private val speed by option("--speed", help = "walk (default), cycle, drive, or e.g. 30kmh, 5mps, 20mph.")
        .parsed(::parseSpeed)
    private val roads by option("--roads", help = "Follow roads (routing service on the device).").flag()

    override suspend fun execute() {
        val params = when (val t = targetOf(target)) {
            is Target.Coordinates -> MoveToParams(t.position.lat, t.position.lng, metersPerSecond = speed?.metersPerSecond, followRoads = roads)
            is Target.Query -> MoveToParams(query = t.text, metersPerSecond = speed?.metersPerSecond, followRoads = roads)
        }
        withSession { s ->
            val result = s.client.moveTo(params)
            if (json) printJson(RouteResult.serializer(), result) else echo(Format.route(result))
        }
    }
}

class PlayCommand : HauntCommand("play") {
    override fun help(context: Context) =
        "Play a GPX or KML track: `haunt play track.gpx --rate 2x` (recorded timing) or `--speed 30kmh` (fixed speed)."

    private val file by argument("FILE", help = ".gpx or .kml file")
    private val rate by option("--rate", help = "Replay recorded timing at N× (e.g. 2x).").parsed(::parseRate)
    private val speed by option("--speed", help = "Fixed speed: walk, cycle, drive, 30kmh, 5mps, 20mph.").parsed(::parseSpeed)
    private val loop by option("--loop", help = "Restart from the beginning at the end.").flag()
    private val pingpong by option("--pingpong", help = "Go back and forth.").flag()
    private val roads by option("--roads", help = "Snap to roads between points.").flag()
    private val name by option("--name", help = "Route name shown in status (default: file name).")

    override suspend fun execute() {
        if (rate != null && speed != null) throw UsageError("use either --rate or --speed, not both")
        if (loop && pingpong) throw UsageError("use either --loop or --pingpong, not both")
        val text = try {
            global.deps.readFile(file)
        } catch (e: IOException) {
            throw HauntFailure(ExitCode.ERROR, "Cannot read $file: ${e.message}")
        }
        val kind = when {
            file.endsWith(".gpx", ignoreCase = true) -> "gpx"
            file.endsWith(".kml", ignoreCase = true) -> "kml"
            "<gpx" in text.take(2000) -> "gpx"
            "<kml" in text.take(2000) -> "kml"
            else -> throw HauntFailure(ExitCode.ERROR, "$file is not a GPX or KML file")
        }
        val params = RoutePlayParams(
            gpx = text.takeIf { kind == "gpx" },
            kml = text.takeIf { kind == "kml" },
            metersPerSecond = speed?.metersPerSecond,
            multiplier = rate,
            followRoads = roads,
            loop = when {
                loop -> LoopMode.Loop
                pingpong -> LoopMode.PingPong
                else -> LoopMode.Once
            },
            name = name ?: File(file).nameWithoutExtension,
        )
        withSession { s ->
            val result = s.client.playRoute(params)
            if (json) printJson(RouteResult.serializer(), result) else echo(Format.route(result, "Playing ${params.name}"))
        }
    }
}

class PauseCommand : HauntCommand("pause") {
    override fun help(context: Context) = "Pause route playback (keeps the current position)."

    override suspend fun execute() = withSession { s ->
        s.client.pause()
        if (json) okJson() else echo("Paused.")
    }
}

class ResumeCommand : HauntCommand("resume") {
    override fun help(context: Context) = "Resume route playback."

    override suspend fun execute() = withSession { s ->
        s.client.resume()
        if (json) okJson() else echo("Resumed.")
    }
}

class StopCommand : HauntCommand("stop") {
    override fun help(context: Context) = "Stop mocking (real GPS returns). With --hold, only stop moving and keep the current fake position."

    private val hold by option("--hold", help = "Stop moving but keep holding the current position.").flag()

    override suspend fun execute() = withSession { s ->
        if (hold) s.client.stopPlayback() else s.client.stopLocation()
        if (json) okJson() else echo(if (hold) "Stopped; holding the current position." else "Stopped mocking.")
    }
}

class SpeedCommand : HauntCommand("speed") {
    override fun help(context: Context) = "Change the speed of the current route: `haunt speed 30kmh`, `haunt speed drive` or `haunt speed 2x`."

    private val value by argument("SPEED", help = "walk, cycle, drive, 30kmh, 5mps, 20mph, or a multiplier like 2x")

    override suspend fun execute() {
        val params = try {
            if (value.trim().endsWith("x", ignoreCase = true)) {
                SetSpeedParams(multiplier = parseRate(value))
            } else {
                SetSpeedParams(metersPerSecond = parseSpeed(value).metersPerSecond)
            }
        } catch (e: IllegalArgumentException) {
            throw BadParameterValue(e.message ?: "invalid speed")
        }
        withSession { s ->
            s.client.setSpeed(params)
            if (json) okJson() else echo("Speed set to $value.")
        }
    }
}

class SearchCommand : HauntCommand("search") {
    override fun help(context: Context) = "Search places (geocoded on the device): `haunt search ramen --near 35.66,139.70`."

    private val query by argument("QUERY").multiple(required = true)
    private val limit by option("--limit", help = "Maximum results.").parsed { it.toIntOrNull() ?: throw IllegalArgumentException("not an integer") }
    private val near by option("--near", help = "Bias results around \"lat,lng\".").parsed {
        parseLatLng(it) ?: throw IllegalArgumentException("expected \"lat,lng\"")
    }

    override suspend fun execute() = withSession { s ->
        val places = s.client.searchPlaces(PlacesSearchParams(query.joinToString(" "), near, limit))
        if (json) {
            printJson(ListSerializer(Place.serializer()), places)
        } else if (places.isEmpty()) {
            echo("No results.")
        } else {
            places.forEachIndexed { i, p -> echo("${i + 1}. ${Format.place(p)}") }
        }
    }
}

class WatchCommand : HauntCommand("watch") {
    override fun help(context: Context) = "Stream events (fixes, state changes, route progress) until interrupted. --json prints one JSON object per line."

    private val events by option("--events", help = "Comma-separated: fix,state,routeProgress,routeFinished,error (default: all).")
        .default("*")
    private val untilFinished by option("--until-finished", help = "Exit 0 when the current route finishes.").flag()

    override suspend fun execute() = withSession { s ->
        val finished = CompletableDeferred<Unit>()
        val arrived = coroutineScope {
            val printer = launch(start = CoroutineStart.UNDISPATCHED) {
                s.client.events.collect { e ->
                    if (finished.isCompleted) return@collect
                    if (json) {
                        val method = EventNames.methodOf(e)
                        val body = ProtocolJson.encodeToJsonElement(EventNames.serializerFor(method)!!, e).jsonObject
                        printJson(JsonObject(mapOf("event" to JsonPrimitive(method.removePrefix("event."))) + body))
                    } else {
                        echo(Format.event(e))
                    }
                    if (untilFinished && e is HauntEvent.RouteFinishedEvent) finished.complete(Unit)
                }
            }
            val lost = async { s.client.connected.first { !it } }
            val requested = events.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            s.client.subscribe(if (untilFinished) (requested + "routeFinished").distinct() else requested)
            select {
                finished.onAwait { true }
                lost.onAwait { false }
            }.also {
                printer.cancel()
                lost.cancel()
            }
        }
        if (!arrived) throw HauntFailure.notConnected("Connection to Haunt lost", ErrorHints.CONNECTION_LOST)
    }
}

class FavCommand : SuspendingCliktCommand("fav") {
    init {
        subcommands(FavList(), FavAdd(), FavRemove())
    }

    override fun help(context: Context) = "Manage favourites: `haunt fav ls`, `haunt fav add <name> [lat lng]`, `haunt fav rm <name>`."

    override suspend fun run() = Unit
}

class FavList : HauntCommand("ls") {
    override fun help(context: Context) = "List favourites."

    override suspend fun execute() = withSession { s ->
        val favorites = s.client.listFavorites()
        if (json) {
            printJson(ListSerializer(Favorite.serializer()), favorites)
        } else if (favorites.isEmpty()) {
            echo("No favourites.")
        } else {
            favorites.forEach { f -> echo(listOfNotNull(f.name, f.folder?.let { "[$it]" }, Format.latLng(f.position)).joinToString(" · ")) }
        }
    }
}

class FavAdd : HauntCommand("add") {
    override fun help(context: Context) = "Save a favourite at the given coordinates, or at the current fake location."

    private val name by argument("NAME")
    private val coords by argument("LAT_LNG", help = "lat lng or \"lat,lng\" (default: current location)").multiple()
    private val folder by option("--folder", help = "Folder name.")

    override suspend fun execute() {
        val position = if (coords.isEmpty()) {
            null
        } else {
            parseLatLng(coords.joinToString(" ")) ?: throw BadParameterValue("expected \"lat,lng\" or lat lng")
        }
        withSession { s ->
            val f = s.client.saveFavorite(FavoriteSaveParams(name, position?.lat, position?.lng, folder))
            if (json) printJson(Favorite.serializer(), f) else echo("Saved ${f.name} · ${Format.latLng(f.position)}")
        }
    }
}

class FavRemove : HauntCommand("rm") {
    override fun help(context: Context) = "Delete a favourite by name."

    private val name by argument("NAME")

    override suspend fun execute() = withSession { s ->
        s.client.deleteFavorite(FavoriteDeleteParams(name = name))
        if (json) okJson() else echo("Deleted $name.")
    }
}

class McpCommand : HauntCommand("mcp") {
    override fun help(context: Context) =
        "Run an MCP server on stdio for AI agents (e.g. `claude mcp add haunt -- haunt mcp`). Connects to the device lazily on the first tool call."

    override suspend fun execute() = runMcpServer(global)
}
