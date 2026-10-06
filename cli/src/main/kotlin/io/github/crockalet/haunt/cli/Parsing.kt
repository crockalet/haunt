package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.Speed

/** Unicode minus used to smuggle negative numbers past the option parser (see [escapeNegativeNumbers]). */
internal const val MINUS_SENTINEL = '−'

private val NEGATIVE_NUMBER = Regex("""^-\d[\d.]*(,.*)?$""")

/**
 * Clikt treats `-33.86` as an (unknown) option. Coordinates are often negative, so rewrite tokens that look
 * like negative numbers to use a Unicode minus, which our number parsers accept. Tokens after `--` are kept.
 */
fun escapeNegativeNumbers(argv: List<String>): List<String> {
    val end = argv.indexOf("--").let { if (it < 0) argv.size else it }
    return argv.mapIndexed { i, tok -> if (i < end && NEGATIVE_NUMBER.matches(tok)) MINUS_SENTINEL + tok.drop(1) else tok }
}

/** Parses a decimal number, accepting the Unicode minus and a leading `+`. */
fun parseNumber(text: String): Double? = text.trim().replace(MINUS_SENTINEL, '-').removePrefix("+").toDoubleOrNull()

/** `35.6586,139.7454`, `35.6586, 139.7454` or `35.6586 139.7454` → LatLng (range-checked), else null. */
fun parseLatLng(text: String): LatLng? {
    val parts = text.trim().split(Regex("""\s*[,;]\s*|\s+""")).filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parseNumber(parts[0]) ?: return null
    val lng = parseNumber(parts[1]) ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return LatLng(lat, lng)
}

/** A place given on the command line: coordinates or a free-text query (geocoded on the device). */
sealed interface Target {
    data class Coordinates(val position: LatLng) : Target
    data class Query(val text: String) : Target
}

/** `["35.6", "139.7"]`, `["35.6,139.7"]` → coordinates; anything else → a query (words joined by spaces). */
fun parseTarget(args: List<String>): Target {
    require(args.isNotEmpty()) { "a place or coordinates is required" }
    val joined = args.joinToString(" ")
    parseLatLng(joined)?.let { return Target.Coordinates(it) }
    if (args.size == 2 && args.all { parseNumber(it) != null }) {
        throw IllegalArgumentException("coordinates out of range: $joined (lat -90..90, lng -180..180)")
    }
    return Target.Query(joined.replace(MINUS_SENTINEL, '-'))
}

/**
 * Speeds: `walk`, `cycle`/`bike`, `drive`/`car`, or a number with unit: `30kmh`, `30km/h`, `5mps`, `5m/s`,
 * `20mph`. A bare number is km/h.
 */
fun parseSpeed(text: String): Speed {
    val t = text.trim().lowercase().replace(" ", "")
    when (t) {
        "walk", "walking" -> return Speed.Walk
        "cycle", "cycling", "bike" -> return Speed.Cycle
        "drive", "driving", "car" -> return Speed.Drive
    }
    val m = Regex("""^([+\-${MINUS_SENTINEL}]?[\d.]+)(kmh|km/h|kph|mps|m/s|ms|mph)?$""").matchEntire(t)
        ?: throw IllegalArgumentException("invalid speed \"$text\" (use walk, cycle, drive, 30kmh, 5mps or 20mph)")
    val value = parseNumber(m.groupValues[1]) ?: throw IllegalArgumentException("invalid speed \"$text\"")
    require(value > 0) { "speed must be positive" }
    return when (m.groupValues[2]) {
        "", "kmh", "km/h", "kph" -> Speed.kmh(value)
        "mps", "m/s", "ms" -> Speed(value)
        "mph" -> Speed(value * 0.44704)
        else -> error("unreachable")
    }
}

/** Playback rate: `2x`, `0.5x`, `2`. */
fun parseRate(text: String): Double {
    val v = parseNumber(text.trim().lowercase().removeSuffix("x").removeSuffix("×"))
        ?: throw IllegalArgumentException("invalid rate \"$text\" (e.g. 2x or 0.5x)")
    require(v > 0) { "rate must be positive" }
    return v
}
