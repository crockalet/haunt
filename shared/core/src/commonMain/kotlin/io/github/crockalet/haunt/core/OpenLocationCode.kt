package io.github.crockalet.haunt.core

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Open Location Code ("plus codes", https://plus.codes) encoding and decoding, following the
 * reference implementation (integer arithmetic for exact decoding).
 */
object OpenLocationCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val PADDING = '0'
    private const val ENCODING_BASE = 20
    private const val PAIR_CODE_LENGTH = 10
    private const val MAX_DIGIT_COUNT = 15
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5
    private const val LAT_MAX = 90
    private const val LNG_MAX = 180
    private const val LAT_INTEGER_MULTIPLIER = 8000L * 3125L
    private const val LNG_INTEGER_MULTIPLIER = 8000L * 1024L
    private const val LAT_MSP_VALUE = LAT_INTEGER_MULTIPLIER * ENCODING_BASE * ENCODING_BASE
    private const val LNG_MSP_VALUE = LNG_INTEGER_MULTIPLIER * ENCODING_BASE * ENCODING_BASE

    /** Decoded area of a code. */
    data class CodeArea(
        val latitudeLo: Double,
        val longitudeLo: Double,
        val latitudeHi: Double,
        val longitudeHi: Double,
        val codeLength: Int,
    ) {
        val center: LatLng
            get() = LatLng(
                ((latitudeLo + latitudeHi) / 2).coerceAtMost(LAT_MAX.toDouble()),
                ((longitudeLo + longitudeHi) / 2).coerceAtMost(LNG_MAX.toDouble()),
            )
    }

    private fun digitValue(c: Char): Int = ALPHABET.indexOf(c.uppercaseChar())

    /** True if [code] is a syntactically valid full or short code. */
    fun isValid(code: String): Boolean {
        if (code.isEmpty()) return false
        val sep = code.indexOf(SEPARATOR)
        if (sep == -1 || sep != code.lastIndexOf(SEPARATOR)) return false
        if (sep > SEPARATOR_POSITION || sep % 2 == 1) return false
        if (code.length == 1) return false
        val pad = code.indexOf(PADDING)
        if (pad != -1) {
            if (sep < SEPARATOR_POSITION) return false
            if (pad == 0 || pad % 2 == 1) return false
            val padding = code.substring(pad, sep)
            if (padding.any { it != PADDING }) return false
            if (code.length > sep + 1) return false
        }
        if (code.length - sep - 1 == 1) return false
        return code.all { it == SEPARATOR || it == PADDING || digitValue(it) >= 0 }
    }

    fun isShort(code: String): Boolean {
        if (!isValid(code)) return false
        val sep = code.indexOf(SEPARATOR)
        return sep in 0 until SEPARATOR_POSITION
    }

    fun isFull(code: String): Boolean {
        if (!isValid(code) || isShort(code)) return false
        val first = digitValue(code[0]) * ENCODING_BASE
        if (first >= LAT_MAX * 2) return false
        if (code.length > 1) {
            val second = digitValue(code[1]) * ENCODING_BASE
            if (second >= LNG_MAX * 2) return false
        }
        return true
    }

    /** Decode a full code. Throws [IllegalArgumentException] for anything else. */
    fun decode(code: String): CodeArea {
        require(isFull(code)) { "Not a valid full plus code: $code" }
        val clean = code.filter { it != SEPARATOR && it != PADDING }.uppercase()
        var latVal = -LAT_MAX * LAT_INTEGER_MULTIPLIER
        var lngVal = -LNG_MAX * LNG_INTEGER_MULTIPLIER
        var latPlace = LAT_MSP_VALUE
        var lngPlace = LNG_MSP_VALUE
        var i = 0
        while (i < minOf(clean.length, PAIR_CODE_LENGTH)) {
            latPlace /= ENCODING_BASE
            lngPlace /= ENCODING_BASE
            latVal += digitValue(clean[i]) * latPlace
            if (i + 1 < clean.length) lngVal += digitValue(clean[i + 1]) * lngPlace
            i += 2
        }
        i = PAIR_CODE_LENGTH
        while (i < minOf(clean.length, MAX_DIGIT_COUNT)) {
            latPlace /= GRID_ROWS
            lngPlace /= GRID_COLUMNS
            val digit = digitValue(clean[i])
            latVal += (digit / GRID_COLUMNS) * latPlace
            lngVal += (digit % GRID_COLUMNS) * lngPlace
            i++
        }
        return CodeArea(
            latitudeLo = latVal.toDouble() / LAT_INTEGER_MULTIPLIER,
            longitudeLo = lngVal.toDouble() / LNG_INTEGER_MULTIPLIER,
            latitudeHi = (latVal + latPlace).toDouble() / LAT_INTEGER_MULTIPLIER,
            longitudeHi = (lngVal + lngPlace).toDouble() / LNG_INTEGER_MULTIPLIER,
            codeLength = minOf(clean.length, MAX_DIGIT_COUNT),
        )
    }

    /**
     * Encode [position] to a code of [codeLength] digits (2..10 even, or 11..15).
     */
    fun encode(position: LatLng, codeLength: Int = PAIR_CODE_LENGTH): String {
        require(codeLength in 2..MAX_DIGIT_COUNT && (codeLength >= PAIR_CODE_LENGTH || codeLength % 2 == 0)) {
            "Invalid code length $codeLength"
        }
        // Like the reference: round at 1e-6 of a unit (absorbs float noise), then floor.
        fun units(degreesFromOrigin: Double, multiplier: Long): Long =
            floor((degreesFromOrigin * multiplier * 1e6).roundToLong() / 1e6).toLong()
        var latVal = units(position.lat.coerceIn(-90.0, 90.0) + LAT_MAX, LAT_INTEGER_MULTIPLIER)
        latVal = latVal.coerceIn(0, 2 * LAT_MAX * LAT_INTEGER_MULTIPLIER - 1)
        val lngRange = 2 * LNG_MAX * LNG_INTEGER_MULTIPLIER
        var lngVal = units(Geo.normalizeLongitude(position.lng) + LNG_MAX, LNG_INTEGER_MULTIPLIER).mod(lngRange)

        val out = CharArray(MAX_DIGIT_COUNT)
        // Grid part (digits 11..15), least significant first.
        for (i in 0 until MAX_DIGIT_COUNT - PAIR_CODE_LENGTH) {
            val latDigit = (latVal % GRID_ROWS).toInt()
            val lngDigit = (lngVal % GRID_COLUMNS).toInt()
            out[MAX_DIGIT_COUNT - 1 - i] = ALPHABET[latDigit * GRID_COLUMNS + lngDigit]
            latVal /= GRID_ROWS
            lngVal /= GRID_COLUMNS
        }
        for (i in 0 until PAIR_CODE_LENGTH / 2) {
            out[PAIR_CODE_LENGTH - 1 - 2 * i] = ALPHABET[(lngVal % ENCODING_BASE).toInt()]
            out[PAIR_CODE_LENGTH - 2 - 2 * i] = ALPHABET[(latVal % ENCODING_BASE).toInt()]
            latVal /= ENCODING_BASE
            lngVal /= ENCODING_BASE
        }
        val digits = out.concatToString(0, codeLength)
        return if (codeLength < SEPARATOR_POSITION) {
            digits + "0".repeat(SEPARATOR_POSITION - codeLength) + SEPARATOR
        } else {
            digits.substring(0, SEPARATOR_POSITION) + SEPARATOR + digits.substring(SEPARATOR_POSITION)
        }
    }

    /**
     * Recover the full code nearest to [reference] from a short code (e.g. `9G8F+6X`).
     * Full codes are returned unchanged (upper-cased).
     */
    fun recoverNearest(shortCode: String, reference: LatLng): String {
        require(isValid(shortCode)) { "Not a valid plus code: $shortCode" }
        if (!isShort(shortCode)) return shortCode.uppercase()
        val refLat = reference.lat.coerceIn(-LAT_MAX.toDouble(), LAT_MAX.toDouble())
        val refLng = Geo.normalizeLongitude(reference.lng)
        val paddingLength = SEPARATOR_POSITION - shortCode.indexOf(SEPARATOR)
        val resolution = ENCODING_BASE.toDouble().pow(2.0 - paddingLength / 2)
        val half = resolution / 2.0
        val prefix = encode(LatLng(refLat, refLng)).substring(0, paddingLength)
        val area = decode(prefix + shortCode.uppercase())
        var lat = area.center.lat
        var lng = area.center.lng
        if (refLat + half < lat && lat - resolution >= -LAT_MAX) {
            lat -= resolution
        } else if (refLat - half > lat && lat + resolution <= LAT_MAX) {
            lat += resolution
        }
        if (refLng + half < lng) {
            lng -= resolution
        } else if (refLng - half > lng) {
            lng += resolution
        }
        return encode(LatLng(lat, Geo.normalizeLongitude(lng)), area.codeLength)
    }
}
