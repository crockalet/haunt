package io.github.crockalet.haunt.core

import kotlin.math.ceil

/**
 * Pure, immutable movement model behind [DefaultHauntController]. Every function returns a new
 * value; nothing here touches clocks, coroutines or shared state, so it is trivially testable and
 * safe to run inside a compare-and-set loop.
 */
internal sealed interface Mode {
    data object Idle : Mode

    data class Hold(
        val position: LatLng,
        val altitude: Double?,
        val accuracy: Float?,
        val label: String?,
    ) : Mode

    /**
     * Route playback. The playhead [cursor] lives in `[0, extent]`, where extent is metres for
     * constant-speed playback and milliseconds of recorded time for timed playback.
     */
    data class Play(
        val track: PreparedRoute,
        val loop: LoopMode,
        val speed: Speed,
        /** Non-null = replaying by recorded timestamps at this rate. */
        val rate: Double?,
        val cursor: Double,
        val forward: Boolean,
        val paused: Boolean,
    ) : Mode {
        val timed: Boolean get() = rate != null
        val extent: Double get() = if (rate != null) track.durationMillis.toDouble() else track.polyline.lengthMeters

        /** Cursor units per second. */
        val velocity: Double get() = if (rate != null) rate * 1000.0 else speed.metersPerSecond

        val distanceMeters: Double get() = if (rate != null) track.distanceAtTime(cursor) else cursor
    }

    data class Stick(
        val position: LatLng,
        val altitude: Double?,
        val maxSpeed: Speed,
        val inputBearingDeg: Double,
        val magnitude: Double,
        val headingDeg: Double,
        val distanceMeters: Double,
        val paused: Boolean,
    ) : Mode
}

/** A [Route] with its polyline and (optional) relative timing precomputed. */
internal class PreparedRoute(val route: Route) {
    val polyline = Polyline(route.points)

    /** Times relative to the first point, or null if the route has no usable timestamps. */
    val relativeTimes: LongArray? = route.timestampsMillis?.let { ts ->
        if (ts.size != route.points.size || ts.size < 2) return@let null
        for (i in 1 until ts.size) if (ts[i] < ts[i - 1]) return@let null
        if (ts.last() == ts.first()) return@let null
        LongArray(ts.size) { ts[it] - ts[0] }
    }

    val durationMillis: Long get() = relativeTimes?.last() ?: 0L

    private val altitudes: List<Double>? = route.altitudes?.takeIf { it.size == route.points.size }

    /** Segment index for recorded time [t] (largest i with times[i] <= t, skipping zero-duration segments). */
    fun segmentAtTime(t: Double): Int {
        val times = relativeTimes ?: return 0
        val n = times.size - 1
        if (t >= times[n]) {
            var i = n - 1
            while (i > 0 && times[i + 1] == times[i]) i--
            return i
        }
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun distanceAtTime(t: Double): Double {
        val times = relativeTimes ?: return 0.0
        val i = segmentAtTime(t)
        val span = (times[i + 1] - times[i]).toDouble()
        val fraction = if (span > 0) ((t - times[i]) / span).coerceIn(0.0, 1.0) else 1.0
        return polyline.cumulativeMeters[i] + fraction * polyline.segmentLengthMeters(i)
    }

    /** Recorded speed (m/s, before rate scaling) at time [t]. */
    fun recordedSpeedAtTime(t: Double): Double {
        val times = relativeTimes ?: return 0.0
        val i = segmentAtTime(t)
        val span = times[i + 1] - times[i]
        return if (span > 0) polyline.segmentLengthMeters(i) / (span / 1000.0) else 0.0
    }

    fun altitudeAt(distanceMeters: Double): Double? {
        val alts = altitudes ?: return null
        if (alts.size == 1) return alts[0]
        val seg = polyline.segmentIndexAt(distanceMeters)
        val len = polyline.segmentLengthMeters(seg)
        val fraction = if (len > 0) ((distanceMeters - polyline.cumulativeMeters[seg]) / len).coerceIn(0.0, 1.0) else 0.0
        return alts[seg] + (alts[seg + 1] - alts[seg]) * fraction
    }
}

internal object MovementEngine {

    /** Advance [mode] by [dtSeconds]. Only route playback and joystick move. */
    fun advance(mode: Mode, dtSeconds: Double): Mode {
        if (dtSeconds <= 0.0) return mode
        return when (mode) {
            Mode.Idle, is Mode.Hold -> mode
            is Mode.Play -> advancePlay(mode, dtSeconds)
            is Mode.Stick -> advanceStick(mode, dtSeconds)
        }
    }

    private fun advancePlay(play: Mode.Play, dt: Double): Mode {
        if (play.paused) return play
        val extent = play.extent
        val step = play.velocity * dt
        if (step <= 0.0) return play
        return when (play.loop) {
            LoopMode.Once -> {
                val next = play.cursor + step
                if (next >= extent) finish(play) else play.copy(cursor = next)
            }
            LoopMode.Loop -> {
                if (extent <= 0.0) return play.copy(cursor = 0.0)
                play.copy(cursor = (play.cursor + step).mod(extent), forward = true)
            }
            LoopMode.PingPong -> {
                if (extent <= 0.0) return play.copy(cursor = 0.0)
                // Unfold onto a [0, 2·extent) circle: forward half then the way back.
                val phase = if (play.forward) play.cursor else 2 * extent - play.cursor
                val next = (phase + step).mod(2 * extent)
                if (next <= extent) {
                    play.copy(cursor = next, forward = true)
                } else {
                    play.copy(cursor = 2 * extent - next, forward = false)
                }
            }
        }
    }

    /** End of a [LoopMode.Once] route: hold at the last point. */
    fun finish(play: Mode.Play): Mode.Hold {
        val polyline = play.track.polyline
        return Mode.Hold(
            position = polyline.points.last(),
            altitude = play.track.altitudeAt(polyline.lengthMeters),
            accuracy = null,
            label = play.track.route.name,
        )
    }

    private fun advanceStick(stick: Mode.Stick, dt: Double): Mode {
        if (stick.paused || stick.magnitude <= 0.0) return stick
        val step = stick.magnitude * stick.maxSpeed.metersPerSecond * dt
        if (step <= 0.0) return stick
        return stick.copy(
            position = Geo.destination(stick.position, stick.inputBearingDeg, step),
            headingDeg = stick.inputBearingDeg,
            distanceMeters = stick.distanceMeters + step,
        )
    }

    /** Public view of [mode], stamped at [nowMillis]. */
    fun render(mode: Mode, defaults: HauntDefaults, nowMillis: Long): HauntState = when (mode) {
        Mode.Idle -> HauntState.Idle
        is Mode.Hold -> HauntState.Holding(
            fix = Fix(
                position = mode.position,
                altitude = mode.altitude ?: defaults.altitude,
                accuracy = mode.accuracy ?: defaults.accuracy,
                timeMillis = nowMillis,
            ),
            label = mode.label,
        )
        is Mode.Play -> renderPlay(mode, defaults, nowMillis)
        is Mode.Stick -> HauntState.Joystick(
            fix = Fix(
                position = mode.position,
                altitude = mode.altitude ?: defaults.altitude,
                accuracy = defaults.accuracy,
                bearing = mode.headingDeg.toFloat(),
                speed = if (mode.paused) 0f else (mode.magnitude * mode.maxSpeed.metersPerSecond).toFloat(),
                timeMillis = nowMillis,
            ),
            maxSpeed = mode.maxSpeed,
            headingDeg = mode.headingDeg,
            distanceMeters = mode.distanceMeters,
            paused = mode.paused,
        )
    }

    private fun renderPlay(play: Mode.Play, defaults: HauntDefaults, nowMillis: Long): HauntState.Moving {
        val track = play.track
        val total = track.polyline.lengthMeters
        val distance = play.distanceMeters
        val at = track.polyline.positionAt(distance)
        val bearing = if (play.forward) at.bearingDeg else Geo.normalizeBearing(at.bearingDeg + 180.0)
        val currentSpeed = if (play.rate != null) {
            Speed(track.recordedSpeedAtTime(play.cursor) * play.rate)
        } else {
            play.speed
        }
        val remaining = if (play.forward) play.extent - play.cursor else play.cursor
        val eta = if (play.velocity > 0.0) ceil(remaining.coerceAtLeast(0.0) / play.velocity).toLong() else null
        return HauntState.Moving(
            fix = Fix(
                position = at.position,
                altitude = track.altitudeAt(distance) ?: defaults.altitude,
                accuracy = defaults.accuracy,
                bearing = bearing.toFloat(),
                speed = if (play.paused) 0f else currentSpeed.metersPerSecond.toFloat(),
                timeMillis = nowMillis,
            ),
            routeName = track.route.name,
            progress = RouteProgress(
                traveledMeters = if (play.forward) distance else total - distance,
                totalMeters = total,
                etaSeconds = eta,
            ),
            speed = currentSpeed,
            loop = play.loop,
            paused = play.paused,
            playbackRate = play.rate,
        )
    }

    fun position(mode: Mode): LatLng? = when (mode) {
        Mode.Idle -> null
        is Mode.Hold -> mode.position
        is Mode.Play -> mode.track.polyline.positionAt(mode.distanceMeters).position
        is Mode.Stick -> mode.position
    }

    fun altitude(mode: Mode): Double? = when (mode) {
        Mode.Idle -> null
        is Mode.Hold -> mode.altitude
        is Mode.Play -> mode.track.altitudeAt(mode.distanceMeters)
        is Mode.Stick -> mode.altitude
    }

    /** Direction of travel, if the mode has one. */
    fun heading(mode: Mode): Double? = when (mode) {
        Mode.Idle, is Mode.Hold -> null
        is Mode.Play -> {
            val bearing = mode.track.polyline.positionAt(mode.distanceMeters).bearingDeg
            if (mode.forward) bearing else Geo.normalizeBearing(bearing + 180.0)
        }
        is Mode.Stick -> mode.headingDeg
    }
}
