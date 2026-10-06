package io.github.crockalet.haunt.ui.state

import io.github.crockalet.haunt.core.LatLng
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Formatting for UI strings (no platform `String.format` in common code). */
object Format {
    /** Fixed-point with [decimals] digits, e.g. `fixed(35.659521, 5) == "35.65952"`. */
    fun fixed(value: Double, decimals: Int): String {
        val factor = 10.0.pow(decimals)
        val scaled = (abs(value) * factor).roundToLong()
        val int = scaled / factor.toLong()
        val frac = scaled % factor.toLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return if (decimals == 0) "$sign$int" else "$sign$int.${frac.toString().padStart(decimals, '0')}"
    }

    fun coords(p: LatLng, decimals: Int = 5) = "${fixed(p.lat, decimals)}, ${fixed(p.lng, decimals)}"

    /** "38 m", "1.2 km". */
    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.roundToLong()} m" else "${fixed(meters / 1000, 1)} km"

    /** "1.2 / 3.4 km" or "640 / 900 m". */
    fun progress(traveled: Double, total: Double): String =
        if (total < 1000) "${traveled.roundToLong()} / ${total.roundToLong()} m"
        else "${fixed(traveled / 1000, 1)} / ${fixed(total / 1000, 1)} km"

    /** "26 min", "1 h 05 min", "40 s". */
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${(seconds + 30) / 60} min"
        else -> "${seconds / 3600} h ${((seconds % 3600) / 60).toString().padStart(2, '0')} min"
    }

    fun kmh(metersPerSecond: Double): String = "${(metersPerSecond * 3.6).roundToLong()} km/h"

    /** Eight-point compass direction for a bearing. */
    fun compass(bearingDeg: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val i = (((bearingDeg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
        return names[i]
    }
}

/**
 * Minimal coordinate parser used when the app doesn't supply one: decimal `lat, lng` and
 * DMS (`35°39'34"N 139°42'02"E`). The full parser (Maps links, plus codes…) is provided by the
 * caller of [io.github.crockalet.haunt.ui.HauntApp].
 */
fun parseSimpleCoordinates(text: String): DetectedCoordinates? {
    val t = text.trim()
    val decimal = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""").find(t)
    if (decimal != null) {
        val lat = decimal.groupValues[1].toDouble()
        val lng = decimal.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) DetectedCoordinates(LatLng(lat, lng), "Decimal") else null
    }
    val dms = Regex("""(\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*["″]?\s*([NSEW])""")
    val parts = dms.findAll(t).toList()
    if (parts.size == 2) {
        fun value(m: MatchResult): Pair<Double, Char> {
            val (d, mi, s, h) = m.destructured
            val v = d.toDouble() + mi.toDouble() / 60 + s.toDouble() / 3600
            return (if (h == "S" || h == "W") -v else v) to h[0]
        }
        val (a, ha) = value(parts[0])
        val (b, hb) = value(parts[1])
        val (lat, lng) = if (ha == 'N' || ha == 'S') a to b else b to a
        if (hb == ha) return null
        return DetectedCoordinates(LatLng(lat, lng), "DMS")
    }
    return null
}
