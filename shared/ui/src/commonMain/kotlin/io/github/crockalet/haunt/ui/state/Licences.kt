package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable

/** A licence from the generated library list; [text] is null when none was bundled. */
@Immutable
data class OssLicence(val id: String, val name: String, val url: String? = null, val text: String? = null)

/** A third-party library shipped in the app, with the ids of the [OssLicence]s it's offered under. */
@Immutable
data class OssLibrary(
    val id: String,
    val name: String,
    val version: String? = null,
    val website: String? = null,
    val licenceIds: List<String> = emptyList(),
)

/** A notice file bundled with the app (e.g. MapLibre Native's third-party notices), read on demand. */
@Immutable
data class OssNotice(val title: String, val path: String, val subtitle: String? = null)

/** Libraries Haunt ships under the same licence. */
@Immutable
data class LicenceGroup(val licence: OssLicence, val libraries: List<OssLibrary>)

/** Everything Settings → Data & licences lists under open-source licences. */
@Immutable
data class OpenSourceInfo(
    val libraries: List<OssLibrary> = emptyList(),
    val licences: Map<String, OssLicence> = emptyMap(),
    val notices: List<OssNotice> = emptyList(),
) {
    /**
     * Libraries grouped by the licence they're shipped under, largest group first. A library offered
     * under several licences (e.g. "GPL-2.0 or Apache-2.0") counts under the first one whose text is
     * bundled, so every group can show its licence text.
     */
    val groups: List<LicenceGroup> by lazy {
        libraries
            .groupBy { lib ->
                lib.licenceIds.firstOrNull { licences[it]?.text != null } ?: lib.licenceIds.firstOrNull() ?: UnknownLicence
            }
            .map { (id, libs) ->
                LicenceGroup(
                    licences[id] ?: OssLicence(id, if (id == UnknownLicence) "Licence not declared" else id),
                    libs.sortedBy { it.name.lowercase() },
                )
            }
            .sortedWith(compareByDescending<LicenceGroup> { it.libraries.size }.thenBy { it.licence.name })
    }

    private companion object {
        const val UnknownLicence = "unknown"
    }
}

/** A licence or notice opened full-screen: [libraries] that use it ("name version") and its text. */
@Immutable
data class LicenceDoc(
    val title: String,
    val subtitle: String? = null,
    val libraries: List<String> = emptyList(),
    val url: String? = null,
    val text: suspend () -> String,
)

/** A credit row on the Data & licences screen. */
@Immutable
data class Credit(val title: String, val subtitle: String, val url: String)

/** A titled block of [Credit]s, with an optional [note] under it. */
@Immutable
data class CreditSection(val title: String, val credits: List<Credit>, val note: String? = null)

/** Credits for the data and services Haunt uses by default (map, routing, search). */
object DataCredits {
    const val SOURCE_URL = "https://github.com/crockalet/haunt"
    const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    val Map = CreditSection(
        "Map data",
        listOf(
            Credit("© OpenStreetMap contributors", "Map data · Open Database License (ODbL)", "https://www.openstreetmap.org/copyright"),
            Credit("Report a map error", "Fix or add something on OpenStreetMap", "https://www.openstreetmap.org/fixthemap"),
            Credit("OpenMapTiles", "Vector tile schema · CC-BY 4.0", "https://openmaptiles.org"),
            Credit("OpenFreeMap", "Map tiles and fonts", "https://openfreemap.org"),
        ),
    )

    val Routing = CreditSection(
        "Routing",
        listOf(
            Credit("OSRM", "Open Source Routing Machine · driving: router.project-osrm.org", "https://project-osrm.org"),
            Credit("Servers by FOSSGIS e.V.", "Walk and cycle routes: routing.openstreetmap.de", "https://routing.openstreetmap.de"),
            Credit("FOSSGIS e.V.", "Runs the OpenStreetMap routing servers", "https://www.fossgis.de"),
        ),
        note = "Routes are calculated from OpenStreetMap data. Servers you set in Settings may have their own terms.",
    )

    val Search = CreditSection(
        "Place search",
        listOf(Credit("Photon by komoot", "Search and nearby places · data from OpenStreetMap", "https://photon.komoot.io")),
    )

    val all: List<CreditSection> = listOf(Map, Routing, Search)
}
