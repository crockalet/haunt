package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.net.RoutingEndpoint
import io.github.crockalet.haunt.android.settings.HauntSettings
import io.github.crockalet.haunt.android.settings.InMemoryKeyValueStore
import io.github.crockalet.haunt.android.settings.SettingsKeys
import io.github.crockalet.haunt.android.settings.SettingsStore
import io.github.crockalet.haunt.android.settings.ThemeMode
import io.github.crockalet.haunt.android.settings.Units
import io.github.crockalet.haunt.core.HauntDefaults
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.Travel
import io.github.crockalet.haunt.ui.state.UiScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun defaultRoutingPicksServerByTravel() {
        val d = HauntSettings()
        assertEquals(RoutingEndpoint(HauntSettings.FOOT_ROUTING_URL, "driving"), d.routingEndpoint(Travel.forSpeed(Speed.Walk)))
        assertEquals(RoutingEndpoint(HauntSettings.BIKE_ROUTING_URL, "driving"), d.routingEndpoint(Travel.forSpeed(Speed.Cycle)))
        assertEquals(RoutingEndpoint(HauntSettings.DEFAULT_ROUTING_URL, "driving"), d.routingEndpoint(Travel.forSpeed(Speed.Drive)))
        val custom = d.copy(routingUrl = "https://osrm.example")
        assertEquals(RoutingEndpoint("https://osrm.example", "driving"), custom.routingEndpoint(Travel.Foot))
        val foot = d.copy(routingProfile = "foot")
        assertEquals(RoutingEndpoint(HauntSettings.DEFAULT_ROUTING_URL, "foot"), foot.routingEndpoint(Travel.Bike))
    }

    @Test
    fun emptyStoreGivesDefaults() {
        assertEquals(HauntSettings(), SettingsKeys.read(InMemoryKeyValueStore()))
    }

    @Test
    fun roundTripsEveryField() {
        val settings = HauntSettings(
            adbControlEnabled = false,
            accuracyMeters = 12.5f,
            altitudeMeters = 40.0,
            updateIntervalMillis = 500,
            units = Units.Imperial,
            theme = ThemeMode.Dark,
            searchUrl = "https://photon.example.org",
            routingUrl = "https://osrm.example.org",
            routingProfile = "foot",
            mapStyleUrl = "https://tiles.example.org/style.json",
            joystickSize = "Large",
            joystickStyle = "Compass",
            joystickMoveLearned = true,
            floatingJoystick = true,
            joystickX = 0.25f,
            joystickY = 1f,
            uiScale = 1.25f,
        )
        val store = InMemoryKeyValueStore(SettingsKeys.entries(settings))
        assertEquals(settings, SettingsKeys.read(store))
        assertEquals(HauntDefaults(accuracy = 12.5f, altitude = 40.0), settings.defaults)
    }

    @Test
    fun oldDefaultMapStyleBecomesHauntStyle() {
        val store = InMemoryKeyValueStore(mapOf(SettingsKeys.MAP_STYLE_URL to HauntSettings.LEGACY_MAP_STYLE_URL))
        assertEquals(HauntSettings.DEFAULT_MAP_STYLE_URL, SettingsKeys.read(store).mapStyleUrl)
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val store = InMemoryKeyValueStore(
            mapOf(
                SettingsKeys.ACCURACY to -3f,
                SettingsKeys.ALTITUDE to "not a number",
                SettingsKeys.UPDATE_INTERVAL to 5L,
                SettingsKeys.UNITS to "furlongs",
                SettingsKeys.THEME to "dark",
                SettingsKeys.SEARCH_URL to "  ",
                SettingsKeys.OVERLAY_X to "1.7",
                SettingsKeys.OVERLAY_Y to "nope",
                SettingsKeys.JOYSTICK_STYLE to " ",
            ),
        )
        val s = SettingsKeys.read(store)
        assertEquals(5f, s.accuracyMeters)
        assertNull(s.altitudeMeters)
        assertEquals(HauntSettings.MIN_UPDATE_INTERVAL_MILLIS, s.updateIntervalMillis)
        assertEquals(Units.Metric, s.units)
        assertEquals(ThemeMode.Dark, s.theme) // case-insensitive
        assertEquals(HauntSettings.DEFAULT_SEARCH_URL, s.searchUrl)
        assertEquals(1f, s.joystickX) // clamped to the screen
        assertNull(s.joystickY)
        assertEquals(HauntSettings.DEFAULT_JOYSTICK_STYLE, s.joystickStyle)
        assertFalse(s.joystickMoveLearned)
    }

    @Test
    fun interfaceSizeIsClampedAndSnapped() {
        fun read(v: Float) = SettingsKeys.read(InMemoryKeyValueStore(mapOf(SettingsKeys.UI_SCALE to v))).uiScale
        assertEquals(1f, read(Float.NaN))
        assertEquals(1f, read(Float.POSITIVE_INFINITY))
        assertEquals(UiScale.MIN, read(0.1f))
        assertEquals(UiScale.MAX, read(9f))
        assertEquals(1.15f, read(1.137f))
        assertEquals(1f, SettingsKeys.read(InMemoryKeyValueStore()).uiScale)

        val kv = InMemoryKeyValueStore()
        SettingsStore(kv).update { it.copy(uiScale = 3f) }
        assertEquals(UiScale.MAX, kv.snapshot[SettingsKeys.UI_SCALE])
        assertEquals(UiScale.MAX, SettingsStore(kv).current.uiScale)
    }

    @Test
    fun storeUpdatesFlowAndPersists() {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        assertTrue(store.settings.value.adbControlEnabled)
        store.setAdbControlEnabled(false)
        assertFalse(store.settings.value.adbControlEnabled)
        assertEquals(false, kv.snapshot[SettingsKeys.ADB_CONTROL])

        store.update { it.copy(altitudeMeters = 12.0) }
        assertEquals("12.0", kv.snapshot[SettingsKeys.ALTITUDE])
        store.update { it.copy(altitudeMeters = null) }
        assertFalse(SettingsKeys.ALTITUDE in kv.snapshot)

        // A fresh store reads what was persisted.
        assertFalse(SettingsStore(kv).current.adbControlEnabled)
        store.reset()
        assertEquals(HauntSettings(), SettingsStore(kv).current)
    }
}
