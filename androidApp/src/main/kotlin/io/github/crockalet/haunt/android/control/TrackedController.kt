package io.github.crockalet.haunt.android.control

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Route
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.core.currentFix
import io.github.crockalet.haunt.protocol.FinishReason
import io.github.crockalet.haunt.protocol.HauntEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * A [HauntController] decorator that gives every route a `routeId` and emits
 * [HauntEvent.RouteFinishedEvent]s with the right [FinishReason]:
 * - [FinishReason.Replaced] when a new route, location or joystick session replaces the active route;
 * - [FinishReason.Stopped] on [stop] / [holdCurrentPosition];
 * - [FinishReason.Arrived] when the engine leaves `Moving` on its own (end of a `Once` route).
 *
 * Every command runs under one lock together with an "arrival check" against the delegate's
 * *current* state, so a route that finished just before a command is reported as Arrived rather
 * than Replaced, and a stale observed state can never finish the wrong route. Everything that
 * drives the controller (UI, socket, broadcasts) must go through this decorator, not the delegate.
 */
class TrackedController(
    private val delegate: HauntController,
    private val emit: (HauntEvent) -> Unit,
    private val newRouteId: () -> String = { "r-" + UUID.randomUUID().toString().take(8) },
) : HauntController {

    private val lock = Any()
    private var activeRouteId: String? = null

    @Volatile
    private var lastSeenFix: Fix? = null

    override val state: StateFlow<HauntState> get() = delegate.state
    override val fixes: SharedFlow<Fix> get() = delegate.fixes

    /** Id of the route currently playing (Moving), or null. */
    val currentRouteId: String? get() = synchronized(lock) { activeRouteId }

    /** The current fix, or the last one injected when idle (where the device was last sent). */
    val lastFix: Fix? get() = delegate.state.value.currentFix ?: lastSeenFix

    /**
     * Starts [route] and returns its new routeId. [playbackRate] (if given) is applied first, for
     * replaying a timed track. A zero-length route arrives immediately.
     */
    fun play(route: Route, speed: Speed, loop: LoopMode = LoopMode.Once, playbackRate: Double? = null): String =
        synchronized(lock) {
            settleLocked()
            val before = currentFixLocked()
            playbackRate?.let(delegate::setPlaybackRate)
            delegate.playRoute(route, speed, loop)
            activeRouteId?.let { emit(HauntEvent.RouteFinishedEvent(it, FinishReason.Replaced, before)) }
            val id = newRouteId()
            activeRouteId = id
            settleLocked() // zero-length route: already holding at the end
            id
        }

    /**
     * `playback.stop`: stop moving but keep the current position. Returns false if there was nothing
     * to stop (idle or already holding).
     */
    fun holdCurrentPosition(): Boolean = synchronized(lock) {
        settleLocked()
        val label: String?
        val fix = when (val s = delegate.state.value) {
            is HauntState.Moving -> s.fix.also { label = s.routeName }
            is HauntState.Joystick -> s.fix.also { label = null }
            else -> return false
        }
        delegate.setLocation(fix.position, fix.altitude, null, label)
        finishLocked(FinishReason.Stopped, currentFixLocked())
        true
    }

    /** Called by the event pipeline on every observed state: detects arrivals. */
    fun onStateObserved() = synchronized(lock) { settleLocked() }

    override fun setLocation(position: LatLng, altitude: Double?, accuracy: Float?, label: String?) = synchronized(lock) {
        settleLocked()
        val before = currentFixLocked()
        delegate.setLocation(position, altitude, accuracy, label)
        finishLocked(FinishReason.Replaced, before)
    }

    override fun playRoute(route: Route, speed: Speed, loop: LoopMode) {
        play(route, speed, loop)
    }

    override fun startJoystick(maxSpeed: Speed, from: LatLng?) = synchronized(lock) {
        settleLocked()
        val before = currentFixLocked()
        delegate.startJoystick(maxSpeed, from)
        finishLocked(FinishReason.Replaced, before)
    }

    override fun joystickInput(bearingDeg: Double, magnitude: Double) = synchronized(lock) {
        settleLocked()
        delegate.joystickInput(bearingDeg, magnitude)
    }

    override fun setSpeed(speed: Speed) = synchronized(lock) {
        settleLocked()
        delegate.setSpeed(speed)
    }

    override fun setPlaybackRate(multiplier: Double) = synchronized(lock) {
        settleLocked()
        delegate.setPlaybackRate(multiplier)
    }

    override fun setLoopMode(loop: LoopMode) = synchronized(lock) {
        settleLocked()
        delegate.setLoopMode(loop)
    }

    override fun pause() = synchronized(lock) {
        settleLocked()
        delegate.pause()
    }

    override fun resume() = synchronized(lock) {
        settleLocked()
        delegate.resume()
    }

    override fun stop() = synchronized(lock) {
        settleLocked()
        val before = currentFixLocked()
        delegate.stop()
        finishLocked(FinishReason.Stopped, before)
    }

    // --- internals (call with lock held) ------------------------------------------------------

    private fun currentFixLocked(): Fix? = delegate.state.value.currentFix?.also { lastSeenFix = it }

    /** If a route is active but the engine is no longer Moving, it reached its end. */
    private fun settleLocked() {
        val s = delegate.state.value
        s.currentFix?.let { lastSeenFix = it }
        if (activeRouteId != null && s !is HauntState.Moving) finishLocked(FinishReason.Arrived, s.currentFix)
    }

    private fun finishLocked(reason: FinishReason, fix: Fix?) {
        val id = activeRouteId ?: return
        activeRouteId = null
        emit(HauntEvent.RouteFinishedEvent(id, reason, fix))
    }
}
