package io.github.crockalet.haunt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The movement engine: the single [HauntController] implementation.
 *
 * - While not [HauntState.Idle], a [Fix] with a fresh `timeMillis` is emitted on [fixes] every
 *   [tickInterval] (immediately on each mode change, then once per tick), even when stationary.
 * - Movement is integrated from the monotonic time actually elapsed (via [clock]), so a late tick
 *   doesn't slow a route down. Commands first settle movement up to "now" before applying.
 * - Thread-safety: all state lives in one immutable snapshot swapped by compare-and-set, so every
 *   command may be called from any thread and takes effect synchronously ([state] reflects it on
 *   return). Only the ticker runs on [scope]; cancelling [scope] stops ticking.
 *
 * Invalid arguments (out-of-range coordinates, negative speed, empty route) throw
 * [IllegalArgumentException]; [startJoystick] without a position throws [IllegalStateException].
 */
class DefaultHauntController(
    scope: CoroutineScope,
    val tickInterval: Duration = 1.seconds,
    private val clock: HauntClock = HauntClock.System,
    defaults: HauntDefaults = HauntDefaults(),
) : HauntController {

    private class Snapshot(
        /** Bumped on every mode change; restarts the tick phase so the new fix goes out at once. */
        val session: Long,
        val mark: ComparableTimeMark?,
        val mode: Mode,
        val public: HauntState,
        /** Rate applied to timed playback, kept across routes. */
        val playbackRate: Double,
    )

    private val core = MutableStateFlow(Snapshot(0, null, Mode.Idle, HauntState.Idle, 1.0))
    private val _state = MutableStateFlow<HauntState>(HauntState.Idle)
    private val _fixes = MutableSharedFlow<Fix>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _defaults = MutableStateFlow(defaults)

    override val state: StateFlow<HauntState> = _state.asStateFlow()
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    /** Accuracy/altitude defaults; changing them takes effect on the next fix. */
    var defaults: HauntDefaults
        get() = _defaults.value
        set(value) {
            _defaults.value = value
            transition(newSession = false) { it }
        }

    /**
     * Current timed-playback multiplier (see [setPlaybackRate]). It persists across [playRoute]
     * calls, so it may be set before or after starting a recorded track; default 1.0.
     */
    val playbackRate: Double get() = core.value.playbackRate

    init {
        require(tickInterval.isPositive()) { "tickInterval must be positive" }
        scope.launch {
            core.map { it.session to (it.mode != Mode.Idle) }
                .distinctUntilChanged()
                .collectLatest { (_, active) ->
                    if (!active) return@collectLatest
                    while (true) {
                        tick()
                        delay(tickInterval)
                    }
                }
        }
    }

    override fun setLocation(position: LatLng, altitude: Double?, accuracy: Float?, label: String?) {
        requireValid(position)
        require(altitude == null || altitude.isFinite()) { "altitude must be finite" }
        require(accuracy == null || (accuracy.isFinite() && accuracy >= 0f)) { "accuracy must be >= 0" }
        transition(newSession = true) { Mode.Hold(position, altitude, accuracy, label) }
    }

    override fun playRoute(route: Route, speed: Speed, loop: LoopMode) {
        require(route.points.isNotEmpty()) { "route has no points" }
        route.points.forEach(::requireValid)
        requireValidSpeed(speed)
        val track = PreparedRoute(route)
        transitionWithRate(newSession = true) { _, rate ->
            when {
                track.polyline.lengthMeters <= 0.0 -> Mode.Hold(
                    position = route.points.first(),
                    altitude = track.altitudeAt(0.0),
                    accuracy = null,
                    label = route.name,
                )
                else -> Mode.Play(
                    track = track,
                    loop = loop,
                    speed = speed,
                    rate = if (track.relativeTimes != null) rate else null,
                    cursor = 0.0,
                    forward = true,
                    paused = false,
                )
            }
        }
    }

    override fun startJoystick(maxSpeed: Speed, from: LatLng?) {
        from?.let(::requireValid)
        requireValidSpeed(maxSpeed)
        transition(newSession = true) { mode ->
            val start = from ?: MovementEngine.position(mode)
                ?: throw IllegalStateException("No current position: pass `from` or set a location first")
            val heading = MovementEngine.heading(mode) ?: 0.0
            Mode.Stick(
                position = start,
                altitude = if (from == null) MovementEngine.altitude(mode) else null,
                maxSpeed = maxSpeed,
                inputBearingDeg = heading,
                magnitude = 0.0,
                headingDeg = heading,
                distanceMeters = 0.0,
                paused = false,
            )
        }
    }

    override fun joystickInput(bearingDeg: Double, magnitude: Double) {
        require(bearingDeg.isFinite() && magnitude.isFinite()) { "joystick input must be finite" }
        val bearing = Geo.normalizeBearing(bearingDeg)
        val m = magnitude.coerceIn(0.0, 1.0)
        transition(newSession = false) { mode ->
            if (mode is Mode.Stick) {
                mode.copy(
                    inputBearingDeg = bearing,
                    magnitude = m,
                    headingDeg = if (m > 0.0) bearing else mode.headingDeg,
                )
            } else {
                mode
            }
        }
    }

    override fun setSpeed(speed: Speed) {
        requireValidSpeed(speed)
        transition(newSession = false) { mode ->
            when (mode) {
                is Mode.Play -> if (mode.timed) {
                    // Leave recorded timing: continue from the same spot at constant speed.
                    val distance = mode.distanceMeters
                    val total = mode.track.polyline.lengthMeters
                    mode.copy(speed = speed, rate = null, cursor = distance.coerceIn(0.0, total))
                } else {
                    mode.copy(speed = speed)
                }
                is Mode.Stick -> mode.copy(maxSpeed = speed)
                else -> mode
            }
        }
    }

    override fun setPlaybackRate(multiplier: Double) {
        require(multiplier.isFinite() && multiplier > 0.0) { "playback rate must be > 0" }
        transition(newSession = false, newRate = multiplier) { mode ->
            if (mode is Mode.Play && mode.timed) mode.copy(rate = multiplier) else mode
        }
    }

    override fun setLoopMode(loop: LoopMode) {
        transition(newSession = false) { mode ->
            when {
                mode !is Mode.Play || mode.loop == loop -> mode
                // Only ping-pong travels backwards; elsewhere carry on forwards from the same spot.
                loop == LoopMode.PingPong -> mode.copy(loop = loop)
                else -> mode.copy(loop = loop, forward = true)
            }
        }
    }

    override fun pause() = setPaused(true)

    override fun resume() = setPaused(false)

    private fun setPaused(paused: Boolean) {
        transition(newSession = false) { mode ->
            when (mode) {
                is Mode.Play -> mode.copy(paused = paused)
                is Mode.Stick -> mode.copy(paused = paused)
                else -> mode
            }
        }
    }

    override fun stop() {
        transition(newSession = true) { Mode.Idle }
    }

    // --- internals -------------------------------------------------------------------------

    private fun tick() {
        val snapshot = transition(newSession = false) { it }
        snapshot.public.currentFix?.let { _fixes.tryEmit(it) }
    }

    private inline fun transition(
        newSession: Boolean,
        newRate: Double? = null,
        crossinline change: (Mode) -> Mode,
    ): Snapshot = transitionWithRate(newSession, newRate) { mode, _ -> change(mode) }

    /**
     * Settle movement up to now, apply [change] atomically and publish. [change] may run more than
     * once under contention, so it must be pure.
     */
    private inline fun transitionWithRate(
        newSession: Boolean,
        newRate: Double? = null,
        crossinline change: (mode: Mode, rate: Double) -> Mode,
    ): Snapshot {
        val mark = clock.markNow()
        val now = clock.nowMillis()
        val updated = core.updateAndGet { s ->
            val dt = s.mark?.let { (mark - it).inWholeNanoseconds / 1e9 } ?: 0.0
            val settled = MovementEngine.advance(s.mode, dt)
            val rate = newRate ?: s.playbackRate
            val next = change(settled, rate)
            Snapshot(
                session = if (newSession) s.session + 1 else s.session,
                mark = mark,
                mode = next,
                public = MovementEngine.render(next, _defaults.value, now),
                playbackRate = rate,
            )
        }
        publish()
        return updated
    }

    /**
     * Copy the snapshot's public state to [_state]. Re-checks after writing so a thread that
     * publishes a stale snapshot always overwrites it with the latest one: the final value of
     * [_state] is always the latest snapshot.
     */
    private fun publish() {
        while (true) {
            val s = core.value
            _state.value = s.public
            if (core.value === s) return
        }
    }

    private fun requireValid(position: LatLng) {
        require(Geo.isValid(position)) { "invalid coordinate: $position" }
    }

    private fun requireValidSpeed(speed: Speed) {
        require(speed.metersPerSecond.isFinite() && speed.metersPerSecond >= 0.0) { "speed must be >= 0" }
    }
}
