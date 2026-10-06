package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.map.HauntMapStyle
import io.github.crockalet.haunt.ui.theme.FolderColors

/**
 * Data and app-level hooks the UI shows but doesn't own (favourites, history, agent status…).
 * The Android app fills this from storage and the control server; [Sample] is for previews.
 */
@Immutable
data class HauntAppData(
    val folders: List<Folder> = emptyList(),
    val favourites: List<Place> = emptyList(),
    val history: List<Place> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val recent: List<Place> = emptyList(),
    /** Places near a point (reverse geocoding / POIs). */
    val nearby: (LatLng) -> List<Place> = { emptyList() },
    /** Free-text place search (synchronous; previews). Ignored when [placeSearch] is set. */
    val search: (String) -> List<Place> = { emptyList() },
    /** Debounced, asynchronous search + nearby places (the app's geocoder). Overrides [search] / [nearby] / [areaName]. */
    val placeSearch: PlaceSearch? = null,
    /** Short area name for a coordinate ("Shibuya, Tokyo"), if known. */
    val areaName: (LatLng) -> String? = { null },
    val adbControlEnabled: Boolean = false,
    val onAdbControlChange: (Boolean) -> Unit = {},
    val agentConnection: AgentConnection? = null,
    val activityLog: List<LogEntry> = emptyList(),
    val services: List<ServiceEndpoint> = DefaultServices,
    val onImportTrack: () -> Unit = {},
    val onSaveFavourite: (Place) -> Unit = {},
    /** Settings → service editor saved: new URL and, for routing, profile (both trimmed and validated). */
    val onServiceSave: (ServiceKind, url: String, profile: String?) -> Unit = { _, _, _ -> },
    val onClearLog: () -> Unit = {},
    /**
     * The device's real location for the locate button; null hides the button. Throw
     * [CommandException] (message + hint) when it can't be found.
     */
    val locateMe: (suspend () -> LatLng)? = null,
    /** Settings → Defaults changed (update rate, accuracy, units); persist them. */
    val onDefaultsChange: (HauntDefaults) -> Unit = {},
) {
    companion object {
        val DefaultServices = listOf(
            ServiceEndpoint("Map style", "Haunt · OpenFreeMap", "tiles.openfreemap.org", ServiceKind.MapStyle, HauntMapStyle.OPENFREEMAP_TILES),
            ServiceEndpoint("Place search", "Photon", "photon.komoot.io", ServiceKind.Search, "https://photon.komoot.io"),
            ServiceEndpoint("Routing", "OSRM demo", "router.project-osrm.org", ServiceKind.Routing, "https://router.project-osrm.org", profile = "driving"),
        )

        /** Sample content from the mockups. */
        val Sample: HauntAppData by lazy { SampleData.appData }
    }
}

/** Sample content (Tokyo) used by previews, screenshots and the fake-controller build. */
object SampleData {
    val ShibuyaCrossing = LatLng(35.65952, 139.70055)

    val folders = listOf(
        Folder("qa", "QA Tokyo", FolderColors.Blue),
        Folder("delivery", "Delivery", FolderColors.Green),
        Folder("travel", "Travel", FolderColors.Orange),
    )

    val favourites = listOf(
        Place("Shibuya Crossing", ShibuyaCrossing, folderId = "qa"),
        Place("Tokyo Tower", LatLng(35.65858, 139.74543), folderId = "qa"),
        Place("Warehouse — depot A", LatLng(35.62840, 139.77610), folderId = "delivery"),
        Place("Customer drop-off 12", LatLng(35.66410, 139.71230), folderId = "delivery"),
        Place("Times Square", LatLng(40.75800, -73.98550), folderId = "travel"),
        Place("Malé waterfront", LatLng(4.17520, 73.50920), folderId = "travel"),
    )

    val nearby = listOf(
        Place("Shibuya Scramble Crossing", LatLng(35.65950, 139.70050), "Dogenzaka, Shibuya", "20 m"),
        Place("Hachiko Statue", LatLng(35.65905, 139.70060), "Dogenzaka, Shibuya", "90 m"),
        Place("Shibuya Station", LatLng(35.65800, 139.70160), "JR · Tokyo Metro", "180 m"),
    )

    val recent = listOf(
        Place("Yoyogi Park", LatLng(35.67170, 139.69490), meta = "Today"),
        Place("Osaka Castle", LatLng(34.68730, 135.52620), meta = "Mon"),
        Place("Dropped pin", LatLng(51.50072, -0.12462), meta = "Sep 28"),
    )

    val history = recent + listOf(
        Place("Shibuya Crossing", ShibuyaCrossing, meta = "Sep 27"),
        Place("Tokyo Tower", LatLng(35.65858, 139.74543), meta = "Sep 25"),
    )

    /** Screen-space polyline of the Route mockup, converted to coordinates around Shibuya. */
    val routeShibuyaToYoyogi: List<LatLng> = mockupToLatLng(
        listOf(60.0 to 353.0, 147.0 to 342.0, 140.0 to 214.0, 300.0 to 199.0),
        anchorScreen = 144.0 to 282.0,
    )

    val tracks = listOf(
        Track("Shibuya Stn → Yoyogi Park", "3.4 km · walk · 2 days ago", routeShibuyaToYoyogi),
        Track("Morning run — Yoyogi loop", "5.1 km · GPX · 214 pts"),
        Track("Delivery round 3", "12.8 km · KML · 9 stops"),
    )

    val log = listOf(
        LogEntry("14:06", "play_route Shibuya → Yoyogi"),
        LogEntry("14:04", "search_place \"Yoyogi Park\""),
        LogEntry("14:03", "set_location lat=91.0, lng=139.7", ok = false, detail = "Latitude must be between -90 and 90"),
        LogEntry("14:02", "set_location 35.6595,139.7006"),
        LogEntry("14:01", "hello protocol=1"),
    )

    val appData = HauntAppData(
        folders = folders,
        favourites = favourites,
        history = history,
        tracks = tracks,
        recent = recent,
        nearby = { nearby },
        areaName = { "Shibuya, Tokyo" },
        adbControlEnabled = true,
        agentConnection = AgentConnection("haunt mcp", "via adb (USB) · since 14:01"),
        activityLog = log,
        locateMe = { ShibuyaCrossing },
    )

    /**
     * Converts mockup screen points (390×844 frame, ~9 m per px) into coordinates so the
     * drawn map in screenshots lines up with the mockups.
     */
    fun mockupToLatLng(points: List<Pair<Double, Double>>, anchorScreen: Pair<Double, Double>, anchor: LatLng = ShibuyaCrossing): List<LatLng> =
        points.map { (x, y) ->
            Geo.offset(anchor, eastM = (x - anchorScreen.first) * MetersPerDp, northM = -(y - anchorScreen.second) * MetersPerDp)
        }

    /** Scale of the drawn map (metres per dp). */
    const val MetersPerDp = 9.0
}
