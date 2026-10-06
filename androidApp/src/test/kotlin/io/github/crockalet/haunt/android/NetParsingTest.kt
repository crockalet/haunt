package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.net.HttpClient
import io.github.crockalet.haunt.android.net.OsrmParser
import io.github.crockalet.haunt.android.net.OsrmRouter
import io.github.crockalet.haunt.android.net.PhotonGeocoder
import io.github.crockalet.haunt.android.net.PhotonParser
import io.github.crockalet.haunt.android.net.RoutingException
import io.github.crockalet.haunt.android.net.hauntUserAgent
import io.github.crockalet.haunt.core.LatLng
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NetParsingTest {
    private val photon = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[139.7454329,35.6585805]},
           "properties":{"osm_key":"tourism","osm_value":"attraction","name":"Tokyo Tower","street":"Shiba-koen",
                         "housenumber":"4-2-8","city":"Minato","state":"Tokyo","country":"Japan","postcode":"105-0011"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[2.2945,48.8584]},
           "properties":{"street":"Avenue Anatole France","housenumber":"5","city":"Paris","country":"France","type":"house"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[999,0]},"properties":{"name":"Broken"}},
          {"type":"Feature","properties":{"name":"No geometry"}}
        ]}
    """.trimIndent()

    @Test
    fun parsesPhotonFeatures() {
        val places = PhotonParser.parse(photon)
        assertEquals(2, places.size)
        val tower = places[0]
        assertEquals("Tokyo Tower", tower.name)
        assertEquals(LatLng(35.6585805, 139.7454329), tower.position)
        assertEquals("Shiba-koen 4-2-8, Minato, Tokyo, Japan", tower.address)
        assertEquals("attraction", tower.kind)
        val house = places[1]
        assertEquals("Avenue Anatole France 5", house.name)
        assertEquals("Paris, France", house.address)
        assertEquals("house", house.kind)
    }

    @Test
    fun photonRejectsNonCollections() {
        assertFailsWith<IllegalArgumentException> { PhotonParser.parse("""{"message":"error"}""") }
        assertFailsWith<IllegalArgumentException> { PhotonParser.parse("<html>") }
        assertEquals(emptyList(), PhotonParser.parse("""{"features":[]}"""))
    }

    @Test
    fun photonUrl() {
        assertEquals(
            "https://photon.komoot.io/api/?q=Tokyo%20Tower%20%26%20more&limit=3&lat=35.000000&lon=139.500000",
            PhotonGeocoder.buildUrl("https://photon.komoot.io/", "Tokyo Tower & more", 3, LatLng(35.0, 139.5)),
        )
        assertEquals("https://x.org/api/?q=a&limit=1", PhotonGeocoder.buildUrl("https://x.org/api", "a", 1, null))
    }

    @Test
    fun photonReverseUrl() {
        assertEquals(
            "https://photon.komoot.io/reverse?lat=35.000000&lon=139.500000&limit=5",
            PhotonGeocoder.buildReverseUrl("https://photon.komoot.io/", LatLng(35.0, 139.5), 5),
        )
        assertEquals(
            "https://x.org/reverse?lat=1.000000&lon=2.000000&limit=50",
            PhotonGeocoder.buildReverseUrl("https://x.org/api/", LatLng(1.0, 2.0), 99),
        )
    }

    @Test
    fun geocoderUsesCurrentBaseUrl() = runTest {
        val urls = mutableListOf<String>()
        var base = "https://a.example"
        val geocoder = PhotonGeocoder(HttpClient { url -> urls += url; photon }) { base }
        assertEquals("Tokyo Tower", geocoder.search("tower", 5, null).first().name)
        base = "https://b.example"
        geocoder.search("tower", 5, null)
        assertEquals(listOf("https://a.example/api/?q=tower&limit=5", "https://b.example/api/?q=tower&limit=5"), urls)
    }

    @Test
    fun parsesOsrmRoute() {
        val body = """
            {"code":"Ok","routes":[{"distance":1234.5,"duration":99.9,
              "geometry":{"type":"LineString","coordinates":[[139.70,35.65],[139.71,35.66],[139.72,35.67]]}}],
             "waypoints":[]}
        """.trimIndent()
        val path = OsrmParser.parse(body)
        assertEquals(listOf(LatLng(35.65, 139.70), LatLng(35.66, 139.71), LatLng(35.67, 139.72)), path.points)
        assertEquals(1234.5, path.distanceMeters)
        assertEquals(99.9, path.durationSeconds)
    }

    @Test
    fun osrmErrors() {
        val e = assertFailsWith<RoutingException> { OsrmParser.parse("""{"code":"NoRoute","message":"Impossible route"}""") }
        assertEquals("Routing failed: NoRoute (Impossible route)", e.message)
        assertFailsWith<RoutingException> { OsrmParser.parse("""{"code":"Ok","routes":[]}""") }
        assertFailsWith<RoutingException> { OsrmParser.parse("""{"code":"Ok","routes":[{"geometry":"encodedpolyline"}]}""") }
        assertFailsWith<RoutingException> { OsrmParser.parse("not json") }
    }

    @Test
    fun osrmUrl() {
        assertEquals(
            "https://router.project-osrm.org/route/v1/driving/139.700000,35.650000;139.720000,35.670000?overview=full&geometries=geojson",
            OsrmRouter.buildUrl("https://router.project-osrm.org/", "driving", listOf(LatLng(35.65, 139.70), LatLng(35.67, 139.72))),
        )
    }

    @Test
    fun osrmMissingDistanceIsComputed() {
        val path = OsrmParser.parse("""{"code":"Ok","routes":[{"geometry":{"coordinates":[[0,0],[0,0.001]]}}]}""")
        assertEquals(111.19, path.distanceMeters, 0.1)
        assertNull(path.durationSeconds)
    }

    @Test
    fun userAgent() {
        assertEquals("Haunt/0.1.0 (+https://github.com/crockalet/haunt)", hauntUserAgent("0.1.0"))
    }
}
