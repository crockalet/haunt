package io.github.crockalet.haunt.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.theme.ThemeMode
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The map chrome driven like a user would, on the reporting phone's screen (1080×2400 at 480 dpi,
 * i.e. 360×800 dp): taps are pointer events, checks read the semantics tree.
 */
class MapFlowTest {
    private val pin = LatLng(4.21179, 73.53994)

    private fun onEdt(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { failure = runCatching(block).exceptionOrNull() }
        failure?.let { throw it }
    }

    private class Ui(val scene: ImageComposeScene, val density: Float) {
        private var time = 0L

        fun settle() = repeat(4) {
            scene.render(time)
            time += 300_000_000L
        }

        private fun all(): List<SemanticsNode> = buildList {
            fun walk(n: SemanticsNode) {
                add(n)
                n.children.forEach(::walk)
            }
            scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
        }

        fun find(description: String): SemanticsNode? = all().firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == description } == true
        }

        fun node(description: String): SemanticsNode = assertNotNull(find(description), "no node \"$description\"")

        fun toggled(description: String): Boolean = node(description).config[SemanticsProperties.ToggleableState] == ToggleableState.On

        /** Bounds in dp. */
        fun bounds(description: String): Rect = node(description).boundsInRoot.let {
            Rect(it.left / density, it.top / density, it.right / density, it.bottom / density)
        }

        fun tap(description: String) {
            val c = node(description).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, c)
            scene.render(time)
            scene.sendPointerEvent(PointerEventType.Release, c)
            settle()
        }
    }

    private fun ui(
        state: HauntAppState,
        data: () -> HauntAppData = { HauntAppData() },
        heightDp: Int = 800,
        block: Ui.() -> Unit,
    ) {
        val d = 3f
        ImageComposeScene((360 * d).toInt(), (heightDp * d).toInt(), Density(d)) {
            HauntApp(state.map.controller, data = data(), state = state)
        }.use { scene -> Ui(scene, d).apply { settle() }.block() }
    }

    /** Through [rememberHauntAppState], so engine states reach the holder as they do in the app. */
    private fun appUi(controller: FakeHauntController, defaults: HauntDefaults, block: Ui.() -> Unit) {
        val d = 3f
        ImageComposeScene((360 * d).toInt(), (800 * d).toInt(), Density(d)) {
            val s = rememberHauntAppState(controller, initialTheme = ThemeMode.Light, defaults = defaults)
            HauntApp(controller, data = HauntAppData(), state = s)
        }.use { scene -> Ui(scene, d).apply { settle() }.block() }
    }

    @Test
    fun joystickModeShowsThePadWhileAPinRunsAndStartTakesOver() = onEdt {
        val controller = FakeHauntController(HauntState.Holding(Fix(pin, timeMillis = 0), "Dropped pin"), clock = { 0L })
        appUi(controller, HauntDefaults(joystickSize = JoystickSize.Small)) {
            tap("Joystick mode")
            // The pin keeps running until Start; the pad is already there, waiting.
            assertIs<HauntState.Holding>(controller.state.value)
            node(JOYSTICK_WAITING)
            assertNull(find(JOYSTICK_LIVE))

            // Start (and the small Stop beside it) fit on a 360 dp screen.
            val start = bounds("Start joystick")
            assertTrue(start.left >= 0f && start.right <= 360f, "Start at $start")
            assertTrue(start.width >= 63.5f, "Start squeezed to ${start.width} dp")
            val stop = bounds("Stop haunting")
            assertTrue(stop.left >= 0f && stop.right <= start.left, "Stop at $stop")

            tap("Start joystick")
            assertEquals(pin, assertIs<HauntState.Joystick>(controller.state.value).fix.position)
            val pad = bounds(JOYSTICK_LIVE)
            assertTrue(pad.left >= 0f && pad.right <= 360f && pad.top >= 0f, "pad at $pad")
            assertTrue(pad.bottom <= bounds("Joystick mode").top, "pad overlaps the toolbar: $pad")
        }
    }

    @Test
    fun waitingPadDoesNotStartAnything() = onEdt {
        val controller = FakeHauntController(HauntState.Idle, clock = { 0L })
        val state = HauntAppState(controller, Screen.Map, ThemeMode.Light, LocalUiState(mode = MapMode.Joystick, lastPosition = pin))
        ui(state) {
            val pad = node(JOYSTICK_WAITING).boundsInRoot
            val up = pad.center + Offset(0f, -pad.height / 3)
            scene.sendPointerEvent(PointerEventType.Press, pad.center)
            scene.sendPointerEvent(PointerEventType.Move, up)
            scene.sendPointerEvent(PointerEventType.Release, up)
            settle()
            assertEquals(HauntState.Idle, controller.state.value)
        }
    }

    @Test
    fun floatingSwitchInTheJoystickCardTogglesBothWays() = onEdt {
        val controller = FakeHauntController(HauntState.Idle, clock = { 0L })
        val saved = mutableListOf<Boolean>()
        val state = HauntAppState(controller, Screen.Map, ThemeMode.Light, LocalUiState(mode = MapMode.Joystick, expanded = true, lastPosition = pin))
        ui(state, { HauntAppData(onDefaultsChange = { saved += it.floatingJoystick }) }) {
            assertTrue(!toggled(FLOAT))
            tap(FLOAT)
            assertTrue(toggled(FLOAT), "switch didn't turn on")
            tap(FLOAT)
            assertTrue(!toggled(FLOAT), "switch didn't turn off")
            assertEquals(listOf(true, false), saved)
        }
    }

    @Test
    fun floatingSwitchInSettingsTogglesBothWays() = onEdt {
        val controller = FakeHauntController(HauntState.Idle, clock = { 0L })
        val saved = mutableListOf<Boolean>()
        val state = HauntAppState(controller, Screen.Settings, ThemeMode.Light)
        ui(state, { HauntAppData(onDefaultsChange = { saved += it.floatingJoystick }) }, heightDp = 1600) {
            tap(FLOAT)
            assertTrue(toggled(FLOAT))
            tap(FLOAT)
            assertTrue(!toggled(FLOAT))
            assertEquals(listOf(true, false), saved)
        }
    }

    @Test
    fun missingOverlayPermissionIsShownNotEnforced() = onEdt {
        val controller = FakeHauntController(HauntState.Idle, clock = { 0L })
        var allowed = false
        var asked = 0
        val data = { HauntAppData(canDrawOverlays = allowed, onAllowOverlay = { asked++ }) }

        // Settings: on without the permission → the setting stays on, with an Allow button.
        val settings = HauntAppState(controller, Screen.Settings, ThemeMode.Light, defaults = HauntDefaults(floatingJoystick = true))
        ui(settings, data, heightDp = 1600) {
            assertTrue(toggled(FLOAT))
            tap(ALLOW)
            assertEquals(1, asked)
            assertTrue(toggled(FLOAT))
        }

        // Joystick card: same, and the setting still switches off (the Allow button goes with it).
        val map = HauntAppState(
            controller, Screen.Map, ThemeMode.Light, LocalUiState(mode = MapMode.Joystick, expanded = true, lastPosition = pin),
            defaults = HauntDefaults(floatingJoystick = true),
        )
        ui(map, data) {
            tap(ALLOW)
            assertEquals(2, asked)
            tap(FLOAT)
            assertTrue(!toggled(FLOAT))
            assertNull(find(ALLOW))
        }

        // Granted meanwhile (back from system settings): no Allow button, nothing else changes.
        allowed = true
        ui(HauntAppState(controller, Screen.Settings, ThemeMode.Light, defaults = HauntDefaults(floatingJoystick = true)), data, heightDp = 1600) {
            assertTrue(toggled(FLOAT))
            assertNull(find(ALLOW))
        }
    }

    private companion object {
        const val FLOAT = "Float over other apps"
        const val ALLOW = "Allow display over other apps"
        const val JOYSTICK_LIVE = "Joystick. Drag to move."
        const val JOYSTICK_WAITING = "Joystick. Press Start to steer."
    }
}
