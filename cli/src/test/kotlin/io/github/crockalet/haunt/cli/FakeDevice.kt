package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.cli.adb.AdbLocator
import io.github.crockalet.haunt.cli.adb.ProcessResult
import io.github.crockalet.haunt.cli.adb.ProcessRunner
import io.github.crockalet.haunt.cli.connect.TransportFactory
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.protocol.Favorite
import io.github.crockalet.haunt.protocol.FavoriteDeleteParams
import io.github.crockalet.haunt.protocol.FavoriteSaveParams
import io.github.crockalet.haunt.protocol.HauntApi
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.HelloParams
import io.github.crockalet.haunt.protocol.HelloResult
import io.github.crockalet.haunt.protocol.InMemoryPipe
import io.github.crockalet.haunt.protocol.LineTransport
import io.github.crockalet.haunt.protocol.MoveToParams
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.PlacesSearchParams
import io.github.crockalet.haunt.protocol.Protocol
import io.github.crockalet.haunt.protocol.RouteResult
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.RpcServer
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.protocol.SetLocationResult
import io.github.crockalet.haunt.protocol.SetSpeedParams
import io.github.crockalet.haunt.protocol.StatusResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.Collections

val TokyoTower = LatLng(35.6586, 139.7454)
val Sydney = LatLng(-33.8568, 151.2153)

/** A [HauntApi] that records calls and keeps simple state. */
class TestApi : HauntApi {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var state: HauntState = HauntState.Idle
    @Volatile var mockAppSelected = true
    @Volatile var failWith: RpcException? = null
    @Volatile var lastSetLocation: SetLocationParams? = null
    @Volatile var lastRoute: RoutePlayParams? = null
    @Volatile var lastMoveTo: MoveToParams? = null
    @Volatile var lastSpeed: SetSpeedParams? = null
    @Volatile var lastSearch: PlacesSearchParams? = null
    val favorites: MutableList<Favorite> = Collections.synchronizedList(mutableListOf(Favorite("1", "Home", TokyoTower)))

    private fun record(name: String) {
        calls += name
        failWith?.let { throw it }
    }

    override suspend fun hello(params: HelloParams): HelloResult {
        calls += "hello"
        return HelloResult(Protocol.VERSION, "0.1.0", "foss", mockAppSelected)
    }

    override suspend fun status(): StatusResult {
        record("status")
        return StatusResult(state, Fix(TokyoTower, timeMillis = 1), mockAppSelected)
    }

    override suspend fun setLocation(params: SetLocationParams): SetLocationResult {
        record("location.set")
        if (!mockAppSelected) throw RpcException.mockAppNotSelected()
        lastSetLocation = params
        val place = params.query?.let { Place(it, TokyoTower, "Minato, Tokyo") }
        val fix = Fix(params.position ?: TokyoTower, params.altitude, params.accuracy ?: 5f, timeMillis = 1)
        state = HauntState.Holding(fix, params.label ?: place?.name)
        return SetLocationResult(fix, place)
    }

    override suspend fun stopLocation() {
        record("location.stop")
        state = HauntState.Idle
    }

    override suspend fun playRoute(params: RoutePlayParams): RouteResult {
        record("route.play")
        lastRoute = params
        return RouteResult("r1", 2500.0, 1800, params.followRoads)
    }

    override suspend fun moveTo(params: MoveToParams): RouteResult {
        record("move.to")
        lastMoveTo = params
        state = HauntState.Moving(
            Fix(TokyoTower, timeMillis = 1), "to target", RouteProgress(0.0, 1200.0, 857),
            Speed(params.metersPerSecond ?: Speed.Walk.metersPerSecond), LoopMode.Once, paused = false,
        )
        return RouteResult("r2", 1200.0, 857, destination = params.query?.let { Place(it, TokyoTower) })
    }

    override suspend fun pause() = record("playback.pause")
    override suspend fun resume() = record("playback.resume")
    override suspend fun stopPlayback() = record("playback.stop")

    override suspend fun setSpeed(params: SetSpeedParams) {
        record("playback.setSpeed")
        lastSpeed = params
    }

    override suspend fun searchPlaces(params: PlacesSearchParams): List<Place> {
        record("places.search")
        lastSearch = params
        return listOf(Place("Shibuya Station", LatLng(35.658, 139.7016), "Shibuya, Tokyo", "station"))
    }

    override suspend fun listFavorites(): List<Favorite> {
        record("favorites.list")
        return favorites.toList()
    }

    override suspend fun saveFavorite(params: FavoriteSaveParams): Favorite {
        record("favorites.save")
        val f = Favorite("2", params.name, LatLng(params.lat ?: TokyoTower.lat, params.lng ?: TokyoTower.lng), params.folder)
        favorites += f
        return f
    }

    override suspend fun deleteFavorite(params: FavoriteDeleteParams) {
        record("favorites.delete")
        if (!favorites.removeIf { it.name == params.name }) throw RpcException.notFound("No favourite named \"${params.name}\"")
    }
}

/**
 * A fake phone behind a fake adb: answers adb commands, and when the "app is running" serves
 * [api] through an in-memory pipe for every TCP connection the CLI opens.
 */
class FakeDevice(
    var devicesOutput: String = "List of devices attached\nemulator-5554          device product:sdk model:Pixel_8 device:emu transport_id:1\n",
    var appRunning: Boolean = true,
    var appInstalled: Boolean = true,
) : AutoCloseable {
    val api = TestApi()
    val events = MutableSharedFlow<HauntEvent>(extraBufferCapacity = 64)
    val commands: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val forwards: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private var nextPort = 40000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = RpcServer(api, events)

    val runner = ProcessRunner { command, _ ->
        commands += command
        val args = command.drop(1).let { if (it.firstOrNull() == "-s") it.drop(2) else it }
        when {
            args == listOf("devices", "-l") -> ProcessResult(0, devicesOutput, "")
            args.take(2) == listOf("forward", "tcp:0") -> {
                val port = nextPort++
                forwards += port
                ProcessResult(0, "$port\n", "")
            }
            args.take(2) == listOf("forward", "--remove") -> {
                forwards -= args[2].removePrefix("tcp:").toInt()
                ProcessResult(0, "", "")
            }
            args.take(3) == listOf("shell", "am", "start-foreground-service") -> if (appInstalled) {
                appRunning = true
                ProcessResult(0, "Starting service: Intent { cmp=${args.last()} }\n", "")
            } else {
                ProcessResult(0, "Error: Not found; no service started.\n", "")
            }
            else -> ProcessResult(1, "", "unknown command $args")
        }
    }

    val transports = TransportFactory { _ ->
        if (!appRunning) {
            // adb accepts the TCP connection, then closes it because nothing listens on the device.
            object : LineTransport {
                override val incoming: Flow<String> = emptyFlow()
                override suspend fun send(line: String) = Unit
                override fun close() = Unit
            }
        } else {
            val pipe = InMemoryPipe()
            scope.launch {
                server.serve(pipe.serverIncoming, pipe::serverSend)
                pipe.serverHangUp()
            }
            pipe.client
        }
    }

    fun deps(readFile: (String) -> String = { error("no file $it") }) = CliDeps(
        locator = AdbLocator(env = mapOf("ANDROID_HOME" to "/sdk"), isExecutable = { true }, home = ""),
        runner = runner,
        transports = transports,
        readFile = readFile,
        fastRetries = true,
    )

    fun commandsMatching(vararg prefix: String) = commands.map { it.drop(1) }.filter { c ->
        val args = if (c.firstOrNull() == "-s") c.drop(2) else c
        args.take(prefix.size) == prefix.toList()
    }

    override fun close() = scope.cancel()
}
