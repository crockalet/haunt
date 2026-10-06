package io.github.crockalet.haunt.android.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.crockalet.haunt.android.HauntRuntime
import io.github.crockalet.haunt.android.MainActivity
import io.github.crockalet.haunt.android.ui.UiMapping
import io.github.crockalet.haunt.ui.components.GlassSurface
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.JoystickGrip
import io.github.crockalet.haunt.ui.components.JoystickPad
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The joystick drawn over other apps (`TYPE_APPLICATION_OVERLAY`, needs "Display over other apps").
 * Shown while [OverlayGeometry.shouldShow] holds; hosted by [io.github.crockalet.haunt.android.HauntService],
 * which runs for as long as joystick mode does. Drag the grip to move it (position saved as a screen
 * fraction); the corner button opens Haunt.
 *
 * Call [start] / [stop] on the main thread.
 */
class JoystickOverlay(private val context: Context, private val runtime: HauntRuntime) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var watch: Job? = null
    private var lastBearing = 0.0
    /** Window size the saved position was last applied for; re-applied only when the size changes. */
    private var placedSize = 0 to 0

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
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { Content() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Haunt joystick"
        }
        try {
            windowManager.addView(compose, lp)
        } catch (e: Exception) {
            // Permission revoked between the check and now, or the window token is gone.
            Log.w(TAG, "Could not show the floating joystick", e)
            lifecycleOwner.destroy()
            return
        }
        view = compose
        owner = lifecycleOwner
        params = lp
        placedSize = 0 to 0
        // Place it once its size is known (layout also runs while dragging; that must not snap it back).
        compose.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeFromSettings(v) }
    }

    private fun hide() {
        val v = view ?: return
        // A finger may still be on the pad: don't leave the ghost walking.
        runtime.controller.joystickInput(lastBearing, 0.0)
        runCatching { windowManager.removeView(v) }.onFailure { Log.w(TAG, "removeView failed", it) }
        owner?.destroy()
        view = null
        owner = null
        params = null
    }

    @Composable
    private fun Content() {
        val settings by runtime.settings.collectAsState()
        var bearing by remember { mutableDoubleStateOf(lastBearing) }
        var magnitude by remember { mutableDoubleStateOf(0.0) }
        val size = UiMapping.joystickSize(settings)
        HauntTheme(UiMapping.theme(settings.theme)) {
            Box(Modifier.padding(4.dp)) {
                JoystickPad(
                    bearingDeg = bearing,
                    magnitude = magnitude,
                    onInput = { b, m ->
                        bearing = b
                        magnitude = m
                        lastBearing = b
                        runtime.controller.joystickInput(b, m)
                    },
                    padSize = size.dp.dp,
                    modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
                )
                JoystickGrip(
                    onDrag = {},
                    onDragEnd = {},
                    modifier = Modifier.align(Alignment.TopEnd),
                    gestures = windowDrag(),
                )
                GlassSurface(Modifier.align(Alignment.TopStart), shape = HauntShapes.pill) {
                    IconButton(HauntIcons.Open, "Open Haunt", ::openApp, size = 34.dp, iconSize = 16.dp, tint = HauntTheme.colors.text)
                }
            }
        }
    }

    /** Drags the whole window with screen (raw) coordinates, which don't shift as the window moves. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun windowDrag(): Modifier {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        return Modifier.pointerInteropFilter { e ->
            val v = view ?: return@pointerInteropFilter false
            val lp = params ?: return@pointerInteropFilter false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val (w, h) = screenSize()
                    val (x, y) = OverlayGeometry.clamp(
                        startX + (e.rawX - downX).toInt(), startY + (e.rawY - downY).toInt(), w, h, v.width, v.height,
                    )
                    lp.x = x
                    lp.y = y
                    runCatching { windowManager.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val (w, h) = screenSize()
                    val (fx, fy) = OverlayGeometry.toFraction(lp.x, lp.y, w, h, v.width, v.height)
                    runtime.updateSettings { it.copy(overlayX = fx, overlayY = fy) }
                }
            }
            true
        }
    }

    private fun placeFromSettings(v: View) {
        val lp = params ?: return
        if (v.width == 0 || v.height == 0 || placedSize == (v.width to v.height)) return
        placedSize = v.width to v.height
        val (w, h) = screenSize()
        val s = runtime.settings.value
        val (x, y) = OverlayGeometry.toPixels(s.overlayX, s.overlayY, w, h, v.width, v.height)
        if (x == lp.x && y == lp.y) return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun openApp() {
        // Overlay apps may start activities from the background.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "Could not open Haunt", it) }
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

    private companion object {
        const val TAG = "JoystickOverlay"
    }
}
