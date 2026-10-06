package io.github.crockalet.haunt.ui

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.ui.components.JoystickMath
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.Geo
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.state.SampleData
import io.github.crockalet.haunt.ui.state.StatusUi
import io.github.crockalet.haunt.ui.state.buildMapUiState
import io.github.crockalet.haunt.ui.state.parseSimpleCoordinates
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    @Test
    fun fixed() {
        assertEquals("35.65952", Format.fixed(35.659521, 5))
        assertEquals("-0.12462", Format.fixed(-0.124624, 5))
        assertEquals("0.0", Format.fixed(-0.01, 1))
        assertEquals("3", Format.fixed(2.6, 0))
    }

    @Test
    fun distancesAndDurations() {
        assertEquals("640 m", Format.distance(640.2))
        assertEquals("3.4 km", Format.distance(3389.0))
        assertEquals("1.2 / 3.4 km", Format.progress(1190.0, 3389.0))
        assertEquals("26 min", Format.duration(26 * 60))
        assertEquals("1 h 05 min", Format.duration(3900))
        assertEquals("5 km/h", Format.kmh(Speed.Walk.metersPerSecond))
    }

    @Test
    fun compass() {
        assertEquals("N", Format.compass(0.0))
        assertEquals("NE", Format.compass(42.0))
        assertEquals("W", Format.compass(-90.0))
        assertEquals("N", Format.compass(359.0))
    }
}

class ParserTest {
    @Test
    fun decimal() {
        assertEquals(LatLng(35.65944, 139.70056), parseSimpleCoordinates(" 35.65944, 139.70056 ")?.position)
        assertNull(parseSimpleCoordinates("95.0, 10.0"))
        assertNull(parseSimpleCoordinates("Yoyogi Park"))
    }

    @Test
    fun dms() {
        val p = assertNotNull(parseSimpleCoordinates("35°39'34\"N 139°42'02\"E")).position
        assertEquals("35.65944, 139.70056", Format.coords(p))
        val s = assertNotNull(parseSimpleCoordinates("33°52'04\"S 151°12'36\"E")).position
        assertTrue(s.lat < 0)
    }
}

class JoystickMathTest {
    @Test
    fun polarRoundTrip() {
        val (b, m) = JoystickMath.toPolar(dx = 10f, dy = -10f, maxTravel = 28.28f)
        assertTrue(abs(b - 45.0) < 0.01)
        assertTrue(abs(m - 0.5) < 0.01)
        val o = JoystickMath.toOffset(b, m, 28.28f)
        assertTrue(abs(o.x - 10f) < 0.01f && abs(o.y + 10f) < 0.01f)
        assertEquals(1.0, JoystickMath.toPolar(0f, 100f, 10f).second)
        assertEquals(180.0, JoystickMath.toPolar(0f, 5f, 10f).first)
    }
}

class MapUiStateTest {
    private val fix = Fix(SampleData.ShibuyaCrossing, altitude = 38.0, accuracy = 5f, timeMillis = 1_000_000)

    @Test
    fun idlePin() {
        val ui = buildMapUiState(HauntState.Idle, LocalUiState())
        assertEquals(false, ui.active)
        assertEquals(MapMode.Pin, ui.mode)
        assertIs<StatusUi.Message>(ui.status)
        assertNull(ui.map.fix)
    }

    @Test
    fun holdingPin() {
        val ui = buildMapUiState(
            HauntState.Holding(fix, "Shibuya Crossing"),
            LocalUiState(activeSinceMillis = fix.timeMillis - 12 * 60_000),
        )
        val pin = assertNotNull(ui.pin)
        assertEquals("Shibuya Crossing", pin.title)
        assertEquals("35.65952, 139.70055", pin.coordinates)
        assertEquals("38 m", pin.altitude)
        assertEquals("±5 m", pin.accuracy)
        assertTrue(pin.footer.startsWith("Haunting for 12 min"))
        assertEquals(StatusUi.Message("Haunting · Shibuya Crossing", active = true), ui.status)
        assertTrue(ui.map.showHalo)
    }

    @Test
    fun movingRoute() {
        val route = SampleData.routeShibuyaToYoyogi
        val total = Geo.length(route)
        val engine = HauntState.Moving(
            fix = fix.copy(position = Geo.along(route, total * 0.35)),
            routeName = "Walk",
            progress = io.github.crockalet.haunt.core.RouteProgress(total * 0.35, total, 1560),
            speed = Speed.Walk,
            loop = LoopMode.Once,
            paused = false,
        )
        val ui = buildMapUiState(engine, LocalUiState(mode = MapMode.Route, draftRoute = route))
        val r = assertNotNull(ui.route)
        assertTrue(r.playing)
        assertEquals("ETA 26 min", r.eta)
        assertEquals(0.35f, r.fraction, 0.001f)
        assertIs<StatusUi.Progress>(ui.status)
        assertTrue(ui.map.traveled.size >= 2)
        assertTrue(ui.map.follow)
        assertEquals("Add a stop", ui.searchPlaceholder)
    }
}

class HolderAndFakeTest {
    @Test
    fun pinThenRouteThenStop() {
        val fake = FakeHauntController(clock = { 0L })
        val holder = MapStateHolder(fake)
        holder.onMapLongPress(LatLng(1.0, 2.0))
        assertIs<HauntState.Holding>(fake.state.value)

        holder.selectMode(MapMode.Route)
        holder.onMapLongPress(LatLng(1.0, 2.0))
        holder.onMapLongPress(LatLng(1.0, 2.01))
        assertEquals(2, holder.local.draftRoute.size)
        holder.playPause()
        val moving = assertIs<HauntState.Moving>(fake.state.value)
        assertTrue(moving.progress.totalMeters > 1000)

        fake.tick(10.0)
        val after = assertIs<HauntState.Moving>(fake.state.value)
        assertEquals(14.0, after.progress.traveledMeters, 0.01)

        holder.playPause()
        assertTrue(assertIs<HauntState.Moving>(fake.state.value).paused)
        holder.stop()
        assertIs<HauntState.Idle>(fake.state.value)
    }

    @Test
    fun joystickMoves() {
        val fake = FakeHauntController(clock = { 0L })
        fake.setLocation(LatLng(0.0, 0.0), null, null, null)
        val holder = MapStateHolder(fake)
        holder.selectMode(MapMode.Joystick)
        assertIs<HauntState.Joystick>(fake.state.value)
        holder.joystickInput(90.0, 1.0)
        fake.tick(10.0)
        val j = assertIs<HauntState.Joystick>(fake.state.value)
        assertTrue(j.fix.position.lng > 0)
        assertEquals(10 * 12 / 3.6, j.distanceMeters, 0.01)
        holder.selectMode(MapMode.Pin)
        assertIs<HauntState.Holding>(fake.state.value)
    }

    @Test
    fun loopRoute() {
        val fake = FakeHauntController(clock = { 0L })
        fake.playRoute(Route(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.001))), Speed(10.0), LoopMode.Loop)
        repeat(3) { fake.tick(5.0) }
        val m = assertIs<HauntState.Moving>(fake.state.value)
        assertTrue(m.progress.traveledMeters < m.progress.totalMeters)
    }
}
