package io.github.crockalet.haunt.ui

import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.ServiceEndpoint
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.state.ServiceValidation
import io.github.crockalet.haunt.ui.state.buildMapUiState
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
    fun padSettingsReachTheJoystickDetails() {
        val defaults = HauntDefaults(
            joystickSize = JoystickSize.Large,
            floatingJoystick = true,
            joystickOffsetX = 30f,
            joystickOffsetY = -120f,
        )
        val local = LocalUiState(mode = MapMode.Joystick)
        val j = assertNotNull(buildMapUiState(HauntState.Idle, local, defaults).joystick)
        assertEquals(JoystickSize.Large, j.size)
        assertTrue(j.floating)
        assertEquals(30f, j.offsetX)
        assertEquals(-120f, j.offsetY)
        assertNull(buildMapUiState(HauntState.Idle, local.copy(mode = MapMode.Pin), defaults).joystick)
    }
}
