package io.github.crockalet.haunt.ui.state

import io.github.crockalet.haunt.core.CoordinateParseResult
import io.github.crockalet.haunt.core.CoordinateParser
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

    /** Playback rate chip: "1×", "2×", "1.5×". */
    fun rate(multiplier: Double): String {
        val whole = multiplier.roundToLong()
        return if (abs(multiplier - whole) < 0.05) "$whole×" else "${fixed(multiplier, 1)}×"
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
 * Detects coordinates in pasted text with the full [CoordinateParser] (decimal, DMS, DDM, Google
 * Maps links, geo: URIs, plus codes). [reference] resolves short plus codes. Null if [text] isn't
 * coordinates.
 */
fun detectCoordinates(text: String, reference: LatLng? = null): DetectedCoordinates? =
    when (val r = CoordinateParser.parse(text, reference)) {
        is CoordinateParseResult.Success -> DetectedCoordinates(r.position, r.format.label, r.label)
        is CoordinateParseResult.Failure -> null
    }
