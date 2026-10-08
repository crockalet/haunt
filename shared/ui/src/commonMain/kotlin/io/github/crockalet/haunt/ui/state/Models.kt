package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable
import io.github.crockalet.haunt.ui.map.HauntMapStyle
import androidx.compose.ui.graphics.Color
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import kotlin.math.roundToInt

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

/** Joystick sizes: the travel ring's outer diameter in dp; knob, chevrons and touch area scale with it. */
enum class JoystickSize(val label: String, val dp: Float) {
    Small("S", 90f),
    Medium("M", 112f),
    Large("L", 140f),
    ExtraLarge("XL", 172f),
}

/**
 * The joystick's spot as a fraction (0..1) of the screen's free width / height (screen minus the pad), so
 * the in-app pad and the one floating over other apps sit in the same place and survive rotation.
 */
object JoystickPlacement {
    /** Default spot: left edge, low enough for a thumb but clear of the bottom bar. */
    const val DEFAULT_X = 0f
    const val DEFAULT_Y = 0.72f

    /** Top-left (px) for a stored fraction; null fractions mean the default spot. */
    fun toPixels(fx: Float?, fy: Float?, screenW: Int, screenH: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val freeW = (screenW - viewW).coerceAtLeast(0)
        val freeH = (screenH - viewH).coerceAtLeast(0)
        return ((fx ?: DEFAULT_X).coerceIn(0f, 1f) * freeW).roundToInt() to ((fy ?: DEFAULT_Y).coerceIn(0f, 1f) * freeH).roundToInt()
    }

    /** Inverse of [toPixels]: the fraction to persist. */
    fun toFraction(x: Int, y: Int, screenW: Int, screenH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {
        val freeW = (screenW - viewW).coerceAtLeast(0)
        val freeH = (screenH - viewH).coerceAtLeast(0)
        fun f(v: Int, free: Int) = if (free == 0) 0f else (v.toFloat() / free).coerceIn(0f, 1f)
        return f(x, freeW) to f(y, freeH)
    }
}

/** How the joystick is drawn. Both steer the same way and move with a hold on the knob. */
enum class JoystickStyle(val label: String) {
    /** Thin ring + knob; an accent arc and tether show direction and speed while driving. */
    Halo("Halo"),

    /** Four chevrons around a hollow knob; snaps to 8 headings with a tick on each change. */
    Compass("Compass"),
}

/** Default per-user settings shown in Settings → Defaults. */
@Immutable
data class HauntDefaults(
    val updateRateHz: Int = 1,
    val accuracyMeters: Float = 5f,
    val metric: Boolean = true,
    val loop: LoopMode = LoopMode.Once,
    val joystickSize: JoystickSize = JoystickSize.Medium,
    val joystickStyle: JoystickStyle = JoystickStyle.Halo,
    /** The user has moved the pad with a hold at least once, so the pad stops showing the hint. */
    val joystickMoveLearned: Boolean = false,
    /** Show the joystick over other apps while Haunt is in the background (Android). */
    val floatingJoystick: Boolean = false,
    /** Where the pad sits, shared by the in-app and floating pad (see [JoystickPlacement]); null = default spot. */
    val joystickX: Float? = null,
    val joystickY: Float? = null,
    /** Settings → Interface size, on top of [UiScale.BASE] (see [UiScale]). */
    val uiScale: Float = UiScale.DEFAULT,
)

/**
 * How big Haunt's chrome (not the map) is drawn: [BASE] × the user's "Interface size" ([MIN]..[MAX],
 * 1 = the default look).
 */
object UiScale {
    /** The chrome's size at "1×" relative to its design size in dp / sp. */
    const val BASE = 0.85f
    const val DEFAULT = 1f
    const val MIN = 0.75f
    const val MAX = 1.5f
    private const val STEPS_PER_UNIT = 20
    const val STEP = 1f / STEPS_PER_UNIT

    /** [user] snapped to [STEP] within [MIN]..[MAX]; [DEFAULT] when it isn't a number. */
    fun clamp(user: Float): Float {
        if (!user.isFinite()) return DEFAULT
        return (user.coerceIn(MIN, MAX) * STEPS_PER_UNIT).roundToInt() / STEPS_PER_UNIT.toFloat()
    }

    /** The density factor for the user's setting. */
    fun factor(user: Float): Float = BASE * clamp(user)
}

/** What the map draws: Haunt's own style (follows light / dark) or any MapLibre style URL. */
@Immutable
sealed interface MapStyle {
    /** [HauntMapStyle] over [tilesUrl], an OpenMapTiles-schema TileJSON (OpenFreeMap by default). */
    data class Haunt(val tilesUrl: String = HauntMapStyle.OPENFREEMAP_TILES) : MapStyle

    /** A MapLibre style JSON URL, used for both themes. */
    data class Custom(val url: String) : MapStyle

    companion object {
        val Default: MapStyle = Haunt()
    }
}
