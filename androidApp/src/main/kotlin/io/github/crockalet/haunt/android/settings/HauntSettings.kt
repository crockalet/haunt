package io.github.crockalet.haunt.android.settings

import io.github.crockalet.haunt.core.HauntDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

enum class Units { Metric, Imperial }

enum class ThemeMode { System, Light, Dark }

/**
 * User settings (Settings screen + ADB control). Immutable snapshot; change it through
 * [SettingsStore.update].
 */
data class HauntSettings(
    /** "Allow ADB control": when off, every socket / broadcast call fails with ADB_CONTROL_DISABLED. */
    val adbControlEnabled: Boolean = true,
    /** Horizontal accuracy (metres) reported on fixes unless a command overrides it. */
    val accuracyMeters: Float = 5f,
    /** Default altitude (metres) when neither the command nor the route gives one; null = none. */
    val altitudeMeters: Double? = null,
    /** Fix rate. Applied when the process starts (the movement engine's tick is fixed per process). */
    val updateIntervalMillis: Long = 1000L,
    val units: Units = Units.Metric,
    val theme: ThemeMode = ThemeMode.System,
    /** Photon base URL (search / geocoding). */
    val searchUrl: String = DEFAULT_SEARCH_URL,
    /** OSRM base URL (road-following routes). */
    val routingUrl: String = DEFAULT_ROUTING_URL,
    /** OSRM profile segment, e.g. "driving", "foot", "bike" (the public demo only serves driving). */
    val routingProfile: String = DEFAULT_ROUTING_PROFILE,
    /** MapLibre style URL for the map (used by the UI). */
    val mapStyleUrl: String = DEFAULT_MAP_STYLE_URL,
) {
    val defaults: HauntDefaults get() = HauntDefaults(accuracy = accuracyMeters, altitude = altitudeMeters)

    companion object {
        const val DEFAULT_SEARCH_URL = "https://photon.komoot.io"
        const val DEFAULT_ROUTING_URL = "https://router.project-osrm.org"
        const val DEFAULT_ROUTING_PROFILE = "driving"
        const val DEFAULT_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val MIN_UPDATE_INTERVAL_MILLIS = 100L
        const val MAX_UPDATE_INTERVAL_MILLIS = 10_000L
    }
}

/** SharedPreferences keys and the mapping between [HauntSettings] and a [KeyValueStore]. */
object SettingsKeys {
    const val ADB_CONTROL = "adb_control_enabled"
    const val ACCURACY = "default_accuracy_m"
    /** Stored as a string so "no altitude" is representable. */
    const val ALTITUDE = "default_altitude_m"
    const val UPDATE_INTERVAL = "update_interval_ms"
    const val UNITS = "units"
    const val THEME = "theme"
    const val SEARCH_URL = "search_url"
    const val ROUTING_URL = "routing_url"
    const val ROUTING_PROFILE = "routing_profile"
    const val MAP_STYLE_URL = "map_style_url"

    /** Reads settings, falling back to defaults for missing or invalid values. */
    fun read(store: KeyValueStore): HauntSettings {
        val d = HauntSettings()
        return HauntSettings(
            adbControlEnabled = store.getBoolean(ADB_CONTROL, d.adbControlEnabled),
            accuracyMeters = store.getFloat(ACCURACY, d.accuracyMeters).takeIf { it.isFinite() && it > 0f } ?: d.accuracyMeters,
            altitudeMeters = store.getString(ALTITUDE)?.toDoubleOrNull()?.takeIf { it.isFinite() },
            updateIntervalMillis = store.getLong(UPDATE_INTERVAL, d.updateIntervalMillis)
                .coerceIn(HauntSettings.MIN_UPDATE_INTERVAL_MILLIS, HauntSettings.MAX_UPDATE_INTERVAL_MILLIS),
            units = enumOrDefault(store.getString(UNITS), d.units),
            theme = enumOrDefault(store.getString(THEME), d.theme),
            searchUrl = store.getString(SEARCH_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: d.searchUrl,
            routingUrl = store.getString(ROUTING_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: d.routingUrl,
            routingProfile = store.getString(ROUTING_PROFILE)?.trim()?.takeIf { it.isNotEmpty() } ?: d.routingProfile,
            mapStyleUrl = store.getString(MAP_STYLE_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: d.mapStyleUrl,
        )
    }

    /** All keys and values of [settings], ready for [KeyValueStore.putAll]. */
    fun entries(settings: HauntSettings): Map<String, Any?> = mapOf(
        ADB_CONTROL to settings.adbControlEnabled,
        ACCURACY to settings.accuracyMeters,
        ALTITUDE to settings.altitudeMeters?.toString(),
        UPDATE_INTERVAL to settings.updateIntervalMillis,
        UNITS to settings.units.name,
        THEME to settings.theme.name,
        SEARCH_URL to settings.searchUrl,
        ROUTING_URL to settings.routingUrl,
        ROUTING_PROFILE to settings.routingProfile,
        MAP_STYLE_URL to settings.mapStyleUrl,
    )

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default
}

/** Persistent settings exposed as a [StateFlow]. Thread-safe. */
class SettingsStore(private val store: KeyValueStore) {
    private val _settings = MutableStateFlow(SettingsKeys.read(store))

    val settings: StateFlow<HauntSettings> = _settings.asStateFlow()

    val current: HauntSettings get() = _settings.value

    /** Atomically transforms and persists the settings; returns the new value. */
    fun update(transform: (HauntSettings) -> HauntSettings): HauntSettings = synchronized(this) {
        val previous = _settings.value
        val next = SettingsKeys.read(InMemoryKeyValueStore(SettingsKeys.entries(transform(previous)))) // normalise
        if (next != previous) {
            val old = SettingsKeys.entries(previous)
            store.putAll(SettingsKeys.entries(next).filter { (k, v) -> old[k] != v || !store.contains(k) })
            _settings.value = next
        }
        next
    }

    fun setAdbControlEnabled(enabled: Boolean) = update { it.copy(adbControlEnabled = enabled) }

    /** Restores every setting to its default. */
    fun reset() = update { HauntSettings() }
}
