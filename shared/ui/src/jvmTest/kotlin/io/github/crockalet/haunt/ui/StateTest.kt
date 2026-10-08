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
import io.github.crockalet.haunt.ui.state.JoystickPlacement
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.state.SampleData
import io.github.crockalet.haunt.ui.state.StatusUi
import io.github.crockalet.haunt.ui.state.buildMapUiState
import io.github.crockalet.haunt.ui.state.detectCoordinates
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
        assertEquals("5 km/h", Format.speed(Speed.Walk.metersPerSecond, metric = true))
        assertEquals("3 mph", Format.speed(Speed.Walk.metersPerSecond, metric = false))
        assertEquals("NE · 5 km/h", Format.joystickReadout(42.0, Speed.Walk.metersPerSecond, metric = true))
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
        val d = assertNotNull(detectCoordinates(" 35.65944, 139.70056 "))
        assertEquals(LatLng(35.65944, 139.70056), d.position)
        assertEquals("Decimal detected", d.label)
        assertNull(detectCoordinates("95.0, 10.0"))
        assertNull(detectCoordinates("Yoyogi Park"))
    }

    @Test
    fun dms() {
        val d = assertNotNull(detectCoordinates("35°39'34\"N 139°42'02\"E"))
        assertEquals("35.65944, 139.70056", Format.coords(d.position))
        assertEquals("DMS detected", d.label)
        val s = assertNotNull(detectCoordinates("33°52'04\"S 151°12'36\"E")).position
        assertTrue(s.lat < 0)
    }

    @Test
    fun linksAndPlusCodes() {
        val maps = assertNotNull(detectCoordinates("https://www.google.com/maps/@35.6586,139.7454,17z"))
        assertEquals(LatLng(35.6586, 139.7454), maps.position)
        assertEquals("Google Maps link detected", maps.label)
        val geo = assertNotNull(detectCoordinates("geo:0,0?q=35.6586,139.7454(Tokyo Tower)"))
        assertEquals("Tokyo Tower", geo.name)
        assertEquals("Plus code detected", assertNotNull(detectCoordinates("8Q7XMP6W+9Q")).label)
    }

    @Test
    fun rateLabels() {
        assertEquals("1×", Format.rate(1.0))
        assertEquals("4×", Format.rate(4.0))
        assertEquals("1.5×", Format.rate(1.5))
    }
}

class JoystickPlacementTest {
    @Test
    fun fractionsRoundTripAndDefault() {
        // 1080×2400 screen, 400×400 pad → 680×2000 free.
        assertEquals(0 to 1440, JoystickPlacement.toPixels(null, null, 1080, 2400, 400, 400))
        assertEquals(0 to 2000, JoystickPlacement.toPixels(0f, 1f, 1080, 2400, 400, 400))
        assertEquals(170 to 500, JoystickPlacement.toPixels(0.25f, 0.25f, 1080, 2400, 400, 400))
        assertEquals(0.25f to 0.25f, JoystickPlacement.toFraction(170, 500, 1080, 2400, 400, 400))
        // Rotation: same fraction, new pixels.
        assertEquals(500 to 170, JoystickPlacement.toPixels(0.25f, 0.25f, 2400, 1080, 400, 400))
        // Pad bigger than the screen: no free space, stays at 0.
        assertEquals(0f to 0f, JoystickPlacement.toFraction(10, 10, 300, 300, 400, 400))
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

    @Test
    fun headings() {
        assertEquals(0, JoystickMath.heading(0.0))
        assertEquals(0, JoystickMath.heading(22.4))
        assertEquals(1, JoystickMath.heading(22.6))
        assertEquals(0, JoystickMath.heading(350.0))
        assertEquals(7, JoystickMath.heading(315.0))
        assertEquals(4, JoystickMath.heading(-180.0))
    }

    @Test
    fun litChevrons() {
        fun lit(heading: Int) = (0..3).filter { JoystickMath.isChevronLit(it, heading) }
        assertEquals(listOf(0), lit(0))
        assertEquals(listOf(0, 1), lit(1))
        assertEquals(listOf(1), lit(2))
        assertEquals(listOf(2, 3), lit(5))
        assertEquals(listOf(0, 3), lit(7))
    }

    @Test
    fun compassSnapsPastThreshold() {
        // 10° off north at half travel: snapped to due north, knob on the axis.
        val s = JoystickMath.steer(dx = 4.17f, dy = -23.6f, maxTravel = 48f, snap = true)
        assertEquals(0, s.heading)
        assertEquals(0.0, s.bearingDeg)
        assertTrue(abs(s.knob.x) < 0.001f && abs(s.magnitude - 0.5) < 0.01)
        // Below 7/48 of the travel the vector stays raw.
        val small = JoystickMath.steer(dx = 3f, dy = -3f, maxTravel = 48f, snap = true)
        assertEquals(null, small.heading)
        assertTrue(abs(small.bearingDeg - 45.0) < 0.01)
        // Halo never snaps, and clamps to the travel.
        val halo = JoystickMath.steer(dx = 100f, dy = -10f, maxTravel = 48f, snap = false)
        assertEquals(null, halo.heading)
        assertEquals(1.0, halo.magnitude)
        assertTrue(abs(halo.knob.getDistance() - 48f) < 0.01f)
    }

    @Test
    fun holdSlop() {
        assertTrue(!JoystickMath.breaksHold(3f, 4f, 6f))
        assertTrue(JoystickMath.breaksHold(5f, 4f, 6f))
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
        assertNull(ui.startAction)
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
        assertIs<HauntState.Idle>(fake.state.value)
        holder.start()
        assertIs<HauntState.Holding>(fake.state.value)

        holder.selectMode(MapMode.Route)
        holder.onMapLongPress(LatLng(1.0, 2.0))
        holder.onMapLongPress(LatLng(1.0, 2.01))
        assertEquals(2, holder.local.draftRoute.size)
        assertIs<HauntState.Holding>(fake.state.value)
        holder.start()
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
        assertIs<HauntState.Holding>(fake.state.value)
        holder.start()
        assertIs<HauntState.Joystick>(fake.state.value)
        holder.joystickInput(90.0, 1.0)
        fake.tick(10.0)
        val j = assertIs<HauntState.Joystick>(fake.state.value)
        assertTrue(j.fix.position.lng > 0)
        assertEquals(10 * 12 / 3.6, j.distanceMeters, 0.01)
        holder.selectMode(MapMode.Pin)
        assertIs<HauntState.Joystick>(fake.state.value)
        holder.stop()
        assertIs<HauntState.Idle>(fake.state.value)
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
