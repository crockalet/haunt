package io.github.crockalet.haunt.android.ui

import androidx.compose.ui.graphics.Color
import io.github.crockalet.haunt.android.data.ActivityEntry
import io.github.crockalet.haunt.android.data.ActivitySource
import io.github.crockalet.haunt.android.data.HistoryEntry
import io.github.crockalet.haunt.android.data.SavedTrack
import io.github.crockalet.haunt.android.settings.HauntSettings
import io.github.crockalet.haunt.android.settings.Units
import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.protocol.Favorite
import io.github.crockalet.haunt.ui.state.AgentConnection
import io.github.crockalet.haunt.ui.state.Folder
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.LogEntry
import io.github.crockalet.haunt.ui.state.MapStyle
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.state.ServiceEndpoint
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.state.Track
import io.github.crockalet.haunt.ui.theme.FolderColors
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import io.github.crockalet.haunt.android.settings.ThemeMode as SettingsTheme
import io.github.crockalet.haunt.protocol.Place as ProtocolPlace
import io.github.crockalet.haunt.ui.theme.ThemeMode as UiTheme

/** Pure mappings from the Android runtime's data to the shared UI's models. */
object UiMapping {

    // --- places -------------------------------------------------------------------------------

    /** A search / reverse-geocoding result; [origin] adds a distance ("180 m") on the right. */
    fun place(p: ProtocolPlace, origin: LatLng?): Place = Place(
        name = p.name,
        position = p.position,
        subtitle = p.address,
        meta = origin?.let { Format.distance(Geo.distanceMeters(it, p.position)) },
    )

    fun favourite(f: Favorite): Place = Place(f.name, f.position, folderId = f.folder)

    /** Folders in first-use order; a favourite's `#RRGGBB` colour wins, otherwise the palette. */
    fun folders(favourites: List<Favorite>): List<Folder> {
        val byName = LinkedHashMap<String, Color?>()
        for (f in favourites) {
            val folder = f.folder?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val color = parseColor(f.color)
            if (folder !in byName || (byName[folder] == null && color != null)) byName[folder] = color
        }
        return byName.entries.mapIndexed { i, (name, color) ->
            Folder(name, name, color ?: FolderColors.all[i % FolderColors.all.size])
        }
    }

    fun parseColor(hex: String?): Color? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> Color(0xFF000000 or v)
            8 -> Color(v)
            else -> null
        }
    }

    fun history(entries: List<HistoryEntry>, nowMillis: Long, zone: ZoneId): List<Place> =
        entries.map { Place(it.name, it.position, meta = relativeDay(it.timeMillis, nowMillis, zone)) }

    /** "Today", "Yesterday", "Mon" (this week), "Sep 28" (this year), "Sep 28, 2025". */
    fun relativeDay(timeMillis: Long, nowMillis: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val day = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            day.year == today.year -> DateTimeFormatter.ofPattern("MMM d", locale).format(day)
            else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(day)
        }
    }

    // --- tracks -------------------------------------------------------------------------------

    fun track(t: SavedTrack): Track {
        val points = t.route.points
        val parts = buildList {
            add(Format.distance(Geo.polylineLength(points)))
            add(t.format)
            add("${points.size} pts")
        }
        return Track(t.name, parts.joinToString(" · "), points, t.route, t.id)
    }

    // --- agent control ------------------------------------------------------------------------

    /** Activity log rows, newest first. */
    fun log(entries: List<ActivityEntry>, zone: ZoneId): List<LogEntry> =
        entries.asReversed().map { e ->
            val text = listOf(e.method, e.summary).filter { it.isNotBlank() }.joinToString(" ")
            LogEntry(
                clock(e.timeMillis, zone),
                if (e.source == ActivitySource.Broadcast) "$text (broadcast)" else text,
                e.ok,
                e.errorMessage?.takeIf { it.isNotBlank() },
            )
        }

    /**
     * Who is connected over the ADB socket: [connections] comes from the control server; the client
     * name and "since" come from the latest `hello` in the activity log. Null when nobody is connected.
     */
    fun agentConnection(connections: Int, entries: List<ActivityEntry>, zone: ZoneId): AgentConnection? {
        if (connections <= 0) return null
        val hello = entries.lastOrNull { it.method == "hello" && it.source == ActivitySource.Socket && it.ok }
        val client = hello?.summary?.let { CLIENT.find(it)?.groupValues?.get(1) }?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val via = buildString {
            append("via adb")
            hello?.let { append(" · since ").append(clock(it.timeMillis, zone)) }
            if (connections > 1) append(" · $connections clients")
        }
        return AgentConnection(client ?: "haunt", via)
    }

    private val CLIENT = Regex("""client="?([^",]+)"?""")

    private fun clock(timeMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(timeMillis).atZone(zone))

    // --- settings -----------------------------------------------------------------------------

    fun services(s: HauntSettings): List<ServiceEndpoint> = listOf(
        ServiceEndpoint(
            "Map style",
            if (s.mapStyleUrl == HauntSettings.DEFAULT_MAP_STYLE_URL) "Haunt · OpenFreeMap" else "Custom",
            host(s.mapStyleUrl),
            ServiceKind.MapStyle,
            s.mapStyleUrl,
            HauntSettings.DEFAULT_MAP_STYLE_URL,
        ),
        ServiceEndpoint(
            "Place search",
            if (s.searchUrl == HauntSettings.DEFAULT_SEARCH_URL) "Photon" else "Photon (custom)",
            host(s.searchUrl),
            ServiceKind.Search,
            s.searchUrl,
            HauntSettings.DEFAULT_SEARCH_URL,
        ),
        ServiceEndpoint(
            "Routing",
            when {
                s.routesByTravel -> "OSRM demo · FOSSGIS for walk/cycle"
                s.routingUrl == HauntSettings.DEFAULT_ROUTING_URL -> "OSRM demo · ${s.routingProfile}"
                else -> "OSRM (custom) · ${s.routingProfile}"
            },
            host(s.routingUrl),
            ServiceKind.Routing,
            s.routingUrl,
            HauntSettings.DEFAULT_ROUTING_URL,
            profile = s.routingProfile,
            defaultProfile = HauntSettings.DEFAULT_ROUTING_PROFILE,
        ),
    )

    /** Applies an edit from the service editor ([profile] only matters for routing). */
    fun applyService(s: HauntSettings, kind: ServiceKind, url: String, profile: String?): HauntSettings = when (kind) {
        ServiceKind.MapStyle -> s.copy(mapStyleUrl = url)
        ServiceKind.Search -> s.copy(searchUrl = url)
        ServiceKind.Routing -> s.copy(routingUrl = url, routingProfile = profile ?: s.routingProfile)
    }

    private fun host(url: String): String = runCatching { URI(url.trim()).host }.getOrNull() ?: url.trim()

    /** The default is Haunt's own style (follows the theme); a custom URL is used for both themes. */
    fun mapStyle(s: HauntSettings): MapStyle =
        if (s.mapStyleUrl == HauntSettings.DEFAULT_MAP_STYLE_URL) MapStyle.Default else MapStyle.Custom(s.mapStyleUrl)

    fun theme(t: SettingsTheme): UiTheme = when (t) {
        SettingsTheme.System -> UiTheme.System
        SettingsTheme.Light -> UiTheme.Light
        SettingsTheme.Dark -> UiTheme.Dark
    }

    fun theme(t: UiTheme): SettingsTheme = when (t) {
        UiTheme.System -> SettingsTheme.System
        UiTheme.Light -> SettingsTheme.Light
        UiTheme.Dark -> SettingsTheme.Dark
    }

    /** Settings → the UI's Defaults section ([base] keeps UI-only fields such as loop). */
    fun defaults(s: HauntSettings, base: HauntDefaults = HauntDefaults()): HauntDefaults = base.copy(
        updateRateHz = (1000L / s.updateIntervalMillis.coerceAtLeast(1)).toInt().coerceAtLeast(1),
        accuracyMeters = s.accuracyMeters,
        metric = s.units == Units.Metric,
        joystickSize = joystickSize(s),
        floatingJoystick = s.floatingJoystick,
        joystickOffsetX = s.joystickOffsetX,
        joystickOffsetY = s.joystickOffsetY,
    )

    fun applyDefaults(s: HauntSettings, d: HauntDefaults): HauntSettings = s.copy(
        updateIntervalMillis = 1000L / d.updateRateHz.coerceAtLeast(1),
        accuracyMeters = d.accuracyMeters,
        units = if (d.metric) Units.Metric else Units.Imperial,
        joystickSize = d.joystickSize.name,
        floatingJoystick = d.floatingJoystick,
        joystickOffsetX = d.joystickOffsetX,
        joystickOffsetY = d.joystickOffsetY,
    )

    fun joystickSize(s: HauntSettings): JoystickSize =
        JoystickSize.entries.firstOrNull { it.name.equals(s.joystickSize, ignoreCase = true) } ?: JoystickSize.Medium
}
