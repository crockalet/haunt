package io.github.crockalet.haunt.android.overlay

import io.github.crockalet.haunt.core.HauntState
import kotlin.math.roundToInt

/** Pure rules for the floating joystick (unit-tested on the JVM). */
object OverlayGeometry {
    /** Default spot: right edge, a bit below the middle (thumb reach, clear of status bar and nav bar). */
    const val DEFAULT_X = 1f
    const val DEFAULT_Y = 0.62f

    /**
     * Whether the floating pad should be on screen: the user turned it on, joystick mode is running,
     * Haunt's own UI isn't visible (it has its own pad) and Android lets us draw over other apps.
     */
    fun shouldShow(enabled: Boolean, state: HauntState, appVisible: Boolean, canDrawOverlays: Boolean): Boolean =
        enabled && state is HauntState.Joystick && !appVisible && canDrawOverlays

    /** Window position (px, top-left) for a stored position fraction; null fractions mean the default spot. */
    fun toPixels(fx: Float?, fy: Float?, screenW: Int, screenH: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val freeW = (screenW - viewW).coerceAtLeast(0)
        val freeH = (screenH - viewH).coerceAtLeast(0)
        return ((fx ?: DEFAULT_X).coerceIn(0f, 1f) * freeW).roundToInt() to ((fy ?: DEFAULT_Y).coerceIn(0f, 1f) * freeH).roundToInt()
    }

    /** Inverse of [toPixels]: the fraction to persist, so the spot survives rotation and resolution changes. */
    fun toFraction(x: Int, y: Int, screenW: Int, screenH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {
        val freeW = (screenW - viewW).coerceAtLeast(0)
        val freeH = (screenH - viewH).coerceAtLeast(0)
        fun f(v: Int, free: Int) = if (free == 0) 0f else (v.toFloat() / free).coerceIn(0f, 1f)
        return f(x, freeW) to f(y, freeH)
    }

    /** Keeps the window fully on screen. */
    fun clamp(x: Int, y: Int, screenW: Int, screenH: Int, viewW: Int, viewH: Int): Pair<Int, Int> =
        x.coerceIn(0, (screenW - viewW).coerceAtLeast(0)) to y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
}
