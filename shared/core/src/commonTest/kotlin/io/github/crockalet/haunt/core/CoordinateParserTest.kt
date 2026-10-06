package io.github.crockalet.haunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinateParserTest {

    private fun ok(
        input: String,
        lat: Double,
        lng: Double,
        format: CoordinateFormat,
        reference: LatLng? = null,
        tolerance: Double = 1e-6,
    ): CoordinateParseResult.Success {
        val result = CoordinateParser.parse(input, reference)
        val success = assertIs<CoordinateParseResult.Success>(result, "for input: $input")
        assertEquals(lat, success.position.lat, tolerance, "lat for $input")
        assertEquals(lng, success.position.lng, tolerance, "lng for $input")
        assertEquals(format, success.format, "format for $input")
        return success
    }

    private fun bad(input: String, reference: LatLng? = null): String {
        val result = CoordinateParser.parse(input, reference)
        return assertIs<CoordinateParseResult.Failure>(result, "expected failure for: $input").reason
    }

    private val dmsLat = 35 + 39 / 60.0 + 31 / 3600.0
    private val dmsLng = 139 + 44 / 60.0 + 43 / 3600.0

    @Test
    fun decimal() {
        ok("35.6586, 139.7454", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("35.6586,139.7454", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("35.6586 139.7454", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("  -33.8568 ,  151.2153  ", -33.8568, 151.2153, CoordinateFormat.Decimal)
        ok("(40.7128, -74.0060)", 40.7128, -74.0060, CoordinateFormat.Decimal)
        ok("35.6586; 139.7454", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("+35.6586 +139.7454", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("0 0", 0.0, 0.0, CoordinateFormat.Decimal)
        ok("90, 180", 90.0, 180.0, CoordinateFormat.Decimal)
    }

    @Test
    fun decimalWithHemispheres() {
        ok("35.6586N, 139.7454E", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("35.6586 N 139.7454 E", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("N35.6586 E139.7454", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("33.8568° S, 151.2153° E", -33.8568, 151.2153, CoordinateFormat.Decimal)
        ok("40.7128 n, 74.0060 w", 40.7128, -74.0060, CoordinateFormat.Decimal)
        // Longitude first but labelled: swapped into place.
        ok("139.7454E 35.6586N", 35.6586, 139.7454, CoordinateFormat.Decimal)
        ok("W 74.0060, N 40.7128", 40.7128, -74.0060, CoordinateFormat.Decimal)
    }

    @Test
    fun dms() {
        ok("35°39'31\"N 139°44'43\"E", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("35°39′31″N 139°44′43″E", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("35° 39' 31\" N, 139° 44' 43\" E", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("N 35° 39' 31\" E 139° 44' 43\"", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("35°39'31.0\"N 139°44'43.0\"E", dmsLat, dmsLng, CoordinateFormat.DMS) // Google Maps style
        ok("35°39'31'' N 139°44'43'' E", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("35°39'31\" 139°44'43\"", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("33°51'24.5\"S 151°12'55.1\"E", -(33 + 51 / 60.0 + 24.5 / 3600), 151 + 12 / 60.0 + 55.1 / 3600, CoordinateFormat.DMS)
        ok("-33°51'24.5\", 151°12'55.1\"", -(33 + 51 / 60.0 + 24.5 / 3600), 151 + 12 / 60.0 + 55.1 / 3600, CoordinateFormat.DMS)
        ok("35 39 31 N 139 44 43 E", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("35 39 31 139 44 43", dmsLat, dmsLng, CoordinateFormat.DMS)
        ok("35º39'31\"N 139º44'43\"E", dmsLat, dmsLng, CoordinateFormat.DMS)
    }

    @Test
    fun ddm() {
        ok("35°39.517'N 139°44.717'E", 35 + 39.517 / 60, 139 + 44.717 / 60, CoordinateFormat.DDM)
        ok("N 35 39.517 E 139 44.717", 35 + 39.517 / 60, 139 + 44.717 / 60, CoordinateFormat.DDM)
        ok("35° 39.517', 139° 44.717'", 35 + 39.517 / 60, 139 + 44.717 / 60, CoordinateFormat.DDM)
    }

    @Test
    fun googleMapsUrls() {
        ok(
            "https://www.google.com/maps/@35.6585805,139.7454329,17z",
            35.6585805, 139.7454329, CoordinateFormat.GoogleMapsUrl,
        )
        ok("https://maps.google.com/?q=35.6586,139.7454", 35.6586, 139.7454, CoordinateFormat.GoogleMapsUrl)
        ok("https://maps.google.com/maps?q=35.6586%2C139.7454&z=15", 35.6586, 139.7454, CoordinateFormat.GoogleMapsUrl)
        ok("https://maps.google.com/maps?ll=35.6586,139.7454&z=15", 35.6586, 139.7454, CoordinateFormat.GoogleMapsUrl)
        ok(
            "https://www.google.com/maps/search/?api=1&query=35.6586,139.7454",
            35.6586, 139.7454, CoordinateFormat.GoogleMapsUrl,
        )
        val place = ok(
            "https://www.google.com/maps/place/Tokyo+Tower/@35.6585805,139.7428526,17z/data=!3m1!4b1!4m6!3m5!1s0x60188bbd9009ec09:0x481a93f0d2a409dd!8m2!3d35.6585805!4d139.7454329!16zL20vMDJ2NTk",
            35.6585805, 139.7454329, CoordinateFormat.GoogleMapsUrl,
        )
        assertEquals("Tokyo Tower", place.label)
        ok(
            "https://www.google.com/maps/place/Tokyo+Tower/@35.6585805,139.7428526,17z",
            35.6585805, 139.7428526, CoordinateFormat.GoogleMapsUrl,
        )
        ok(
            "https://www.google.com/maps/place/35%C2%B039'31.0%22N+139%C2%B044'43.0%22E/@35.6586,139.7428,17z",
            dmsLat, dmsLng, CoordinateFormat.GoogleMapsUrl,
        )
        ok("maps.google.com/?q=-33.8568,151.2153", -33.8568, 151.2153, CoordinateFormat.GoogleMapsUrl)
        assertTrue(bad("https://maps.app.goo.gl/AbCdEf123").contains("Short"))
        bad("https://www.google.com/maps/place/Tokyo+Tower")
    }

    @Test
    fun geoUris() {
        ok("geo:35.6586,139.7454", 35.6586, 139.7454, CoordinateFormat.GeoUri)
        val withAlt = ok("geo:35.6586,139.7454,40;u=10", 35.6586, 139.7454, CoordinateFormat.GeoUri)
        assertEquals(40.0, withAlt.altitude)
        ok("GEO:-33.8568,151.2153?z=15", -33.8568, 151.2153, CoordinateFormat.GeoUri)
        val labelled = ok("geo:0,0?q=35.6586,139.7454(Tokyo Tower)", 35.6586, 139.7454, CoordinateFormat.GeoUri)
        assertEquals("Tokyo Tower", labelled.label)
        ok("geo:0,0?q=35.6586%2C139.7454%28Tokyo%20Tower%29", 35.6586, 139.7454, CoordinateFormat.GeoUri)
        bad("geo:0,0?q=Tokyo+Tower")
        bad("geo:abc,def")
        bad("geo:95,0")
    }

    @Test
    fun plusCodes() {
        // Google Zürich: 8FVC9G8F+6X → centre of the 1/8000° cell.
        ok("8FVC9G8F+6X", 47.3655625, 8.5249375, CoordinateFormat.PlusCode, tolerance = 1e-9)
        ok("8fvc9g8f+6x", 47.3655625, 8.5249375, CoordinateFormat.PlusCode, tolerance = 1e-9)
        ok("8FVC9G8F+6XQ", 47.36557, 8.52494, CoordinateFormat.PlusCode, tolerance = 1e-4)
        ok("8FVC0000+", 47.5, 8.5, CoordinateFormat.PlusCode, tolerance = 1e-9)
        // Short code with a reference nearby.
        val zurich = LatLng(47.37, 8.54)
        val short = ok("9G8F+6X Zürich", 47.3655625, 8.5249375, CoordinateFormat.PlusCode, reference = zurich, tolerance = 1e-9)
        assertEquals("Zürich", short.label)
        ok("9G8F+6X", 47.3655625, 8.5249375, CoordinateFormat.PlusCode, reference = zurich, tolerance = 1e-9)
        assertTrue(bad("9G8F+6X Zürich").contains("reference"))
        bad("8FVC9G8F+6")   // single char after separator
        bad("8FVC9G8+6X")   // separator at odd position
    }

    @Test
    fun invalidInput() {
        bad("")
        bad("   ")
        bad("hello world")
        bad("35.6586")
        bad("35.6586, 139.7454, 12.0")
        bad("91, 0")
        bad("0, 181")
        bad("139.7454, 35.6586, x")
        bad("35°70'00\"N 139°44'43\"E") // minutes >= 60
        bad("35°39'61\"N 139°44'43\"E") // seconds >= 60
        bad("35.5°39'N 139°44'E")       // fractional degrees with minutes
        bad("35.1N 36.2S")              // two latitudes
        bad("-35.1S, 139.0E")           // sign + hemisphere
        bad("35.1, ")
        bad(", 35.1, 139.2")
        bad("1.2.3, 4")
        assertNull(CoordinateParser.parseOrNull("nope"))
        assertEquals(LatLng(1.0, 2.0), CoordinateParser.parseOrNull("1, 2"))
    }

    @Test
    fun formatLabels() {
        assertEquals("DMS", CoordinateFormat.DMS.label)
        assertEquals("Plus code", CoordinateFormat.PlusCode.label)
    }
}
