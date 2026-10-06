package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.overlay.OverlayGeometry
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.Speed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayGeometryTest {
    private val fix = Fix(LatLng(35.0, 139.0), altitude = null, accuracy = 5f, speed = 0f, timeMillis = 0)
    private val joystick = HauntState.Joystick(fix, Speed.kmh(12.0), 0.0, 0.0)

    @Test
    fun showsOnlyForJoystickInTheBackgroundWithPermission() {
        assertTrue(OverlayGeometry.shouldShow(enabled = true, state = joystick, appVisible = false, canDrawOverlays = true))
        assertFalse(OverlayGeometry.shouldShow(enabled = false, state = joystick, appVisible = false, canDrawOverlays = true))
        assertFalse(OverlayGeometry.shouldShow(enabled = true, state = joystick, appVisible = true, canDrawOverlays = true))
        assertFalse(OverlayGeometry.shouldShow(enabled = true, state = joystick, appVisible = false, canDrawOverlays = false))
        assertFalse(OverlayGeometry.shouldShow(enabled = true, state = HauntState.Holding(fix, null), appVisible = false, canDrawOverlays = true))
        assertFalse(OverlayGeometry.shouldShow(enabled = true, state = HauntState.Idle, appVisible = false, canDrawOverlays = true))
    }

    @Test
    fun fractionsRoundTripAndDefault() {
        // 1080×2400 screen, 400×400 window → 680×2000 free.
        assertEquals(680 to 1240, OverlayGeometry.toPixels(null, null, 1080, 2400, 400, 400))
        assertEquals(0 to 2000, OverlayGeometry.toPixels(0f, 1f, 1080, 2400, 400, 400))
        assertEquals(170 to 500, OverlayGeometry.toPixels(0.25f, 0.25f, 1080, 2400, 400, 400))
        assertEquals(0.25f to 0.25f, OverlayGeometry.toFraction(170, 500, 1080, 2400, 400, 400))
        // Rotation: same fraction, new pixels.
        assertEquals(500 to 170, OverlayGeometry.toPixels(0.25f, 0.25f, 2400, 1080, 400, 400))
        // Window bigger than the screen: no free space, stays at 0.
        assertEquals(0f to 0f, OverlayGeometry.toFraction(10, 10, 300, 300, 400, 400))
    }

    @Test
    fun clampsOnScreen() {
        assertEquals(0 to 0, OverlayGeometry.clamp(-50, -10, 1080, 2400, 400, 400))
        assertEquals(680 to 2000, OverlayGeometry.clamp(900, 2300, 1080, 2400, 400, 400))
        assertEquals(100 to 200, OverlayGeometry.clamp(100, 200, 1080, 2400, 400, 400))
    }
}
