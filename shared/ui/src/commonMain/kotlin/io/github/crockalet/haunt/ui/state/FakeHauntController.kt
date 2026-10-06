package io.github.crockalet.haunt.ui.state

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.currentFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * In-memory [HauntController] for previews, screenshots and running the UI without the Android
 * service. With a [scope] it simulates movement (routes and joystick) on a timer; without one it
 * is static and only changes on calls.
 */
class FakeHauntController(
    initial: HauntState = HauntState.Idle,
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = ::systemMillis,
    private val tickMillis: Long = 250,
) : HauntController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<HauntState> = _state.asStateFlow()

    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
    override val fixes: SharedFlow<Fix> = _fixes

    private var job: Job? = null
    private var route: Route? = null
    private var direction = 1
    private var joystick = 0.0 to 0.0

    private fun now(): Long = clock()

    private fun fix(position: LatLng, accuracy: Float = 5f, bearing: Float? = null, speed: Float? = null, altitude: Double? = 38.0) =
        Fix(position = position, altitude = altitude, accuracy = accuracy, bearing = bearing, speed = speed, timeMillis = now())

    override fun setLocation(position: LatLng, altitude: Double?, accuracy: Float?, label: String?) {
        stopTicker()
        _state.value = HauntState.Holding(fix(position, accuracy ?: 5f, altitude = altitude ?: 38.0), label)
    }

    override fun playRoute(route: Route, speed: Speed, loop: LoopMode) {
        if (route.points.size < 2) return
        this.route = route
        direction = 1
        val total = Geo.length(route.points)
        _state.value = HauntState.Moving(
            fix = fix(route.points.first(), speed = speed.metersPerSecond.toFloat()),
            routeName = route.name,
            progress = RouteProgress(0.0, total, (total / speed.metersPerSecond).roundToLong()),
            speed = speed,
            loop = loop,
            paused = false,
        )
        startTicker()
    }

    override fun startJoystick(maxSpeed: Speed, from: LatLng?) {
        val start = from ?: _state.value.currentFix?.position ?: return
        joystick = 0.0 to 0.0
        _state.value = HauntState.Joystick(fix(start, speed = 0f), maxSpeed, 0.0, 0.0)
        startTicker()
    }

    override fun joystickInput(bearingDeg: Double, magnitude: Double) {
        joystick = bearingDeg to magnitude.coerceIn(0.0, 1.0)
        val s = _state.value as? HauntState.Joystick ?: return
        _state.value = s.copy(headingDeg = bearingDeg)
    }

    override fun setSpeed(speed: Speed) {
        _state.value = when (val s = _state.value) {
            is HauntState.Moving -> s.copy(speed = speed)
            is HauntState.Joystick -> s.copy(maxSpeed = speed)
            else -> s
        }
    }

    override fun pause() {
        (_state.value as? HauntState.Moving)?.let { _state.value = it.copy(paused = true) }
    }

    override fun resume() {
        (_state.value as? HauntState.Moving)?.let { _state.value = it.copy(paused = false) }
    }

    override fun stop() {
        stopTicker()
        _state.value = HauntState.Idle
    }

    private fun stopTicker() {
        job?.cancel()
        job = null
    }

    private fun startTicker() {
        val scope = scope ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(tickMillis)
                tick(tickMillis / 1000.0)
            }
        }
    }

    /** Advances the simulation by [seconds]. Public for tests and screenshots. */
    fun tick(seconds: Double) {
        when (val s = _state.value) {
            is HauntState.Moving -> if (!s.paused) {
                val points = route?.points ?: return
                val total = s.progress.totalMeters
                var traveled = s.progress.traveledMeters + direction * s.speed.metersPerSecond * seconds
                if (traveled >= total) {
                    when (s.loop) {
                        LoopMode.Once -> traveled = total
                        LoopMode.Loop -> traveled -= total
                        LoopMode.PingPong -> { traveled = total; direction = -1 }
                    }
                } else if (traveled <= 0 && direction < 0) {
                    traveled = 0.0
                    direction = 1
                }
                val position = Geo.along(points, traveled)
                val remaining = if (direction > 0) total - traveled else traveled
                _state.value = s.copy(
                    fix = fix(position, bearing = Geo.bearing(s.fix.position, position).toFloat(), speed = s.speed.metersPerSecond.toFloat()),
                    progress = RouteProgress(traveled, total, (remaining / s.speed.metersPerSecond).roundToLong()),
                )
                _fixes.tryEmit(_state.value.currentFix!!)
                if (s.loop == LoopMode.Once && traveled >= total) stopTicker()
            }
            is HauntState.Joystick -> {
                val (bearing, magnitude) = joystick
                val speed = s.maxSpeed.metersPerSecond * magnitude
                val position = if (speed > 0) Geo.destination(s.fix.position, bearing, speed * seconds) else s.fix.position
                _state.value = s.copy(
                    fix = fix(position, bearing = bearing.toFloat(), speed = speed.toFloat()),
                    headingDeg = bearing,
                    distanceMeters = s.distanceMeters + speed * seconds,
                )
                _fixes.tryEmit(_state.value.currentFix!!)
            }
            is HauntState.Holding -> _fixes.tryEmit(s.fix)
            HauntState.Idle -> Unit
        }
    }
}

@OptIn(ExperimentalTime::class)
internal fun systemMillis(): Long = Clock.System.now().toEpochMilliseconds()
