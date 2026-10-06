package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.control.AdbGatedHauntApi
import io.github.crockalet.haunt.android.control.AndroidHauntApi
import io.github.crockalet.haunt.android.control.BroadcastCommands
import io.github.crockalet.haunt.android.data.FavoritesStore
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.protocol.Broadcast
import io.github.crockalet.haunt.protocol.ErrorCodes
import io.github.crockalet.haunt.protocol.FavoriteDeleteParams
import io.github.crockalet.haunt.protocol.FavoriteSaveParams
import io.github.crockalet.haunt.protocol.HelloParams
import io.github.crockalet.haunt.protocol.MoveToParams
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.PlacesSearchParams
import io.github.crockalet.haunt.protocol.RoutePlayParams
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.protocol.SetSpeedParams
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AndroidHauntApiTest {
    private val dir: File = Files.createTempDirectory("haunt-api").toFile()
    private val shibuya = Place("Shibuya Station", LatLng(35.658, 139.7016), kind = "station")
    private val geocoder = FakeGeocoder(mapOf("shibuya station" to shibuya))
    private val router = FakeRouter()
    private val env = FakeEnvironment()
    private val favorites = FavoritesStore(File(dir, "f.json"))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun TestScope.api(): Pair<AndroidHauntApi, Harness> {
        val h = harness()
        return AndroidHauntApi(h.controller, favorites, geocoder, router, env) to h
    }

    private suspend fun assertRpcError(code: Int, block: suspend () -> Unit): RpcException {
        val e = try {
            block()
            null
        } catch (e: RpcException) {
            e
        }
        assertNotNull(e, "expected RpcException $code")
        assertEquals(code, e.code, e.message)
        return e
    }

    @Test
    fun helloAndStatus() = runTest {
        val (api, _) = api()
        val hello = api.hello(HelloParams())
        assertEquals("9.9.9", hello.appVersion)
        assertEquals("foss", hello.flavour)
        assertTrue(hello.mockAppSelected)
        assertEquals(HauntState.Idle, api.status().state)
        assertNull(api.status().lastFix)
    }

    @Test
    fun setLocationByCoordinatesAndQuery() = runTest {
        val (api, _) = api()
        val r = api.setLocation(SetLocationParams(lat = 35.0, lng = 139.0, accuracy = 3f))
        assertEquals(LatLng(35.0, 139.0), r.fix.position)
        assertEquals(3f, r.fix.accuracy)
        assertNull(r.place)
        assertEquals(1, env.started)

        val q = api.setLocation(SetLocationParams(query = "Shibuya Station"))
        assertEquals(shibuya, q.place)
        assertEquals("Shibuya Station", (api.status().state as HauntState.Holding).label)
        assertEquals(LatLng(35.0, 139.0), geocoder.calls.single().third) // biased to the current position

        // Coordinates in a query don't hit the geocoder; favourites match by name.
        assertEquals(LatLng(1.5, 2.5), api.setLocation(SetLocationParams(query = "1.5, 2.5")).fix.position)
        favorites.save("Home", LatLng(10.0, 20.0))
        assertEquals("favorite", api.setLocation(SetLocationParams(query = "home")).place!!.kind)
        assertEquals(1, geocoder.calls.size)

        assertRpcError(ErrorCodes.NOT_FOUND) { api.setLocation(SetLocationParams(query = "Atlantis")) }
        geocoder.fail = true
        val e = assertRpcError(ErrorCodes.UNAVAILABLE) { api.setLocation(SetLocationParams(query = "Somewhere")) }
        assertNotNull(e.hint)
    }

    @Test
    fun mockingRequiresMockApp() = runTest {
        val (api, _) = api()
        env.selected = false
        assertRpcError(ErrorCodes.MOCK_APP_NOT_SELECTED) { api.setLocation(SetLocationParams(lat = 1.0, lng = 2.0)) }
        assertRpcError(ErrorCodes.MOCK_APP_NOT_SELECTED) { api.playRoute(RoutePlayParams(waypoints = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01)))) }
        assertFalse(api.hello(HelloParams()).mockAppSelected)
        env.selected = true
        env.permission = false
        assertRpcError(ErrorCodes.UNAVAILABLE) { api.setLocation(SetLocationParams(lat = 1.0, lng = 2.0)) }
        assertEquals(HauntState.Idle, api.status().state)
        assertEquals(0, env.started)
    }

    @Test
    fun playWaypointsWithRoadsAndFallback() = runTest {
        val (api, h) = api()
        val waypoints = listOf(LatLng(35.0, 139.0), LatLng(35.01, 139.0))
        val routed = api.playRoute(RoutePlayParams(waypoints = waypoints, followRoads = true, metersPerSecond = 10.0, name = "trip"))
        assertTrue(routed.followRoads)
        assertNull(routed.warning)
        assertEquals(h.controller.currentRouteId, routed.routeId)
        assertTrue(routed.distanceM > 1112.0)
        val moving = assertIs<HauntState.Moving>(api.status().state)
        assertEquals("trip", moving.routeName)
        assertEquals(routed.etaS, moving.progress.etaSeconds)

        router.fail = true
        val straight = api.playRoute(RoutePlayParams(waypoints = waypoints, followRoads = true))
        assertFalse(straight.followRoads)
        assertTrue(straight.warning!!.contains("straight lines"))
        assertEquals(1112.0, straight.distanceM, 1.0)
        assertEquals(1.4, (api.status().state as HauntState.Moving).speed.metersPerSecond)

        // The first route was replaced by the second.
        val finished = h.events.filterIsInstance<io.github.crockalet.haunt.protocol.HauntEvent.RouteFinishedEvent>().single()
        assertEquals(routed.routeId, finished.routeId)
    }

    @Test
    fun playGpxByRecordedTimingWithMultiplier() = runTest {
        val (api, _) = api()
        val gpx = """
            <gpx version="1.1" creator="test"><trk><name>run</name><trkseg>
              <trkpt lat="35.0" lon="139.0"><time>2024-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="35.001" lon="139.0"><time>2024-01-01T00:01:40Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val r = api.playRoute(RoutePlayParams(gpx = gpx, multiplier = 2.0, loop = LoopMode.Loop))
        val moving = assertIs<HauntState.Moving>(api.status().state)
        assertEquals(2.0, moving.playbackRate)
        assertEquals("run", moving.routeName)
        assertEquals(50L, r.etaS)

        // Without speed: recorded timing at 1x (the rate isn't inherited from the previous call).
        api.playRoute(RoutePlayParams(gpx = gpx))
        assertEquals(1.0, (api.status().state as HauntState.Moving).playbackRate)

        // A fixed speed ignores timestamps.
        api.playRoute(RoutePlayParams(gpx = gpx, metersPerSecond = 5.0))
        assertNull((api.status().state as HauntState.Moving).playbackRate)

        // setSpeed multiplier scales the recorded timing / the constant speed.
        api.playRoute(RoutePlayParams(gpx = gpx))
        api.setSpeed(SetSpeedParams(multiplier = 3.0))
        assertEquals(3.0, (api.status().state as HauntState.Moving).playbackRate)
        api.setSpeed(SetSpeedParams(metersPerSecond = 2.0))
        api.setSpeed(SetSpeedParams(multiplier = 2.0))
        assertEquals(4.0, (api.status().state as HauntState.Moving).speed.metersPerSecond)

        assertRpcError(ErrorCodes.INVALID_PARAMS) { api.playRoute(RoutePlayParams(gpx = "<html/>")) }
        assertRpcError(ErrorCodes.INVALID_PARAMS) { api.playRoute(RoutePlayParams(kml = "<kml></kml>")) }
    }

    @Test
    fun moveToFromCurrentPosition() = runTest {
        val (api, _) = api()
        assertRpcError(ErrorCodes.INVALID_STATE) { api.moveTo(MoveToParams(lat = 1.0, lng = 1.0)) }
        api.setLocation(SetLocationParams(lat = 35.66, lng = 139.70))
        val r = api.moveTo(MoveToParams(query = "Shibuya Station", followRoads = true, metersPerSecond = 5.0))
        assertEquals(shibuya, r.destination)
        assertTrue(r.followRoads)
        assertEquals(listOf(LatLng(35.66, 139.70), shibuya.position), router.calls.single())
        val moving = assertIs<HauntState.Moving>(api.status().state)
        assertEquals("Shibuya Station", moving.routeName)
        assertEquals(5.0, moving.speed.metersPerSecond)
    }

    @Test
    fun pauseResumeStopPlayback() = runTest {
        val (api, _) = api()
        assertRpcError(ErrorCodes.INVALID_STATE) { api.pause() }
        assertRpcError(ErrorCodes.INVALID_STATE) { api.stopPlayback() }
        assertRpcError(ErrorCodes.INVALID_STATE) { api.setSpeed(SetSpeedParams(multiplier = 2.0)) }
        api.playRoute(RoutePlayParams(waypoints = listOf(LatLng(0.0, 0.0), LatLng(0.01, 0.0)), metersPerSecond = 10.0))
        api.pause()
        assertTrue((api.status().state as HauntState.Moving).paused)
        api.resume()
        assertFalse((api.status().state as HauntState.Moving).paused)
        advance(10.seconds)
        api.stopPlayback()
        val holding = assertIs<HauntState.Holding>(api.status().state)
        assertEquals(100.0, io.github.crockalet.haunt.core.Geo.distanceMeters(LatLng(0.0, 0.0), holding.fix.position), 1.0)
        api.stopPlayback() // holding: no-op
        api.stopLocation()
        assertEquals(HauntState.Idle, api.status().state)
        assertNotNull(api.status().lastFix)
    }

    @Test
    fun favouritesAndSearch() = runTest {
        val (api, _) = api()
        assertRpcError(ErrorCodes.INVALID_STATE) { api.saveFavorite(FavoriteSaveParams("here")) }
        api.setLocation(SetLocationParams(lat = 1.0, lng = 2.0))
        val here = api.saveFavorite(FavoriteSaveParams("here"))
        assertEquals(LatLng(1.0, 2.0), here.position)
        api.saveFavorite(FavoriteSaveParams("there", lat = 3.0, lng = 4.0, color = "#112233"))
        assertEquals(listOf("here", "there"), api.listFavorites().map { it.name })
        api.deleteFavorite(FavoriteDeleteParams(name = "HERE"))
        assertRpcError(ErrorCodes.NOT_FOUND) { api.deleteFavorite(FavoriteDeleteParams(id = "missing")) }
        assertEquals(listOf("there"), api.listFavorites().map { it.name })

        assertEquals(listOf(shibuya), api.searchPlaces(PlacesSearchParams("shibuya station")))
        assertEquals(5, geocoder.calls.last().second)
        assertEquals(LatLng(1.0, 2.0), geocoder.calls.last().third)
    }

    @Test
    fun adbGateBlocksEverything() = runTest {
        val (api, _) = api()
        var enabled = false
        val gated = AdbGatedHauntApi(api) { enabled }
        val e = assertRpcError(ErrorCodes.ADB_CONTROL_DISABLED) { gated.hello(HelloParams()) }
        assertNotNull(e.hint)
        assertRpcError(ErrorCodes.ADB_CONTROL_DISABLED) { gated.status() }
        enabled = true
        assertEquals("9.9.9", gated.hello(HelloParams()).appVersion)
    }

    @Test
    fun broadcastCommands() = runTest {
        val (api, _) = api()
        val set = BroadcastCommands.execute(api, Broadcast.ACTION_SET, mapOf("lat" to 35.5, "lng" to "139.5", "acc" to 4f))
        assertNull(set.error, set.json)
        assertTrue(set.json.contains("\"lat\":35.5"), set.json)
        val status = BroadcastCommands.execute(api, Broadcast.ACTION_STATUS, emptyMap())
        assertTrue(status.json.contains("\"type\":\"Holding\""), status.json)
        assertEquals(ErrorCodes.INVALID_STATE, BroadcastCommands.execute(api, Broadcast.ACTION_PAUSE, emptyMap()).error?.code)
        val bad = BroadcastCommands.execute(api, Broadcast.ACTION_SET, mapOf("lat" to 1.0))
        assertEquals(ErrorCodes.INVALID_PARAMS, bad.error?.code)
        assertTrue(bad.json.startsWith("{\"error\":{\"code\":-32602"), bad.json)
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, BroadcastCommands.execute(api, "haunt.NOPE", emptyMap()).error?.code)
        val q = BroadcastCommands.execute(api, Broadcast.ACTION_SET, mapOf("query" to "Shibuya Station"))
        assertTrue(q.json.contains("Shibuya Station"), q.json)
        assertEquals("{}", BroadcastCommands.execute(api, Broadcast.ACTION_STOP, emptyMap()).json)
        assertEquals(HauntState.Idle, api.status().state)
    }
}
