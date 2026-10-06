package io.github.crockalet.haunt.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single owner of Haunt's state. The UI, the ADB socket and broadcast commands are all
 * clients of this, so humans and agents always see the same thing.
 */
interface HauntController {
    val state: StateFlow<HauntState>

    /** Every fix to inject, emitted once per tick while not [HauntState.Idle]. */
    val fixes: SharedFlow<Fix>

    fun setLocation(position: LatLng, altitude: Double? = null, accuracy: Float? = null, label: String? = null)

    /**
     * Play [route] at a constant [speed]. If the route carries valid [Route.timestampsMillis]
     * (a recorded GPX/KML track), it is replayed by its recorded timing instead, scaled by the
     * playback rate (see [setPlaybackRate]); strip the timestamps to force constant speed.
     */
    fun playRoute(route: Route, speed: Speed, loop: LoopMode = LoopMode.Once)

    /** Enter joystick mode at the current (or given) position. */
    fun startJoystick(maxSpeed: Speed, from: LatLng? = null)

    /** Joystick input: [bearingDeg] 0 = north, [magnitude] 0..1 of max speed. */
    fun joystickInput(bearingDeg: Double, magnitude: Double)

    /**
     * Constant speed for route playback and the joystick's max speed. Applied to a recorded track
     * that is being replayed by its timestamps, this switches it to constant-speed playback.
     */
    fun setSpeed(speed: Speed)

    /**
     * Multiplier for replaying a recorded track by its timestamps (1.0 = real time, 2.0 = twice as
     * fast). Ignored for constant-speed playback. Implementations that don't support timed
     * playback may ignore it.
     */
    fun setPlaybackRate(multiplier: Double) {}

    fun pause()

    fun resume()

    fun stop()
}
