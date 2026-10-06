package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed

/** The three map modes in the toolbar. */
enum class MapMode { Pin, Route, Joystick }

/** Speed chips in the route card. */
enum class SpeedPreset(val label: String, val speed: Speed?) {
    Walk("Walk", Speed.Walk),
    Cycle("Cycle", Speed.Cycle),
    Drive("Drive", Speed.Drive),
    Custom("Custom", null),
}

/** A place in search results, favourites, history or recents. */
@Immutable
data class Place(
    val name: String,
    val position: LatLng,
    /** Secondary line ("Dogenzaka, Shibuya"). Null → coordinates are shown. */
    val subtitle: String? = null,
    /** Right-aligned meta ("20 m", "Today"). */
    val meta: String? = null,
    val folderId: String? = null,
)

@Immutable
data class Folder(val id: String, val name: String, val color: Color)

/**
 * A saved track. [route] carries the full recorded data (timestamps, altitudes) when known, so a
 * GPX track can be replayed by its recorded timing; otherwise [points] are played at a constant speed.
 */
@Immutable
data class Track(
    val name: String,
    val subtitle: String,
    val points: List<LatLng> = emptyList(),
    val route: Route? = null,
    val id: String? = null,
)

/** An activity-log row; [detail] is the error message of a failed call. */
@Immutable
data class LogEntry(val time: String, val text: String, val ok: Boolean = true, val detail: String? = null)

@Immutable
data class AgentConnection(val name: String, val via: String)

/** The configurable network services (Settings → Map & services). */
enum class ServiceKind { MapStyle, Search, Routing }

/**
 * A row in Settings → Map & services, and what its editor needs.
 *
 * @property url what the row shows (usually the host).
 * @property kind null for display-only rows (previews); otherwise the row opens the editor.
 * @property value the full configured URL; [defaultValue] is what "Reset" restores.
 * @property profile routing profile ("driving", "foot"…); null for services without one.
 */
@Immutable
data class ServiceEndpoint(
    val title: String,
    val provider: String,
    val url: String,
    val kind: ServiceKind? = null,
    val value: String = url,
    val defaultValue: String = value,
    val profile: String? = null,
    val defaultProfile: String? = profile,
)

/** Checks service URLs and routing profiles typed into the endpoint editor. */
object ServiceValidation {
    private val URL = Regex("""^https?://[^\s/?#@:]+(:\d{1,5})?([/?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private val PROFILE = Regex("""^[A-Za-z0-9_-]{1,32}$""")

    /** Common OSRM profiles offered as chips (the public demo only serves `driving`). */
    val Profiles = listOf("driving", "foot", "bike")

    /** Null when [url] is an absolute http(s) URL with a host; otherwise why not. */
    fun urlError(url: String): String? = when {
        url.isBlank() -> "Enter a URL"
        !url.trim().startsWith("http://", ignoreCase = true) && !url.trim().startsWith("https://", ignoreCase = true) ->
            "Use an http:// or https:// URL"
        !URL.matches(url.trim()) -> "That doesn't look like a URL"
        else -> null
    }

    /** Null when [profile] is a plain profile name (letters, digits, - and _). */
    fun profileError(profile: String): String? = when {
        profile.isBlank() -> "Enter a profile, e.g. driving"
        !PROFILE.matches(profile.trim()) -> "Letters, digits, - and _ only"
        else -> null
    }
}

/**
 * Result of parsing pasted text as coordinates (see [detectCoordinates]).
 *
 * @property format how it was written ("DMS", "Plus code"…), shown as "<format> detected".
 * @property name a place label carried by the input (geo: URI label, Maps `/place/Name/`).
 */
@Immutable
data class DetectedCoordinates(val position: LatLng, val format: String? = null, val name: String? = null) {
    val label: String get() = format?.let { "$it detected" } ?: "Coordinates detected"
}

/**
 * A short message shown in a glass banner over the map (e.g. why mocking couldn't start).
 * [hint] is a second, muted line telling the user what to do.
 */
@Immutable
data class Notice(val text: String, val hint: String? = null, val error: Boolean = true)

/** Joystick pad sizes (diameter in dp); the knob scales with the pad. */
enum class JoystickSize(val label: String, val dp: Float) {
    Small("S", 112f),
    Medium("M", 140f),
    Large("L", 176f),
    ExtraLarge("XL", 216f),
}

/** Default per-user settings shown in Settings → Defaults. */
@Immutable
data class HauntDefaults(
    val updateRateHz: Int = 1,
    val accuracyMeters: Float = 5f,
    val metric: Boolean = true,
    val loop: LoopMode = LoopMode.Once,
    val joystickSize: JoystickSize = JoystickSize.Medium,
    /** Show the joystick over other apps while Haunt is in the background (Android). */
    val floatingJoystick: Boolean = false,
    /** Where the user dragged the in-app pad, in dp from its default spot (bottom-left). */
    val joystickOffsetX: Float = 0f,
    val joystickOffsetY: Float = 0f,
)

/** MapLibre style URLs; see https://openfreemap.org. */
@Immutable
data class MapStyle(val lightUrl: String, val darkUrl: String) {
    fun url(dark: Boolean) = if (dark) darkUrl else lightUrl

    companion object {
        val OpenFreeMap = MapStyle(
            lightUrl = "https://tiles.openfreemap.org/styles/positron",
            darkUrl = "https://tiles.openfreemap.org/styles/dark",
        )
    }
}
