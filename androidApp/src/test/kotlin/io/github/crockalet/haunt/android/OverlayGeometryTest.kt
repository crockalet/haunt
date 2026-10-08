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
    fun clampsOnScreen() {
        assertEquals(0 to 0, OverlayGeometry.clamp(-50, -10, 1080, 2400, 400, 400))
        assertEquals(680 to 2000, OverlayGeometry.clamp(900, 2300, 1080, 2400, 400, 400))
        assertEquals(100 to 200, OverlayGeometry.clamp(100, 200, 1080, 2400, 400, 400))
    }

    @Test
    fun snapsToTheNearerSide() {
        // 1080 wide, 300 wide window: centre left of 540 → left edge, otherwise flush right at 780.
        assertEquals(0, OverlayGeometry.snapToSide(0, 1080, 300))
        assertEquals(0, OverlayGeometry.snapToSide(389, 1080, 300))
        assertEquals(780, OverlayGeometry.snapToSide(390, 1080, 300))
        assertEquals(780, OverlayGeometry.snapToSide(780, 1080, 300))
        // Window wider than the screen: nowhere to go.
        assertEquals(0, OverlayGeometry.snapToSide(500, 300, 400))
    }
}
