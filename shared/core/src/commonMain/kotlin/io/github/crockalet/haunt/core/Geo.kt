package io.github.crockalet.haunt.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-Earth geodesy helpers. Distances in metres, bearings in degrees (0 = north, clockwise). */
object Geo {
    /** Mean Earth radius (IUGG), metres. */
    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    /** Great-circle distance between [a] and [b] (haversine), in metres. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val phi1 = a.lat.toRadians()
        val phi2 = b.lat.toRadians()
        val dPhi = (b.lat - a.lat).toRadians()
        val dLambda = (b.lng - a.lng).toRadians()
        val h = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing of the great circle from [from] to [to], in [0, 360). 0 if the points coincide. */
    fun initialBearingDeg(from: LatLng, to: LatLng): Double {
        if (from == to) return 0.0
        val phi1 = from.lat.toRadians()
        val phi2 = to.lat.toRadians()
        val dLambda = (to.lng - from.lng).toRadians()
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return normalizeBearing(atan2(y, x).toDegrees())
    }

    /** Point reached travelling [distanceMeters] from [start] along the great circle with initial [bearingDeg]. */
    fun destination(start: LatLng, bearingDeg: Double, distanceMeters: Double): LatLng {
        if (distanceMeters == 0.0) return start
        val delta = distanceMeters / EARTH_RADIUS_METERS
        val theta = bearingDeg.toRadians()
        val phi1 = start.lat.toRadians()
        val lambda1 = start.lng.toRadians()
        val sinPhi2 = (sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)).coerceIn(-1.0, 1.0)
        val phi2 = asin(sinPhi2)
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sinPhi2,
        )
        return LatLng(phi2.toDegrees(), normalizeLongitude(lambda2.toDegrees()))
    }

    /**
     * Point at [fraction] (0..1) of the way from [a] to [b] along the great circle.
     * Falls back to linear interpolation for (near-)coincident points.
     */
    fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng {
        if (fraction <= 0.0) return a
        if (fraction >= 1.0) return b
        val delta = distanceMeters(a, b) / EARTH_RADIUS_METERS
        if (delta < 1e-9) {
            return LatLng(a.lat + (b.lat - a.lat) * fraction, a.lng + (b.lng - a.lng) * fraction)
        }
        val sinDelta = sin(delta)
        val wa = sin((1 - fraction) * delta) / sinDelta
        val wb = sin(fraction * delta) / sinDelta
        val phi1 = a.lat.toRadians()
        val phi2 = b.lat.toRadians()
        val l1 = a.lng.toRadians()
        val l2 = b.lng.toRadians()
        val x = wa * cos(phi1) * cos(l1) + wb * cos(phi2) * cos(l2)
        val y = wa * cos(phi1) * sin(l1) + wb * cos(phi2) * sin(l2)
        val z = wa * sin(phi1) + wb * sin(phi2)
        val phi = atan2(z, sqrt(x * x + y * y))
        val lambda = atan2(y, x)
        return LatLng(phi.toDegrees(), normalizeLongitude(lambda.toDegrees()))
    }

    /** Normalises any bearing to [0, 360). */
    fun normalizeBearing(deg: Double): Double {
        val r = deg % 360.0
        val n = if (r < 0) r + 360.0 else r
        return if (n >= 360.0) 0.0 else n + 0.0 // + 0.0 turns -0.0 into 0.0
    }

    /** Normalises a longitude to [-180, 180). */
    fun normalizeLongitude(deg: Double): Double {
        val r = (deg + 180.0) % 360.0
        val n = if (r < 0) r + 360.0 else r
        return n - 180.0
    }

    /** Sum of great-circle segment lengths, in metres. */
    fun polylineLength(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distanceMeters(points[i - 1], points[i])
        return total
    }

    fun isValid(position: LatLng): Boolean =
        position.lat.isFinite() && position.lng.isFinite() &&
            position.lat in -90.0..90.0 && position.lng in -180.0..180.0
}

fun LatLng.distanceTo(other: LatLng): Double = Geo.distanceMeters(this, other)

fun LatLng.bearingTo(other: LatLng): Double = Geo.initialBearingDeg(this, other)

fun LatLng.destination(bearingDeg: Double, distanceMeters: Double): LatLng =
    Geo.destination(this, bearingDeg, distanceMeters)

/** A position along a [Polyline]. */
data class PolylinePosition(
    val position: LatLng,
    /** Bearing of travel (forward along the polyline) at [position], degrees in [0, 360). */
    val bearingDeg: Double,
    /** Index of the segment `points[segmentIndex] → points[segmentIndex + 1]`. */
    val segmentIndex: Int,
    /** Distance along the polyline, clamped to [0, length]. */
    val distanceMeters: Double,
)

/**
 * A polyline with precomputed cumulative distances so that [positionAt] is O(log n).
 * Zero-length segments (repeated points) are allowed and skipped.
 */
class Polyline(val points: List<LatLng>) {
    init {
        require(points.isNotEmpty()) { "Polyline needs at least one point" }
    }

    /** `cumulative[i]` = distance from `points[0]` to `points[i]`. */
    val cumulativeMeters: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
    }

    val lengthMeters: Double get() = cumulativeMeters.last()

    val segmentCount: Int get() = maxOf(points.size - 1, 0)

    /** Initial bearing of each segment; zero-length segments borrow the nearest non-empty one. */
    private val segmentBearings: DoubleArray = DoubleArray(segmentCount).also { b ->
        val n = segmentCount
        val known = BooleanArray(n)
        for (i in 0 until n) {
            if (cumulativeMeters[i + 1] > cumulativeMeters[i]) {
                b[i] = Geo.initialBearingDeg(points[i], points[i + 1])
                known[i] = true
            }
        }
        var last = -1
        for (i in 0 until n) if (known[i]) last = i else if (last >= 0) b[i] = b[last]
        val first = known.indexOfFirst { it }
        for (i in 0 until first) b[i] = b[first]
    }

    fun segmentLengthMeters(index: Int): Double = cumulativeMeters[index + 1] - cumulativeMeters[index]

    fun segmentBearingDeg(index: Int): Double = segmentBearings[index]

    /** Position [distanceMeters] along the polyline (clamped to its ends). */
    fun positionAt(distanceMeters: Double): PolylinePosition {
        val total = lengthMeters
        val d = if (distanceMeters.isNaN()) 0.0 else distanceMeters.coerceIn(0.0, total)
        if (points.size == 1) return PolylinePosition(points[0], 0.0, 0, 0.0)
        val seg = segmentIndexAt(d)
        val len = segmentLengthMeters(seg)
        val a = points[seg]
        val b = points[seg + 1]
        val fraction = if (len > 0) (d - cumulativeMeters[seg]) / len else 0.0
        val position = Geo.interpolate(a, b, fraction)
        val bearing = if (len > 0 && Geo.distanceMeters(position, b) > 0.5) {
            Geo.initialBearingDeg(position, b)
        } else {
            segmentBearings[seg]
        }
        return PolylinePosition(position, bearing, seg, d)
    }

    /**
     * Binary search for the segment containing [d]: the last segment `i` with
     * `cumulative[i] <= d`, preferring non-empty segments.
     */
    fun segmentIndexAt(d: Double): Int {
        val n = segmentCount
        if (n == 0) return 0
        if (d >= lengthMeters) {
            // Last non-empty segment, so the bearing at the very end is meaningful.
            var i = n - 1
            while (i > 0 && segmentLengthMeters(i) == 0.0) i--
            return i
        }
        // Largest i in [0, n-1] with cumulative[i] <= d.
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulativeMeters[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }
}

internal fun Double.toRadians(): Double = this * PI / 180.0

internal fun Double.toDegrees(): Double = this * 180.0 / PI
