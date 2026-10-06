package io.github.crockalet.haunt.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Thrown when a file is neither GPX nor KML. */
class TrackFormatException(message: String) : IllegalArgumentException(message)

/**
 * GPX 1.1 / KML import and GPX export.
 *
 * Import produces one [Route] per geometry:
 * - GPX: each `<trk>` (all `<trkseg>`s concatenated), then each `<rte>`, then — if present — all
 *   `<wpt>`s as one route. `<time>` → [Route.timestampsMillis] and `<ele>` → [Route.altitudes],
 *   each only when every point has one (timestamps also must be non-decreasing).
 * - KML: each `<LineString>`, `<LinearRing>` and `<gx:Track>` (with `<when>`/`<gx:coord>`), named
 *   after its Placemark; all `<Point>` placemarks combined into one route, in document order.
 *
 * Points with missing or out-of-range coordinates are skipped. A recognised document without any
 * geometry yields an empty list.
 */
object TrackFormats {

    /** Detects GPX or KML from the root element. */
    fun parse(text: String): List<Route> {
        val root = documentElement(text)
        return when (root?.name?.lowercase()) {
            "gpx" -> parseGpx(root)
            "kml" -> parseKml(root)
            else -> throw TrackFormatException("Not a GPX or KML document")
        }
    }

    fun parseGpx(text: String): List<Route> {
        val root = documentElement(text)
        if (root?.name != "gpx") throw TrackFormatException("Not a GPX document")
        return parseGpx(root)
    }

    fun parseKml(text: String): List<Route> {
        val root = documentElement(text)
        if (root?.name != "kml") throw TrackFormatException("Not a KML document")
        return parseKml(root)
    }

    private fun documentElement(text: String): XmlElement? = Xml.parse(text).elements.firstOrNull()

    // --- GPX -----------------------------------------------------------------------------------

    private class Sample(val position: LatLng, val altitude: Double?, val time: Long?)

    private fun parseGpx(gpx: XmlElement): List<Route> {
        val docName = gpx.child("metadata")?.childText("name") ?: gpx.childText("name")
        val routes = mutableListOf<Route>()
        for (trk in gpx.children("trk")) {
            val samples = trk.children("trkseg").flatMap { seg -> seg.children("trkpt").mapNotNull(::gpxSample) }
            buildRoute(samples, trk.childText("name"))?.let(routes::add)
        }
        for (rte in gpx.children("rte")) {
            buildRoute(rte.children("rtept").mapNotNull(::gpxSample), rte.childText("name"))?.let(routes::add)
        }
        val waypoints = gpx.children("wpt").mapNotNull(::gpxSample)
        buildRoute(waypoints, docName ?: "Waypoints")?.let(routes::add)
        return routes
    }

    private fun gpxSample(pt: XmlElement): Sample? {
        val lat = pt.attributes["lat"]?.trim()?.toDoubleOrNull() ?: return null
        val lng = pt.attributes["lon"]?.trim()?.toDoubleOrNull() ?: return null
        val position = LatLng(lat, lng)
        if (!Geo.isValid(position)) return null
        return Sample(
            position = position,
            altitude = pt.childText("ele")?.toDoubleOrNull()?.takeIf { it.isFinite() },
            time = pt.childText("time")?.let(Iso8601::parse),
        )
    }

    private fun buildRoute(samples: List<Sample>, name: String?): Route? {
        if (samples.isEmpty()) return null
        val times = samples.map { it.time }
        val timestamps = if (times.all { it != null }) {
            @Suppress("UNCHECKED_CAST")
            (times as List<Long>).takeIf { ts -> ts.zipWithNext().all { (a, b) -> b >= a } }
        } else {
            null
        }
        val alts = samples.map { it.altitude }
        @Suppress("UNCHECKED_CAST")
        val altitudes = if (alts.all { it != null }) alts as List<Double> else null
        return Route(
            points = samples.map { it.position },
            timestampsMillis = timestamps,
            name = name,
            altitudes = altitudes,
        )
    }

    // --- KML -----------------------------------------------------------------------------------

    private fun parseKml(kml: XmlElement): List<Route> {
        val document = kml.child("Document") ?: kml.child("Folder")
        val docName = document?.childText("name")
        val routes = mutableListOf<Route>()
        val points = mutableListOf<Pair<Sample, String?>>()

        fun walk(element: XmlElement, placemarkName: String?) {
            for (child in element.elements) {
                when (child.name) {
                    "Placemark" -> walk(child, child.childText("name"))
                    "LineString", "LinearRing" -> {
                        val samples = parseKmlCoordinates(child.childText("coordinates").orEmpty())
                        buildRoute(samples, placemarkName)?.let(routes::add)
                    }
                    "Track" -> parseGxTrack(child, placemarkName)?.let(routes::add)
                    "Point" -> parseKmlCoordinates(child.childText("coordinates").orEmpty())
                        .firstOrNull()?.let { points += it to placemarkName }
                    else -> walk(child, placemarkName)
                }
            }
        }
        walk(kml, null)

        if (points.isNotEmpty()) {
            val name = if (points.size == 1) points[0].second ?: docName else docName ?: "Placemarks"
            buildRoute(points.map { it.first }, name)?.let(routes::add)
        }
        return routes
    }

    private val commaSpaces = Regex("""\s*,\s*""")
    private val whitespace = Regex("""\s+""")

    private fun parseKmlCoordinates(text: String): List<Sample> =
        text.trim().replace(commaSpaces, ",").split(whitespace).mapNotNull { tuple ->
            val parts = tuple.split(',')
            if (parts.size < 2) return@mapNotNull null
            val lng = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val position = LatLng(lat, lng)
            if (!Geo.isValid(position)) return@mapNotNull null
            Sample(position, parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() }, null)
        }

    /** `<gx:Track>`: parallel `<when>` and `<gx:coord>` ("lon lat alt") lists. */
    private fun parseGxTrack(track: XmlElement, name: String?): Route? {
        val whens = track.children("when").map { Iso8601.parse(it.text) }
        val coords = track.children("coord")
        val samples = coords.mapIndexedNotNull { index, coord ->
            val parts = coord.text.trim().split(whitespace)
            if (parts.size < 2) return@mapIndexedNotNull null
            val lng = parts[0].toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
            val position = LatLng(lat, lng)
            if (!Geo.isValid(position)) return@mapIndexedNotNull null
            Sample(
                position = position,
                altitude = parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() },
                time = if (whens.size == coords.size) whens[index] else null,
            )
        }
        return buildRoute(samples, name)
    }

    // --- GPX export ----------------------------------------------------------------------------

    /**
     * Serialises [route] as a GPX 1.1 track (`<trk>`/`<trkseg>`/`<trkpt>`), including `<ele>` and
     * `<time>` when the route has altitudes/timestamps for every point.
     */
    fun toGpx(route: Route, creator: String = "Haunt"): String = buildString {
        val altitudes = route.altitudes?.takeIf { it.size == route.points.size }
        val times = route.timestampsMillis?.takeIf { it.size == route.points.size }
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="""").append(Xml.escape(creator))
        append("""" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        route.name?.let { append("  <metadata><name>").append(Xml.escape(it)).append("</name></metadata>\n") }
        append("  <trk>\n")
        route.name?.let { append("    <name>").append(Xml.escape(it)).append("</name>\n") }
        append("    <trkseg>\n")
        route.points.forEachIndexed { i, p ->
            append("""      <trkpt lat="""").append(decimal(p.lat, 7))
            append("""" lon="""").append(decimal(p.lng, 7)).append('"')
            val ele = altitudes?.get(i)
            val time = times?.get(i)
            if (ele == null && time == null) {
                append("/>\n")
            } else {
                append('>')
                if (ele != null) append("<ele>").append(decimal(ele, 2)).append("</ele>")
                if (time != null) append("<time>").append(Iso8601.format(time)).append("</time>")
                append("</trkpt>\n")
            }
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /** Plain decimal (never scientific notation) with up to [fractionDigits] digits, trailing zeros trimmed. */
    internal fun decimal(value: Double, fractionDigits: Int): String {
        var scale = 1L
        repeat(fractionDigits) { scale *= 10 }
        val scaled = (abs(value) * scale).roundToLong()
        val negative = value < 0 && scaled != 0L
        val whole = scaled / scale
        val fraction = (scaled % scale).toString().padStart(fractionDigits, '0').trimEnd('0')
        return buildString {
            if (negative) append('-')
            append(whole)
            if (fraction.isNotEmpty()) append('.').append(fraction)
        }
    }
}
