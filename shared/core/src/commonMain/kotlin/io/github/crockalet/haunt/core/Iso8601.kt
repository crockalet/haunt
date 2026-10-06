package io.github.crockalet.haunt.core

/**
 * Minimal ISO-8601 / RFC 3339 date-time support (no dependency), as used by GPX `<time>` and
 * KML `<when>`.
 */
object Iso8601 {
    private val pattern = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?$""",
    )

    /**
     * Parses `2024-05-01T12:34:56.789Z`, `…+09:00`, `…+0900`, `…+09`, a date only (`2024-05-01`),
     * or a local time without offset (treated as UTC). Returns epoch milliseconds or null.
     */
    fun parse(text: String): Long? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        var second = g[6].ifEmpty { "0" }.toInt()
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toInt() ?: 0
        if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        if (hour !in 0..24 || minute !in 0..59 || second !in 0..60) return null
        if (hour == 24 && (minute != 0 || second != 0 || millis != 0)) return null
        if (second == 60) second = 59 // leap second: clamp
        val offsetMinutes = parseOffset(g[8]) ?: return null
        val days = daysFromCivil(year, month, day)
        val secondsOfDay = hour * 3600L + minute * 60L + second
        return (days * 86_400L + secondsOfDay - offsetMinutes * 60L) * 1000L + millis
    }

    /** Formats as UTC, e.g. `2024-05-01T12:34:56Z`, or `…56.789Z` when there are milliseconds. */
    fun format(epochMillis: Long): String {
        val days = epochMillis.floorDiv(86_400_000L)
        val msOfDay = epochMillis.mod(86_400_000L)
        val (y, mo, d) = civilFromDays(days)
        val h = msOfDay / 3_600_000
        val mi = msOfDay / 60_000 % 60
        val s = msOfDay / 1000 % 60
        val ms = msOfDay % 1000
        return buildString {
            append(y.toString().padStart(4, '0')).append('-')
            append(mo.toString().padStart(2, '0')).append('-')
            append(d.toString().padStart(2, '0')).append('T')
            append(h.toString().padStart(2, '0')).append(':')
            append(mi.toString().padStart(2, '0')).append(':')
            append(s.toString().padStart(2, '0'))
            if (ms != 0L) append('.').append(ms.toString().padStart(3, '0'))
            append('Z')
        }
    }

    private fun parseOffset(text: String): Long? {
        if (text.isEmpty() || text == "Z" || text == "z") return 0
        val sign = if (text[0] == '-') -1 else 1
        val digits = text.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        if (hours > 18 || minutes > 59) return null
        return sign * (hours * 60L + minutes)
    }

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysInMonth(y: Int, m: Int) = when (m) {
        2 -> if (isLeap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
    }
}
