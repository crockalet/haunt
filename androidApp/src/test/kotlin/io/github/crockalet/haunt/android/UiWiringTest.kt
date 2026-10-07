package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.data.ActivityEntry
import io.github.crockalet.haunt.android.data.ActivitySource
import io.github.crockalet.haunt.android.data.FavoritesStore
import io.github.crockalet.haunt.android.data.HistoryEntry
import io.github.crockalet.haunt.android.settings.HauntSettings
import io.github.crockalet.haunt.android.settings.ThemeMode
import io.github.crockalet.haunt.android.settings.Units
import io.github.crockalet.haunt.android.ui.OnboardingFlow
import io.github.crockalet.haunt.android.ui.OnboardingInputs
import io.github.crockalet.haunt.android.ui.UiMapping
import io.github.crockalet.haunt.android.control.AndroidHauntApi
import io.github.crockalet.haunt.android.net.HttpException
import io.github.crockalet.haunt.android.ui.AndroidCommands
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.Travel
import io.github.crockalet.haunt.protocol.Favorite
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.ui.screens.OnboardingStep
import io.github.crockalet.haunt.ui.state.CommandException
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.MapStyle
import io.github.crockalet.haunt.ui.state.RouteRequest
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.theme.FolderColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiMappingTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun relativeDays() {
        val now = at(2026, 10, 6) // a Tuesday
        fun label(t: Long) = UiMapping.relativeDay(t, now, utc, Locale.US)
        assertEquals("Today", label(at(2026, 10, 6, 1)))
        assertEquals("Yesterday", label(at(2026, 10, 5, 23)))
        assertEquals("Fri", label(at(2026, 10, 2)))
        assertEquals("Sep 28", label(at(2026, 9, 28)))
        assertEquals("Dec 31, 2025", label(at(2025, 12, 31)))
    }

    @Test
    fun historyAndPlaces() {
        val now = at(2026, 10, 6)
        val places = UiMapping.history(listOf(HistoryEntry("Tokyo Tower", LatLng(35.6586, 139.7454), now)), now, utc)
        assertEquals("Today", places.single().meta)
        val p = UiMapping.place(Place("Hachiko", LatLng(0.0, 0.001), address = "Shibuya"), LatLng(0.0, 0.0))
        assertEquals("Shibuya", p.subtitle)
        assertEquals("111 m", p.meta)
    }

    @Test
    fun foldersFromFavourites() {
        val favs = listOf(
            Favorite("1", "A", LatLng(0.0, 0.0), folder = "QA"),
            Favorite("2", "B", LatLng(0.0, 0.0), folder = "Travel", color = "#D9822B"),
            Favorite("3", "C", LatLng(0.0, 0.0), folder = "QA", color = "nonsense"),
            Favorite("4", "D", LatLng(0.0, 0.0)),
        )
        val folders = UiMapping.folders(favs)
        assertEquals(listOf("QA", "Travel"), folders.map { it.name })
        assertEquals(FolderColors.Blue, folders[0].color)
        assertEquals(FolderColors.Orange, folders[1].color)
        assertEquals("QA", UiMapping.favourite(favs[0]).folderId)
    }

    @Test
    fun agentStatusFromConnectionsAndLog() {
        val log = listOf(
            ActivityEntry(at(2026, 10, 6, 14, 1), ActivitySource.Socket, "hello", "protocol=1, client=\"haunt-cli 0.1.0\""),
            ActivityEntry(at(2026, 10, 6, 14, 2), ActivitySource.Socket, "location.set", "lat=35.6, lng=139.7"),
            ActivityEntry(at(2026, 10, 6, 14, 3), ActivitySource.Broadcast, "haunt.STOP", "", errorCode = -32001, errorMessage = "off"),
        )
        assertNull(UiMapping.agentConnection(0, log, utc))
        val c = UiMapping.agentConnection(2, log, utc)!!
        assertEquals("haunt-cli", c.name)
        assertEquals("via adb · since 14:01 · 2 clients", c.via)
        assertEquals("haunt", UiMapping.agentConnection(1, emptyList(), utc)!!.name)

        val rows = UiMapping.log(log, utc)
        assertEquals("14:03", rows.first().time)
        assertEquals("haunt.STOP (broadcast)", rows.first().text)
        assertEquals(false, rows.first().ok)
        assertEquals("off", rows.first().detail)
        assertNull(rows[1].detail)
        assertEquals("location.set lat=35.6, lng=139.7", rows[1].text)
    }

    @Test
    fun settingsRoundTrip() {
        val s = HauntSettings(
            updateIntervalMillis = 500, accuracyMeters = 10f, units = Units.Imperial, theme = ThemeMode.Dark,
            joystickSize = "ExtraLarge", floatingJoystick = true, joystickOffsetX = 12f, joystickOffsetY = -40f,
        )
        val d = UiMapping.defaults(s, HauntDefaults(loop = LoopMode.Loop))
        assertEquals(
            HauntDefaults(
                updateRateHz = 2, accuracyMeters = 10f, metric = false, loop = LoopMode.Loop,
                joystickSize = JoystickSize.ExtraLarge, floatingJoystick = true, joystickOffsetX = 12f, joystickOffsetY = -40f,
            ),
            d,
        )
        assertEquals(JoystickSize.Medium, UiMapping.joystickSize(HauntSettings(joystickSize = "huge")))
        assertEquals(s, UiMapping.applyDefaults(HauntSettings(theme = ThemeMode.Dark), d))
        assertEquals(ThemeMode.Dark, UiMapping.theme(UiMapping.theme(ThemeMode.Dark)))
        assertEquals(MapStyle.Default, UiMapping.mapStyle(HauntSettings()))
        assertEquals(MapStyle.Custom("https://tiles.example.org/s.json"), UiMapping.mapStyle(HauntSettings(mapStyleUrl = "https://tiles.example.org/s.json")))
        assertEquals("tiles.example.org", UiMapping.services(HauntSettings(mapStyleUrl = "https://tiles.example.org/style.json"))[0].url)
        assertEquals(listOf("Haunt · OpenFreeMap", "Photon", "OSRM demo · FOSSGIS for walk/cycle"), UiMapping.services(HauntSettings()).map { it.provider })
    }

    @Test
    fun serviceEditorRoundTrip() {
        val custom = HauntSettings(routingUrl = "https://osrm.example.org", routingProfile = "foot")
        val routing = UiMapping.services(custom).single { it.kind == ServiceKind.Routing }
        assertEquals("https://osrm.example.org", routing.value)
        assertEquals(HauntSettings.DEFAULT_ROUTING_URL, routing.defaultValue)
        assertEquals("foot", routing.profile)
        assertEquals(HauntSettings.DEFAULT_ROUTING_PROFILE, routing.defaultProfile)
        assertEquals("OSRM (custom) · foot", routing.provider)
        assertNull(UiMapping.services(custom).single { it.kind == ServiceKind.Search }.profile)

        val s = HauntSettings()
        assertEquals(custom, UiMapping.applyService(s, ServiceKind.Routing, "https://osrm.example.org", "foot"))
        assertEquals(s.copy(searchUrl = "https://photon.example.org"), UiMapping.applyService(s, ServiceKind.Search, "https://photon.example.org", null))
        assertEquals(s.copy(mapStyleUrl = "https://tiles.example.org/s.json"), UiMapping.applyService(s, ServiceKind.MapStyle, "https://tiles.example.org/s.json", "ignored"))
    }
}

class OnboardingFlowTest {
    private val ready = OnboardingInputs(
        developerOptionsEnabled = true, mockAppSelected = true, locationPermission = true,
        notificationPermission = true, notificationAsked = true, completedBefore = false,
    )

    @Test
    fun firstLaunchWalksThroughEveryStep() {
        val none = ready.copy(developerOptionsEnabled = false, mockAppSelected = false, locationPermission = false, notificationPermission = false, notificationAsked = false)
        assertEquals(OnboardingStep.DeveloperOptions, OnboardingFlow.step(none))
        assertEquals(OnboardingStep.SelectMockApp, OnboardingFlow.step(none.copy(developerOptionsEnabled = true)))
        assertEquals(OnboardingStep.Permissions, OnboardingFlow.step(none.copy(developerOptionsEnabled = true, mockAppSelected = true)))
        // Location granted, notifications not asked yet: still on permissions.
        assertEquals(
            OnboardingStep.Permissions,
            OnboardingFlow.step(none.copy(developerOptionsEnabled = true, mockAppSelected = true, locationPermission = true)),
        )
        // Notifications denied once: optional, move on.
        assertEquals(OnboardingStep.Done, OnboardingFlow.step(ready.copy(notificationPermission = false)))
        assertEquals(OnboardingStep.Done, OnboardingFlow.step(ready))
        assertNull(OnboardingFlow.step(ready.copy(completedBefore = true)))
    }

    @Test
    fun laterLaunchesOnlyWhenMockingCantWork() {
        val done = ready.copy(completedBefore = true, notificationPermission = false, notificationAsked = false)
        assertNull(OnboardingFlow.step(done))
        assertEquals(OnboardingStep.SelectMockApp, OnboardingFlow.step(done.copy(mockAppSelected = false)))
        assertEquals(OnboardingStep.Permissions, OnboardingFlow.step(done.copy(locationPermission = false)))
        assertTrue(!done.copy(locationPermission = false).ready)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCommandsTest {
    private val dir: File = Files.createTempDirectory("haunt-cmd").toFile()
    private val router = FakeRouter()
    private val env = FakeEnvironment()
    private val a = LatLng(35.0, 139.0)
    private val b = LatLng(35.0, 139.01)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun TestScope.commands(): Pair<AndroidCommands, Harness> {
        val h = harness()
        val api = AndroidHauntApi(h.controller, FavoritesStore(File(dir, "f.json")), FakeGeocoder(), router, env)
        return AndroidCommands(api, h.controller, router, env::checkCanMock, env::onMockingStarted, UnconfinedTestDispatcher(testScheduler)) to h
    }

    private fun request(route: Route, followRoads: Boolean = false, rate: Double? = null) =
        RouteRequest(route, Speed.Walk, LoopMode.Once, followRoads, rate)

    @Test
    fun setLocationGoesThroughTheApi() = runTest {
        val (c, h) = commands()
        c.setLocation(a, 5f, "Pin")
        val s = assertIs<HauntState.Holding>(h.controller.state.value)
        assertEquals("Pin", s.label)
        assertEquals(1, env.started)
    }

    @Test
    fun joystickStartRunsTheSameChecks() = runTest {
        val (c, h) = commands()
        env.selected = false
        val e = assertFailsWith<CommandException> { c.startJoystick(Speed.kmh(12.0), a) }
        assertTrue(e.hint!!.isNotBlank())
        assertEquals(HauntState.Idle, h.controller.state.value)
        assertEquals(0, env.started)

        env.selected = true
        c.startJoystick(Speed.kmh(12.0), a)
        assertEquals(a, assertIs<HauntState.Joystick>(h.controller.state.value).fix.position)
        assertEquals(1, env.started)
    }

    @Test
    fun rpcErrorsBecomeCommandExceptionsWithHints() = runTest {
        val (c, h) = commands()
        env.selected = false
        val e = assertFailsWith<CommandException> { c.setLocation(a, null, null) }
        assertTrue(e.hint!!.isNotBlank())
        assertFailsWith<CommandException> { c.playRoute(request(Route(listOf(a, b)), followRoads = true)) }
        assertTrue(router.calls.isEmpty()) // checked before routing
        assertEquals(HauntState.Idle, h.controller.state.value)
    }

    @Test
    fun followRoadsReturnsTheRoutedLine() = runTest {
        val (c, h) = commands()
        val out = c.playRoute(request(Route(listOf(a, b), name = "trip"), followRoads = true))
        assertEquals(3, out.points.size)
        assertNull(out.warning)
        val m = assertIs<HauntState.Moving>(h.controller.state.value)
        assertEquals("trip", m.routeName)
        assertTrue(m.progress.totalMeters > 900)
        assertTrue(h.controller.currentRouteId != null)
    }

    @Test
    fun routingFailureIsAnErrorNotAStraightLine() = runTest {
        val (c, h) = commands()
        router.fail = true
        val e = assertFailsWith<CommandException> { c.playRoute(request(Route(listOf(a, b)), followRoads = true)) }
        assertTrue(e.message!!.startsWith("Couldn't follow roads"))
        assertTrue(e.hint!!.contains("Follow roads"))
        assertEquals(HauntState.Idle, h.controller.state.value)
        assertEquals(0, env.started)
    }

    @Test
    fun previewedLineIsPlayedWithoutRoutingAgain() = runTest {
        val (c, h) = commands()
        val routed = listOf(a, LatLng(35.001, 139.005), b)
        val out = c.playRoute(request(Route(listOf(a, b)), followRoads = true).copy(routed = routed))
        assertEquals(routed, out.points)
        assertTrue(router.calls.isEmpty())
        assertIs<HauntState.Moving>(h.controller.state.value)
    }

    @Test
    fun routeAlongRoadsForThePreview() = runTest {
        val (c, h) = commands()
        assertTrue(c.canFollowRoads)
        assertEquals(3, c.routeAlongRoads(listOf(a, b), Travel.Foot).size)
        assertEquals(HauntState.Idle, h.controller.state.value) // previewing doesn't start faking
        assertEquals(0, env.started)
        assertEquals(Travel.Foot, router.travels.last())

        router.failWith = UnknownHostException("router.project-osrm.org")
        assertEquals("Couldn't follow roads: can't reach the routing server", assertFailsWith<CommandException> { c.routeAlongRoads(listOf(a, b), Travel.Foot) }.message)
        router.failWith = HttpException(429, "HTTP 429 from router.project-osrm.org")
        assertTrue(assertFailsWith<CommandException> { c.routeAlongRoads(listOf(a, b), Travel.Foot) }.message!!.contains("busy"))
    }

    @Test
    fun recordedTracksKeepTheirTimingAndRate() = runTest {
        val (c, h) = commands()
        val track = Route(listOf(a, b), timestampsMillis = listOf(0L, 600_000L), name = "run")
        c.playRoute(request(track, rate = 2.0))
        val m = assertIs<HauntState.Moving>(h.controller.state.value)
        assertEquals(2.0, m.playbackRate)
        assertTrue(router.calls.isEmpty())
        assertEquals(1, env.started)
    }
}
