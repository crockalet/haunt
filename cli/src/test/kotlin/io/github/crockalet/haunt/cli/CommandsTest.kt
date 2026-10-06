package io.github.crockalet.haunt.cli

import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.protocol.FinishReason
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.RpcException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandsTest {
    private val device = FakeDevice()

    @AfterTest
    fun tearDown() = device.close()

    private fun run(vararg argv: String, readFile: (String) -> String = { error("no file") }): CliktCommandTestResult = runBlocking {
        withTimeout(20_000) { HauntCli(device.deps(readFile)).test(escapeNegativeNumbers(argv.toList())) }
    }

    private fun CliktCommandTestResult.assertOk() = apply { assertEquals(0, statusCode, output) }

    @Test
    fun devicesHumanAndJson() {
        val human = run("devices").assertOk()
        assertTrue("emulator-5554" in human.stdout && "Pixel_8" in human.stdout, human.stdout)

        val json = run("--json", "devices").assertOk()
        val list = Json.parseToJsonElement(json.stdout).jsonArray
        assertEquals("emulator-5554", list.single().jsonObject["serial"]!!.jsonPrimitive.content)

        device.devicesOutput = "List of devices attached\n\n"
        val none = run("devices")
        assertEquals(2, none.statusCode)
        assertTrue("No Android devices attached" in none.stderr, none.stderr)
        assertEquals("[]", run("devices", "--json").stdout.trim())
    }

    @Test
    fun setByCoordinatesIncludingNegative() {
        val out = run("set", "-33.8568", "151.2153", "--alt", "12", "--acc", "3").assertOk()
        assertTrue("Holding -33.856800, 151.215300" in out.stdout, out.stdout)
        val p = device.api.lastSetLocation!!
        assertEquals(-33.8568, p.lat)
        assertEquals(151.2153, p.lng)
        assertEquals(12.0, p.altitude)
        assertEquals(3f, p.accuracy)

        run("set", "35.6586,139.7454").assertOk()
        assertEquals(35.6586, device.api.lastSetLocation!!.lat)
        // Forward removed after each command.
        assertTrue(device.forwards.isEmpty(), "forwards left: ${device.forwards}")
    }

    @Test
    fun setByQueryJson() {
        val out = run("set", "Tokyo Tower", "--json").assertOk()
        val obj = Json.parseToJsonElement(out.stdout).jsonObject
        assertEquals("Tokyo Tower", obj["place"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Tokyo Tower", device.api.lastSetLocation!!.query)
    }

    @Test
    fun statusHumanAndJson() {
        run("set", "35.6586", "139.7454", "--label", "Tower").assertOk()
        val human = run("status").assertOk()
        assertTrue(human.stdout.startsWith("Holding · Tower · 35.658600, 139.745400"), human.stdout)
        assertTrue("Haunt 0.1.0 (foss)" in human.stdout)

        val json = Json.parseToJsonElement(run("status", "--json").assertOk().stdout).jsonObject
        assertEquals("Holding", json["state"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("emulator-5554", json["device"]!!.jsonObject["serial"]!!.jsonPrimitive.content)
        assertEquals("foss", json["app"]!!.jsonObject["flavour"]!!.jsonPrimitive.content)
    }

    @Test
    fun goWithSpeedAndRoads() {
        val out = run("go", "Shibuya Station", "--speed", "30kmh", "--roads").assertOk()
        assertTrue("Moving to Shibuya Station · 1.2 km · ETA 14m17s" in out.stdout, out.stdout)
        val p = device.api.lastMoveTo!!
        assertEquals("Shibuya Station", p.query)
        assertEquals(30.0 / 3.6, p.metersPerSecond!!, 1e-9)
        assertEquals(true, p.followRoads)

        run("go", "35.66,139.70").assertOk()
        assertEquals(35.66, device.api.lastMoveTo!!.lat)
        assertEquals(null, device.api.lastMoveTo!!.metersPerSecond)

        assertEquals(1, run("go", "x", "--speed", "warp").statusCode)
    }

    @Test
    fun playGpxAndKml() {
        val files = mapOf("walk.gpx" to "<gpx><trk/></gpx>", "track.kml" to "<kml/>", "mystery.txt" to "<gpx/>")
        val read = { path: String -> files[path] ?: throw java.io.IOException("missing") }
        run("play", "walk.gpx", "--rate", "2x", "--pingpong", readFile = read).assertOk()
        val gpx = device.api.lastRoute!!
        assertEquals("<gpx><trk/></gpx>", gpx.gpx)
        assertEquals(2.0, gpx.multiplier)
        assertEquals(LoopMode.PingPong, gpx.loop)
        assertEquals("walk", gpx.name)

        run("play", "track.kml", "--speed", "drive", "--loop", readFile = read).assertOk()
        assertEquals("<kml/>", device.api.lastRoute!!.kml)
        assertEquals(LoopMode.Loop, device.api.lastRoute!!.loop)

        run("play", "mystery.txt", readFile = read).assertOk()
        assertEquals("<gpx/>", device.api.lastRoute!!.gpx)

        assertEquals(1, run("play", "walk.gpx", "--rate", "2x", "--speed", "walk", readFile = read).statusCode)
        assertEquals(1, run("play", "nope.gpx", readFile = read).statusCode)
    }

    @Test
    fun playbackControls() {
        run("pause").assertOk()
        run("resume").assertOk()
        run("stop", "--hold").assertOk()
        run("stop").assertOk()
        run("speed", "2x").assertOk()
        assertEquals(2.0, device.api.lastSpeed!!.multiplier)
        run("speed", "cycle").assertOk()
        assertEquals(5.0, device.api.lastSpeed!!.metersPerSecond)
        assertEquals("{\"ok\":true}", run("pause", "--json").assertOk().stdout.trim())
        assertEquals(
            listOf("playback.pause", "playback.resume", "playback.stop", "location.stop", "playback.setSpeed", "playback.setSpeed", "playback.pause"),
            device.api.calls.filter { it != "hello" },
        )
    }

    @Test
    fun searchAndFavorites() {
        val search = run("search", "shibuya", "--near", "35.6,139.7", "--limit", "3").assertOk()
        assertTrue("1. Shibuya Station · Shibuya, Tokyo" in search.stdout, search.stdout)
        assertEquals(3, device.api.lastSearch!!.limit)
        assertEquals(35.6, device.api.lastSearch!!.near!!.lat)

        assertTrue("Home" in run("fav", "ls").assertOk().stdout)
        run("fav", "add", "Work", "-33.85", "151.21", "--folder", "Jobs").assertOk()
        assertEquals("Work", device.api.favorites.last().name)
        assertEquals(-33.85, device.api.favorites.last().position.lat)
        run("fav", "add", "Here").assertOk()
        val ls = Json.parseToJsonElement(run("fav", "ls", "--json").assertOk().stdout).jsonArray
        assertEquals(3, ls.size)
        run("fav", "rm", "Work").assertOk()
        val missing = run("fav", "rm", "Nope")
        assertEquals(1, missing.statusCode)
        assertTrue("No favourite named" in missing.stderr)
    }

    @Test
    fun exitCodesForWellKnownErrors() {
        device.api.mockAppSelected = false
        val notSelected = run("set", "1", "2")
        assertEquals(3, notSelected.statusCode)
        assertTrue("Select mock location app" in notSelected.stderr, notSelected.stderr)

        device.api.failWith = RpcException.adbControlDisabled()
        val disabled = run("status", "--json")
        assertEquals(4, disabled.statusCode)
        val err = Json.parseToJsonElement(disabled.stdout).jsonObject["error"]!!.jsonObject
        assertEquals(4, err["exit"]!!.jsonPrimitive.int)
        assertTrue("Allow ADB control" in err["hint"]!!.jsonPrimitive.content)

        device.api.failWith = null
        device.devicesOutput = "List of devices attached\na device\nb device\n"
        val several = run("status")
        assertEquals(2, several.statusCode)
        assertTrue("-s <serial>" in several.stderr)
        run("-s", "b", "status").assertOk()
    }

    @Test
    fun startsTheAppWhenNotRunning() {
        device.appRunning = false
        run("status").assertOk()
        assertEquals(1, device.commandsMatching("shell", "am", "start-foreground-service").size)
        assertTrue(device.forwards.isEmpty())
    }

    @Test
    fun reportsAppNotInstalled() {
        device.appRunning = false
        device.appInstalled = false
        val out = run("status")
        assertEquals(2, out.statusCode)
        assertTrue("not installed" in out.stderr, out.stderr)
        assertTrue(device.forwards.isEmpty(), "forward must be removed on failure")
    }

    @Test
    fun watchStreamsJsonUntilFinished(): Unit = runBlocking {
        // Keep emitting until the CLI (which subscribes asynchronously) has seen a routeFinished.
        val emitter = launch {
            while (true) {
                device.events.emit(HauntEvent.FixEvent(Fix(TokyoTower, timeMillis = 5)))
                device.events.emit(HauntEvent.RouteFinishedEvent("r2", FinishReason.Arrived))
                delay(50)
            }
        }
        val out = withTimeout(20_000) { HauntCli(device.deps()).test(listOf("watch", "--json", "--until-finished")) }
        emitter.cancel()
        assertEquals(0, out.statusCode, out.output)
        val lines = out.stdout.trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("routeFinished", lines.last()["event"]!!.jsonPrimitive.content)
        assertEquals("r2", lines.last()["routeId"]!!.jsonPrimitive.content)
        assertTrue(lines.dropLast(1).all { it["event"]!!.jsonPrimitive.content == "fix" })
    }
}
