package io.github.crockalet.haunt.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.JoystickStyle
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.ServiceEndpoint
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.state.ServiceValidation
import io.github.crockalet.haunt.ui.state.UiScale
import io.github.crockalet.haunt.ui.state.buildMapUiState
import io.github.crockalet.haunt.ui.theme.LocalChromeScale
import io.github.crockalet.haunt.ui.theme.ScaledChrome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceValidationTest {
    @Test
    fun urls() {
        listOf(
            "https://photon.komoot.io",
            "http://192.168.1.20:5000",
            "https://tiles.example.org/styles/positron?key=abc",
            "  https://router.project-osrm.org/  ",
            "HTTPS://Example.ORG",
        ).forEach { assertNull(ServiceValidation.urlError(it), it) }
        listOf("", "   ", "photon.komoot.io", "ftp://example.org", "https://", "https://:80", "https://exa mple.org", "https://a.org:123456")
            .forEach { assertNotNull(ServiceValidation.urlError(it), it) }
    }

    @Test
    fun profiles() {
        listOf("driving", "foot", "bike", "car_fast", "my-profile").forEach { assertNull(ServiceValidation.profileError(it), it) }
        listOf("", "foot/bar", "a b", "x".repeat(33)).forEach { assertNotNull(ServiceValidation.profileError(it), it) }
    }
}

class SettingsNavigationTest {
    private val routing = HauntAppData.DefaultServices.single { it.kind == ServiceKind.Routing }
        .copy(value = "https://osrm.example.org", profile = "foot")

    private fun state() = HauntAppState(FakeHauntController(HauntState.Idle), initialScreen = Screen.Settings)

    @Test
    fun logAndServiceGoBackToSettings() {
        val s = state()
        s.navigate(Screen.ActivityLog)
        assertTrue(s.back())
        assertEquals(Screen.Settings, s.screen)
        s.editService(routing)
        assertEquals(Screen.Service, s.screen)
        assertTrue(s.back())
        assertEquals(Screen.Settings, s.screen)
        assertTrue(s.back())
        assertEquals(Screen.Map, s.screen)
    }

    @Test
    fun displayOnlyRowsDoNotOpenTheEditor() {
        val s = state()
        s.editService(ServiceEndpoint("Map style", "OpenFreeMap", "tiles.openfreemap.org"))
        assertEquals(Screen.Settings, s.screen)
        assertNull(s.service)
    }

    @Test
    fun editValidateSaveAndReset() {
        val s = state()
        s.editService(routing)
        assertEquals("https://osrm.example.org", s.serviceUrl)
        assertEquals("foot", s.serviceProfile)

        val saved = mutableListOf<Triple<ServiceKind, String, String?>>()
        s.serviceUrl = "osrm.example.org"
        assertNotNull(s.serviceUrlError)
        assertFalse(s.saveService { k, u, p -> saved += Triple(k, u, p) })
        assertEquals(Screen.Service, s.screen)

        s.serviceUrl = " https://osrm2.example.org/ "
        s.serviceProfile = "bad profile"
        assertNotNull(s.serviceProfileError)
        assertFalse(s.saveService { k, u, p -> saved += Triple(k, u, p) })

        s.resetService()
        assertEquals(routing.defaultValue, s.serviceUrl)
        assertEquals("driving", s.serviceProfile)
        s.serviceProfile = " bike "
        assertTrue(s.saveService { k, u, p -> saved += Triple(k, u, p) })
        assertEquals(listOf<Triple<ServiceKind, String, String?>>(Triple(ServiceKind.Routing, "https://router.project-osrm.org", "bike")), saved)
        assertEquals(Screen.Settings, s.screen)
    }

    @Test
    fun servicesWithoutProfileSaveNull() {
        val s = state()
        s.editService(HauntAppData.DefaultServices.single { it.kind == ServiceKind.Search })
        assertNull(s.serviceProfileError)
        var profile: String? = "unset"
        assertTrue(s.saveService { _, _, p -> profile = p })
        assertNull(profile)
    }
}

class JoystickDefaultsTest {
    @Test
    fun imperialShowsJoystickSpeedsInMph() {
        val local = LocalUiState(mode = MapMode.Joystick, joystickMaxKmh = 16.09344f, joystickMagnitude = 1.0)
        assertEquals("10 mph", assertNotNull(buildMapUiState(HauntState.Idle, local, HauntDefaults(metric = false)).joystick).speed)
        assertEquals("16 km/h", assertNotNull(buildMapUiState(HauntState.Idle, local, HauntDefaults()).joystick).speed)
    }

    @Test
    fun padSettingsReachTheJoystickDetails() {
        val defaults = HauntDefaults(
            joystickSize = JoystickSize.Large,
            joystickStyle = JoystickStyle.Compass,
            floatingJoystick = true,
            joystickX = 1f,
            joystickY = 0.4f,
        )
        val local = LocalUiState(mode = MapMode.Joystick)
        val j = assertNotNull(buildMapUiState(HauntState.Idle, local, defaults).joystick)
        assertEquals(JoystickSize.Large, j.size)
        assertEquals(JoystickStyle.Compass, j.style)
        assertTrue(j.showMoveHint)
        val learned = buildMapUiState(HauntState.Idle, local, defaults.copy(joystickMoveLearned = true)).joystick
        assertFalse(assertNotNull(learned).showMoveHint)
        assertTrue(j.floating)
        assertEquals(1f, j.x)
        assertEquals(0.4f, j.y)
        assertNull(buildMapUiState(HauntState.Idle, local.copy(mode = MapMode.Pin), defaults).joystick)
    }
}

class UiScaleTest {
    @Test
    fun clampsSnapsAndFallsBack() {
        assertEquals(1f, UiScale.clamp(Float.NaN))
        assertEquals(1f, UiScale.clamp(Float.NEGATIVE_INFINITY))
        assertEquals(UiScale.MIN, UiScale.clamp(0f))
        assertEquals(UiScale.MAX, UiScale.clamp(2f))
        assertEquals(1.05f, UiScale.clamp(1.04f))
        assertEquals(UiScale.BASE, UiScale.factor(HauntDefaults().uiScale))
        assertEquals(UiScale.BASE * 1.5f, UiScale.factor(4f))
    }

    @Test
    fun labels() {
        assertEquals("1.00×", Format.scale(1f))
        assertEquals("0.75×", Format.scale(0.75f))
        assertEquals("1.15×", Format.scale(UiScale.clamp(1.149f)))
    }

    @Test
    fun scaledChromeScalesDpAndSpButNotPx() {
        var box = IntSize.Zero
        var textPx = 0f
        var nestedPx = 0f
        ImageComposeScene(400, 400, density = Density(2f)) {
            CompositionLocalProvider(LocalChromeScale provides 0.5f) {
                ScaledChrome {
                    Box(Modifier.size(100.dp).onSizeChanged { box = it })
                    textPx = with(LocalDensity.current) { 20.sp.toPx() }
                    // Nested chrome (e.g. the map credits) mustn't scale twice.
                    ScaledChrome { nestedPx = with(LocalDensity.current) { 10.dp.toPx() } }
                }
            }
        }.use { it.render() }
        assertEquals(IntSize(100, 100), box)
        assertEquals(20f, textPx)
        assertEquals(10f, nestedPx)
    }
}
