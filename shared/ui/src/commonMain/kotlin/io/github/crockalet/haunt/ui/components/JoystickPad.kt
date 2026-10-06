package io.github.crockalet.haunt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Joystick vector math, shared with tests. */
object JoystickMath {
    /** Converts a drag offset from the pad centre (y down) into bearing (0 = north, clockwise) and 0..1 magnitude. */
    fun toPolar(dx: Float, dy: Float, maxTravel: Float): Pair<Double, Double> {
        val len = hypot(dx, dy)
        if (len == 0f || maxTravel <= 0f) return 0.0 to 0.0
        var bearing = atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI
        if (bearing < 0) bearing += 360.0
        return bearing to min(1.0, len / maxTravel.toDouble())
    }

    /** Inverse of [toPolar]: knob offset (y down) for a bearing and magnitude. */
    fun toOffset(bearingDeg: Double, magnitude: Double, maxTravel: Float): Offset {
        val r = bearingDeg * PI / 180.0
        val m = magnitude.coerceIn(0.0, 1.0) * maxTravel
        return Offset((sin(r) * m).toFloat(), (-cos(r) * m).toFloat())
    }
}

/**
 * Glass thumbstick (140dp). Drag the accent knob; [onInput] receives bearing (0 = north) and
 * magnitude (0..1). On release the knob springs back and [onInput] gets magnitude 0.
 *
 * When not dragging, the knob shows [bearingDeg]/[magnitude] (hoisted state).
 */
@Composable
fun JoystickPad(
    bearingDeg: Double,
    magnitude: Double,
    onInput: (bearingDeg: Double, magnitude: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    val density = LocalDensity.current
    val padSize = 140.dp
    val knobSize = 56.dp
    val maxTravel = with(density) { ((padSize - knobSize) / 2).toPx() }
    val input by rememberUpdatedState(onInput)
    val lastBearing by rememberUpdatedState(bearingDeg)
    var drag by remember { mutableStateOf<Offset?>(null) }
    val knob = drag ?: JoystickMath.toOffset(bearingDeg, magnitude, maxTravel)

    GlassSurface(
        modifier
            .size(padSize)
            .semantics { contentDescription = "Joystick. Drag to move." }
            .pointerInput(maxTravel) {
                val center = Offset(size.width / 2f, size.height / 2f)
                fun emit(p: Offset) {
                    var v = p - center
                    val len = v.getDistance()
                    if (len > maxTravel) v = v * (maxTravel / len)
                    drag = v
                    val (b, m) = JoystickMath.toPolar(v.x, v.y, maxTravel)
                    input(b, m)
                }
                detectDragGestures(
                    onDragStart = { emit(it) },
                    onDragEnd = {
                        drag = null
                        input(lastBearing, 0.0)
                    },
                    onDragCancel = {
                        drag = null
                        input(lastBearing, 0.0)
                    },
                    onDrag = { change, _ -> emit(change.position) },
                )
            },
        shape = HauntShapes.pill,
    ) {
        Tick(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontal = false)
        Tick(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontal = false)
        Tick(Modifier.align(Alignment.CenterStart).padding(start = 10.dp), horizontal = true)
        Tick(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), horizontal = true)
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(knobSize)
                .dropShadow(HauntShapes.pill, Shadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 4.dp)))
                .clip(HauntShapes.pill)
                .background(c.accent)
                .border(3.dp, Color.White, HauntShapes.pill),
        )
    }
}
