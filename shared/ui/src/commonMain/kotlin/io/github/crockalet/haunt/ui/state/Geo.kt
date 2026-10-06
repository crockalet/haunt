package io.github.crockalet.haunt.ui.state

import io.github.crockalet.haunt.core.LatLng
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geo helpers the UI needs for drawing (kept local so the UI doesn't depend on engine internals). */
internal object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat)
        val dLng = rad(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun length(points: List<LatLng>): Double = points.zipWithNext { a, b -> distance(a, b) }.sum()

    fun bearing(a: LatLng, b: LatLng): Double {
        val y = sin(rad(b.lng - a.lng)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lng - a.lng))
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }

    fun destination(from: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val d = meters / EARTH_RADIUS_M
        val b = rad(bearingDeg)
        val lat1 = rad(from.lat)
        val lng1 = rad(from.lng)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lng2 = lng1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLng(deg(lat2), ((deg(lng2) + 540.0) % 360.0) - 180.0)
    }

    fun interpolate(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /** Splits [points] at [meters] along the line: (travelled part, remaining part). Both include the cut point. */
    fun split(points: List<LatLng>, meters: Double): Pair<List<LatLng>, List<LatLng>> {
        if (points.size < 2) return points to points
        var left = meters.coerceAtLeast(0.0)
        for (i in 0 until points.lastIndex) {
            val seg = distance(points[i], points[i + 1])
            if (left <= seg) {
                val cut = if (seg == 0.0) points[i] else interpolate(points[i], points[i + 1], left / seg)
                return (points.subList(0, i + 1) + cut) to (listOf(cut) + points.subList(i + 1, points.size))
            }
            left -= seg
        }
        return points to listOf(points.last())
    }

    /** Point at [meters] along the polyline. */
    fun along(points: List<LatLng>, meters: Double): LatLng = split(points, meters).first.last()

    /** Offset a point by metres east/north (local tangent plane approximation). */
    fun offset(from: LatLng, eastM: Double, northM: Double): LatLng = LatLng(
        lat = from.lat + deg(northM / EARTH_RADIUS_M),
        lng = from.lng + deg(eastM / (EARTH_RADIUS_M * cos(rad(from.lat)))),
    )

    /** Metres east/north of [p] relative to [origin]. */
    fun toLocal(origin: LatLng, p: LatLng): Pair<Double, Double> = Pair(
        rad(p.lng - origin.lng) * EARTH_RADIUS_M * cos(rad(origin.lat)),
        rad(p.lat - origin.lat) * EARTH_RADIUS_M,
    )
}
