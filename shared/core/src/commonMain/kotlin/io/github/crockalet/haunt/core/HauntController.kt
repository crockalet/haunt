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

    fun playRoute(route: Route, speed: Speed, loop: LoopMode = LoopMode.Once)

    /** Enter joystick mode at the current (or given) position. */
    fun startJoystick(maxSpeed: Speed, from: LatLng? = null)

    /** Joystick input: [bearingDeg] 0 = north, [magnitude] 0..1 of max speed. */
    fun joystickInput(bearingDeg: Double, magnitude: Double)

    fun setSpeed(speed: Speed)

    fun pause()

    fun resume()

    fun stop()
}
