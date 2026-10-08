package io.github.crockalet.haunt.android.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.crockalet.haunt.android.HauntRuntime
import io.github.crockalet.haunt.android.settings.Units
import io.github.crockalet.haunt.android.ui.UiMapping
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.ui.components.JoystickMover
import io.github.crockalet.haunt.ui.components.JoystickPad
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.JoystickPlacement
import io.github.crockalet.haunt.ui.state.UiScale
import io.github.crockalet.haunt.ui.theme.HauntTheme
import io.github.crockalet.haunt.ui.theme.ScaledChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The joystick drawn over other apps (`TYPE_APPLICATION_OVERLAY`, needs "Display over other apps").
 * Shown while [OverlayGeometry.shouldShow] holds; hosted by [io.github.crockalet.haunt.android.HauntService],
 * which runs for as long as joystick mode does. Hold the knob to move it; on release it snaps to the
 * nearer screen side (position saved as a screen fraction).
 *
 * Call [start] / [stop] on the main thread.
 */
class JoystickOverlay(private val context: Context, private val runtime: HauntRuntime) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: TouchTrackingFrame? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var watch: Job? = null
    private var lastBearing = 0.0
    /** Window size the saved position was last applied for; re-applied only when the size changes. */
    private var placedSize = 0 to 0
    private var snap: ValueAnimator? = null
    /** Screen position of the window's lp (0, 0), measured once it's laid out. */
    private var originX = 0
    private var originY = 0

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /** Starts following settings, state and app visibility; shows / hides the window accordingly. */
    fun start(scope: CoroutineScope) {
        if (watch != null) return
        watch = scope.launch {
            combine(runtime.settings, runtime.state, runtime.appVisible) { s, state, visible ->
                // Permission can change behind our back; it's re-checked whenever anything else changes.
                OverlayGeometry.shouldShow(s.floatingJoystick, state, visible, canDrawOverlays())
            }.distinctUntilChanged().collect { show -> if (show) show() else hide() }
        }
    }

    fun stop() {
        watch?.cancel()
        watch = null
        hide()
    }

    private fun show() {
        if (view != null) return
        val lifecycleOwner = OverlayOwner().also { it.start() }
        val compose = ComposeView(context).apply { setContent { OverlayContent() } }
        val frame = TouchTrackingFrame(context).apply {
            // On the window's root view, so the ComposeView finds them walking up its parents.
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Windows added from a Service aren't hardware-accelerated unless they ask; `dropShadow` needs RenderNodes.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Haunt joystick"
        }
        try {
            windowManager.addView(frame, lp)
        } catch (e: Exception) {
            // Permission revoked between the check and now, or the window token is gone.
            Log.w(TAG, "Could not show the floating joystick", e)
            lifecycleOwner.destroy()
            return
        }
        view = frame
        owner = lifecycleOwner
        params = lp
        placedSize = 0 to 0
        // Place it once its size is known (layout also runs while dragging; that must not snap it back).
        frame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeFromSettings(v) }
    }

    private fun hide() {
        val v = view ?: return
        snap?.cancel()
        snap = null
        // A finger may still be on the pad: don't leave the ghost walking.
        runtime.controller.joystickInput(lastBearing, 0.0)
        runCatching { windowManager.removeView(v) }.onFailure { Log.w(TAG, "removeView failed", it) }
        owner?.destroy()
        view = null
        owner = null
        params = null
    }

    @Composable
    private fun OverlayContent() {
        val settings by runtime.settings.collectAsState()
        val state by runtime.state.collectAsState()
        var bearing by remember { mutableDoubleStateOf(lastBearing) }
        var magnitude by remember { mutableDoubleStateOf(0.0) }
        val mover = remember { WindowMover() }
        val maxSpeed = (state as? HauntState.Joystick)?.maxSpeed
        // Same scale as the in-app pad, so both are the same px size and share one JoystickPlacement spot.
        HauntTheme(UiMapping.theme(settings.theme)) {
            ScaledChrome(UiScale.factor(settings.uiScale)) {
                JoystickPad(
                    bearingDeg = bearing,
                    magnitude = magnitude,
                    onInput = { b, m ->
                        bearing = b
                        magnitude = m
                        lastBearing = b
                        runtime.controller.joystickInput(b, m)
                    },
                    padSize = UiMapping.joystickSize(settings).dp.dp,
                    style = UiMapping.joystickStyle(settings),
                    mover = mover,
                    readout = maxSpeed?.let {
                        Format.joystickReadout(bearing, magnitude * it.metersPerSecond, settings.units == Units.Metric)
                    },
                    showMoveHint = !settings.joystickMoveLearned,
                )
            }
        }
    }

    /**
     * Moves the whole window with screen (raw) coordinates, which don't shift as the window moves; the
     * pad's local delta does, so it's ignored.
     */
    private inner class WindowMover : JoystickMover {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onMoveStart() {
            val v = view ?: return
            val lp = params ?: return
            snap?.cancel()
            snap = null
            downX = v.rawX
            downY = v.rawY
            startX = lp.x + originX
            startY = lp.y + originY
            moved = false
        }

        override fun onMove(delta: Offset) {
            val v = view ?: return
            val lp = params ?: return
            val (w, h) = screenSize()
            val (sx, sy) = OverlayGeometry.clamp(
                startX + (v.rawX - downX).toInt(), startY + (v.rawY - downY).toInt(), w, h, v.width, v.height,
            )
            val x = sx - originX
            val y = sy - originY
            if (x == lp.x && y == lp.y) return
            moved = true
            lp.x = x
            lp.y = y
            runCatching { windowManager.updateViewLayout(v, lp) }
        }

        override fun onMoveEnd() {
            val v = view ?: return
            val lp = params ?: return
            if (!moved) return
            if (!runtime.settings.value.joystickMoveLearned) runtime.updateSettings { it.copy(joystickMoveLearned = true) }
            val target = OverlayGeometry.snapToSide(lp.x + originX, screenSize().first, v.width) - originX
            snap = ValueAnimator.ofInt(lp.x, target).apply {
                duration = SNAP_MILLIS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    if (view !== v) return@addUpdateListener
                    lp.x = it.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(v, lp) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (snap === animation) snap = null
                        if (cancelled || view !== v) return
                        val (w, h) = screenSize()
                        val (fx, fy) = JoystickPlacement.toFraction(lp.x + originX, lp.y + originY, w, h, v.width, v.height)
                        runtime.updateSettings { it.copy(joystickX = fx, joystickY = fy) }
                    }
                })
                start()
            }
        }
    }

    private fun placeFromSettings(v: View) {
        val lp = params ?: return
        if (v.width == 0 || v.height == 0 || placedSize == (v.width to v.height)) return
        placedSize = v.width to v.height
        // The system may offset overlay windows (e.g. below the status bar); measure it so the pad lands on
        // the same screen spot as the in-app one, which works in full-screen coordinates.
        val onScreen = IntArray(2).also { v.getLocationOnScreen(it) }
        originX = onScreen[0] - lp.x
        originY = onScreen[1] - lp.y
        val (w, h) = screenSize()
        val s = runtime.settings.value
        val (sx, sy) = JoystickPlacement.toPixels(s.joystickX, s.joystickY, w, h, v.width, v.height)
        val (cx, cy) = OverlayGeometry.clamp(sx, sy, w, h, v.width, v.height)
        val x = cx - originX
        val y = cy - originY
        if (x == lp.x && y == lp.y) return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    /** The whole display, like the edge-to-edge activity the in-app pad is placed in. */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        @Suppress("DEPRECATION")
        val p = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        return p.x to p.y
    }

    /** Lifecycle + saved state for a ComposeView that lives outside any activity. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    /** The window's root: remembers the latest touch in screen coordinates for [WindowMover]. */
    private class TouchTrackingFrame(context: Context) : FrameLayout(context) {
        var rawX = 0f
            private set
        var rawY = 0f
            private set

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            rawX = ev.rawX
            rawY = ev.rawY
            return super.dispatchTouchEvent(ev)
        }
    }

    private companion object {
        const val TAG = "JoystickOverlay"
        const val SNAP_MILLIS = 250L
    }
}
