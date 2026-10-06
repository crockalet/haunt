package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.settings.HauntSettings
import io.github.crockalet.haunt.android.settings.InMemoryKeyValueStore
import io.github.crockalet.haunt.android.settings.SettingsKeys
import io.github.crockalet.haunt.android.settings.SettingsStore
import io.github.crockalet.haunt.android.settings.ThemeMode
import io.github.crockalet.haunt.android.settings.Units
import io.github.crockalet.haunt.core.HauntDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
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
            floatingJoystick = true,
            joystickOffsetX = 24f,
            joystickOffsetY = -180f,
            overlayX = 0.25f,
            overlayY = 1f,
        )
        val store = InMemoryKeyValueStore(SettingsKeys.entries(settings))
        assertEquals(settings, SettingsKeys.read(store))
        assertEquals(HauntDefaults(accuracy = 12.5f, altitude = 40.0), settings.defaults)
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
                SettingsKeys.JOYSTICK_OFFSET_X to Float.NaN,
            ),
        )
        val s = SettingsKeys.read(store)
        assertEquals(5f, s.accuracyMeters)
        assertNull(s.altitudeMeters)
        assertEquals(HauntSettings.MIN_UPDATE_INTERVAL_MILLIS, s.updateIntervalMillis)
        assertEquals(Units.Metric, s.units)
        assertEquals(ThemeMode.Dark, s.theme) // case-insensitive
        assertEquals(HauntSettings.DEFAULT_SEARCH_URL, s.searchUrl)
        assertEquals(1f, s.overlayX) // clamped to the screen
        assertNull(s.overlayY)
        assertEquals(0f, s.joystickOffsetX)
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
