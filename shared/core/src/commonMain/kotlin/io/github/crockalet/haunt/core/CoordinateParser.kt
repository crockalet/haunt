package io.github.crockalet.haunt.core

import kotlin.math.abs
import kotlin.math.floor

/** How a pasted coordinate was written. [label] is suitable for a UI badge ("DMS detected"). */
enum class CoordinateFormat(val label: String) {
    /** `35.6586, 139.7454`, `35.6586 139.7454`, `35.6586N 139.7454E`, `35.6586° N`. */
    Decimal("Decimal"),

    /** Degrees, minutes, seconds: `35°39'31"N 139°44'43"E`. */
    DMS("DMS"),

    /** Degrees and decimal minutes: `35°39.517'N 139°44.717'E`. */
    DDM("DDM"),

    /** A Google Maps URL (`@lat,lng`, `?q=lat,lng`, `ll=`, `!3d…!4d…`). */
    GoogleMapsUrl("Google Maps link"),

    /** RFC 5870 `geo:lat,lng` URI. */
    GeoUri("geo: URI"),

    /** Open Location Code / plus code (full, or short + reference). */
    PlusCode("Plus code"),
}

sealed interface CoordinateParseResult {
    data class Success(
        val position: LatLng,
        val format: CoordinateFormat,
        /** Altitude when the input carried one (geo: URIs). */
        val altitude: Double? = null,
        /** Place label when the input carried one (`geo:0,0?q=lat,lng(Label)`, Maps `/place/Name/`). */
        val label: String? = null,
    ) : CoordinateParseResult

    data class Failure(val reason: String) : CoordinateParseResult
}

/**
 * Parses the many ways people paste coordinates. Pure and side-effect free; never throws.
 *
 * Short plus codes (`9G8F+6X Zürich`) need a [reference] location (e.g. the map centre or the
 * geocoded locality); without one they fail with a message saying so.
 */
object CoordinateParser {

    fun parse(input: String, reference: LatLng? = null): CoordinateParseResult {
        val text = input.trim()
        if (text.isEmpty()) return fail("Empty input")
        val lower = text.lowercase()
        return when {
            lower.startsWith("geo:") -> parseGeoUri(text)
            isUrl(lower) -> parseMapsUrl(text)
            else -> parsePlusCode(text, reference) ?: parseCoordinatePair(text)
        }
    }

    /** Convenience: the position, or null if [input] couldn't be parsed. */
    fun parseOrNull(input: String, reference: LatLng? = null): LatLng? =
        (parse(input, reference) as? CoordinateParseResult.Success)?.position

    // --- geo: URI ------------------------------------------------------------------------------

    private fun parseGeoUri(text: String): CoordinateParseResult {
        val body = text.substring(4)
        val queryStart = body.indexOf('?')
        val path = if (queryStart >= 0) body.substring(0, queryStart) else body
        val query = if (queryStart >= 0) parseQuery(body.substring(queryStart + 1)) else emptyMap()
        val coords = percentDecode(path.substringBefore(';')).split(',').map { it.trim() }
        if (coords.size !in 2..3) return fail("geo: URI needs latitude,longitude")
        val lat = coords[0].toDoubleOrNull()
        val lng = coords[1].toDoubleOrNull()
        val alt = coords.getOrNull(2)?.let { it.toDoubleOrNull() ?: return fail("Invalid altitude in geo: URI") }
        if (lat == null || lng == null) return fail("Invalid numbers in geo: URI")

        // geo:0,0?q=lat,lng(Label) — Android's way of dropping a labelled pin.
        val q = query["q"]
        if (q != null) {
            val (qText, label) = splitLabel(q)
            val fromQ = parseCoordinatePair(qText)
            if (fromQ is CoordinateParseResult.Success) {
                return fromQ.copy(format = CoordinateFormat.GeoUri, label = label)
            }
        }
        if (lat == 0.0 && lng == 0.0 && q != null) return fail("geo: URI query is not a coordinate (needs geocoding)")
        return validated(lat, lng, CoordinateFormat.GeoUri, altitude = alt)
    }

    // --- Google Maps URLs ----------------------------------------------------------------------

    private fun isUrl(lower: String): Boolean =
        lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("maps.google.") || lower.startsWith("www.google.") ||
            lower.startsWith("google.") || lower.startsWith("maps.app.goo.gl") || lower.startsWith("goo.gl/")

    private val coordinateParams = listOf("q", "query", "ll", "sll", "center", "destination", "daddr", "viewpoint")
    private val dataPin = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val atViewport = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val placeSegment = Regex("""/(?:place|search|dir)/([^/?#@]+)""")

    private fun parseMapsUrl(text: String): CoordinateParseResult {
        val lower = text.lowercase()
        val withoutFragment = text.substringBefore('#')
        val query = withoutFragment.substringAfter('?', "")
        val path = withoutFragment.substringBefore('?')
        val params = parseQuery(query)

        for (name in coordinateParams) {
            val value = params[name] ?: continue
            val (coordText, label) = splitLabel(value.removePrefix("loc:").trim())
            val result = parseCoordinatePair(coordText)
            if (result is CoordinateParseResult.Success) {
                return result.copy(format = CoordinateFormat.GoogleMapsUrl, label = label)
            }
        }
        val placeName = placeSegment.find(path)?.groupValues?.get(1)?.let { percentDecode(it.replace('+', ' ')) }
        dataPin.find(path)?.let { m ->
            return validated(
                m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), CoordinateFormat.GoogleMapsUrl,
                label = placeName?.takeIf { parseCoordinatePair(it) !is CoordinateParseResult.Success },
            )
        }
        if (placeName != null) {
            val result = parseCoordinatePair(placeName)
            if (result is CoordinateParseResult.Success) return result.copy(format = CoordinateFormat.GoogleMapsUrl)
        }
        atViewport.find(path)?.let { m ->
            return validated(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), CoordinateFormat.GoogleMapsUrl)
        }
        return if ("goo.gl" in lower) {
            fail("Short Maps links don't contain coordinates; open the link and copy the full URL")
        } else {
            fail("No coordinates found in the link")
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&', ';')) {
            if (part.isEmpty()) continue
            val key = percentDecode(part.substringBefore('=')).lowercase()
            val value = percentDecode(part.substringAfter('=', "").replace('+', ' '))
            if (key !in out) out[key] = value
        }
        return out
    }

    /** `35.1,139.2(Tokyo Tower)` → (`35.1,139.2`, `Tokyo Tower`). */
    private fun splitLabel(value: String): Pair<String, String?> {
        val open = value.indexOf('(')
        if (open < 0 || !value.trimEnd().endsWith(")")) return value to null
        return value.substring(0, open).trim() to value.substring(open + 1, value.trimEnd().length - 1).trim()
    }

    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.add(hex.toByte())
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    // --- Plus codes ----------------------------------------------------------------------------

    private val plusCodePattern = Regex(
        """^([23456789CFGHJMPQRVWX0]{2,8}\+[23456789CFGHJMPQRVWX]*)(?:[\s,]+(.*))?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parsePlusCode(text: String, reference: LatLng?): CoordinateParseResult? {
        val m = plusCodePattern.matchEntire(text) ?: return null
        val code = m.groupValues[1].uppercase()
        val locality = m.groupValues[2].trim().ifEmpty { null }
        if (!OpenLocationCode.isValid(code)) return fail("Invalid plus code")
        val full = when {
            OpenLocationCode.isFull(code) -> code
            OpenLocationCode.isShort(code) -> {
                if (reference == null) {
                    val hint = if (locality != null) " (look up \"$locality\" first)" else ""
                    return fail("Short plus code needs a reference location$hint")
                }
                OpenLocationCode.recoverNearest(code, reference)
            }
            else -> return fail("Invalid plus code")
        }
        val center = OpenLocationCode.decode(full).center
        return validated(center.lat, center.lng, CoordinateFormat.PlusCode, label = locality)
    }

    // --- Plain coordinate pairs ----------------------------------------------------------------

    private enum class Unit { Deg, Min, Sec }

    private sealed interface Token {
        data class Num(val value: Double, val negative: Boolean, val explicitSign: Boolean) : Token
        data class Mark(val unit: Unit) : Token
        data class Hemi(val c: Char) : Token
        data object Sep : Token
    }

    private class Component {
        val numbers = mutableListOf<Token.Num>()
        val units = mutableListOf<Unit?>()
        var hemi: Char? = null
        val isEmpty get() = numbers.isEmpty() && hemi == null
        val lastUnit get() = units.lastOrNull()
    }

    private class ParseException(message: String) : Exception(message)

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() || c in "()[]" -> i++
                c.isDigit() || c == '.' ||
                    ((c == '-' || c == '+' || c == '−') && i + 1 < text.length && (text[i + 1].isDigit() || text[i + 1] == '.')) -> {
                    val start = i
                    if (!c.isDigit() && c != '.') i++
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    val raw = text.substring(start, i)
                    val sign = raw[0] == '-' || raw[0] == '−'
                    val explicit = sign || raw[0] == '+'
                    val digits = if (explicit) raw.substring(1) else raw
                    val value = digits.toDoubleOrNull() ?: throw ParseException("Invalid number \"$raw\"")
                    tokens += Token.Num(value, sign, explicit)
                }
                c in "°º˚" -> { tokens += Token.Mark(Unit.Deg); i++ }
                c == '\'' && i + 1 < text.length && text[i + 1] == '\'' -> { tokens += Token.Mark(Unit.Sec); i += 2 }
                c in "'′’‘`´" -> { tokens += Token.Mark(Unit.Min); i++ }
                c in "\"″”“" -> { tokens += Token.Mark(Unit.Sec); i++ }
                c in ",;/|" -> { tokens += Token.Sep; i++ }
                c.uppercaseChar() in "NSEW" && (i + 1 >= text.length || !text[i + 1].isLetter()) &&
                    (i == 0 || !text[i - 1].isLetter()) -> { tokens += Token.Hemi(c.uppercaseChar()); i++ }
                else -> throw ParseException("Unexpected \"$c\"")
            }
        }
        return tokens
    }

    private fun group(tokens: List<Token>): List<Component> {
        val comps = mutableListOf<Component>()
        var cur = Component()
        fun close() {
            if (!cur.isEmpty) comps += cur
            cur = Component()
        }
        for ((index, t) in tokens.withIndex()) {
            when (t) {
                Token.Sep -> {
                    // A separator right after a suffix hemisphere ("35N, 139E") is fine.
                    val afterHemi = tokens.getOrNull(index - 1) is Token.Hemi && comps.isNotEmpty()
                    if (cur.isEmpty && !afterHemi) throw ParseException("Unexpected separator")
                    close()
                }
                is Token.Hemi -> when {
                    cur.numbers.isEmpty() -> {
                        if (cur.hemi != null) throw ParseException("Two hemisphere letters in a row")
                        cur.hemi = t.c
                    }
                    cur.hemi == null -> { cur.hemi = t.c; close() }
                    else -> { close(); cur.hemi = t.c }
                }
                is Token.Num -> {
                    val next = tokens.getOrNull(index + 1)
                    val nextIsDeg = next is Token.Mark && next.unit == Unit.Deg
                    if (cur.numbers.isNotEmpty() && (cur.lastUnit == Unit.Sec || (cur.lastUnit != null && nextIsDeg))) close()
                    cur.numbers += t
                    cur.units += null
                }
                is Token.Mark -> {
                    if (cur.numbers.isEmpty() || cur.units.last() != null) throw ParseException("Misplaced unit symbol")
                    cur.units[cur.units.lastIndex] = t.unit
                }
            }
        }
        close()
        // "35 39 31 139 44 43" / "35.6 139.7": no separators or units — split evenly.
        if (comps.size == 1) {
            val only = comps[0]
            if (only.hemi == null && only.units.all { it == null } && only.numbers.size in setOf(2, 4, 6)) {
                val half = only.numbers.size / 2
                return listOf(
                    Component().apply { numbers += only.numbers.take(half); repeat(half) { units += null } },
                    Component().apply { numbers += only.numbers.drop(half); repeat(half) { units += null } },
                )
            }
        }
        return comps
    }

    private class Value(val degrees: Double, val hemi: Char?, val hasMin: Boolean, val hasSec: Boolean)

    private fun evaluate(comp: Component): Value {
        if (comp.numbers.isEmpty()) throw ParseException("Missing number")
        val slots = arrayOfNulls<Token.Num>(3)
        var nextSlot = 0
        for ((n, unit) in comp.numbers.zip(comp.units)) {
            val slot = unit?.ordinal ?: nextSlot
            if (slot < nextSlot || slot > 2) throw ParseException("Too many numbers in one coordinate")
            slots[slot] = n
            nextSlot = slot + 1
        }
        val deg = slots[0] ?: throw ParseException("Missing degrees")
        val min = slots[1]
        val sec = slots[2]
        if (min?.explicitSign == true || sec?.explicitSign == true) throw ParseException("Minutes and seconds can't be signed")
        if (min != null && deg.value != floor(deg.value)) throw ParseException("Degrees must be whole when minutes are given")
        if (sec != null && min != null && min.value != floor(min.value)) throw ParseException("Minutes must be whole when seconds are given")
        if (sec != null && min == null) throw ParseException("Seconds without minutes")
        if (min != null && min.value >= 60) throw ParseException("Minutes must be below 60")
        if (sec != null && sec.value >= 60) throw ParseException("Seconds must be below 60")
        if (deg.negative && comp.hemi != null) throw ParseException("Use either a sign or N/S/E/W, not both")
        var value = deg.value + (min?.value ?: 0.0) / 60.0 + (sec?.value ?: 0.0) / 3600.0
        if (deg.negative || comp.hemi == 'S' || comp.hemi == 'W') value = -value
        return Value(value, comp.hemi, min != null, sec != null)
    }

    private fun parseCoordinatePair(text: String): CoordinateParseResult = try {
        val comps = group(tokenize(text.trim()))
        if (comps.size != 2) throw ParseException("Expected latitude and longitude, found ${comps.size} value(s)")
        var a = evaluate(comps[0])
        var b = evaluate(comps[1])
        val aIsLat = a.hemi == 'N' || a.hemi == 'S'
        val aIsLng = a.hemi == 'E' || a.hemi == 'W'
        val bIsLat = b.hemi == 'N' || b.hemi == 'S'
        val bIsLng = b.hemi == 'E' || b.hemi == 'W'
        if ((aIsLat && bIsLat) || (aIsLng && bIsLng)) {
            throw ParseException("Both values have the same hemisphere axis")
        }
        if (aIsLng || bIsLat) {
            val t = a
            a = b
            b = t
        }
        val format = when {
            a.hasSec || b.hasSec -> CoordinateFormat.DMS
            a.hasMin || b.hasMin -> CoordinateFormat.DDM
            else -> CoordinateFormat.Decimal
        }
        validated(a.degrees, b.degrees, format)
    } catch (e: ParseException) {
        fail(e.message ?: "Unrecognised coordinate")
    }

    // --- shared --------------------------------------------------------------------------------

    private fun validated(
        lat: Double,
        lng: Double,
        format: CoordinateFormat,
        altitude: Double? = null,
        label: String? = null,
    ): CoordinateParseResult = when {
        !lat.isFinite() || abs(lat) > 90.0 -> fail("Latitude must be between -90 and 90")
        !lng.isFinite() || abs(lng) > 180.0 -> fail("Longitude must be between -180 and 180")
        else -> CoordinateParseResult.Success(LatLng(lat + 0.0, lng + 0.0), format, altitude, label)
    }

    private fun fail(reason: String) = CoordinateParseResult.Failure(reason)
}
