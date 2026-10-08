package io.github.crockalet.haunt.android.overlay

import io.github.crockalet.haunt.core.HauntState

/** Pure rules for the floating joystick (unit-tested on the JVM). */
object OverlayGeometry {
    /**
     * Whether the floating pad should be on screen: the user turned it on, joystick mode is running,
     * Haunt's own UI isn't visible (it has its own pad) and Android lets us draw over other apps.
     */
    fun shouldShow(enabled: Boolean, state: HauntState, appVisible: Boolean, canDrawOverlays: Boolean): Boolean =
        enabled && state is HauntState.Joystick && !appVisible && canDrawOverlays

    /** Where a dropped window settles horizontally: flush with whichever screen side its centre is nearer. */
    fun snapToSide(x: Int, screenW: Int, viewW: Int): Int {
        val maxX = (screenW - viewW).coerceAtLeast(0)
        return if (x + viewW / 2f < screenW / 2f) 0 else maxX
    }

    /** Keeps the window fully on screen. */
    fun clamp(x: Int, y: Int, screenW: Int, screenH: Int, viewW: Int, viewH: Int): Pair<Int, Int> =
        x.coerceIn(0, (screenW - viewW).coerceAtLeast(0)) to y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
}
