package io.github.crockalet.haunt.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.theme.HauntMotion
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
 * Glass thumbstick ([padSize] across, knob 40% of it). Drag the accent knob; [onInput] receives
 * bearing (0 = north) and magnitude (0..1). On release the knob springs back and [onInput] gets
 * magnitude 0.
 *
 * When not dragging, the knob shows [bearingDeg]/[magnitude] (hoisted state). While dragging the knob
 * position stays inside the pad: the caller needn't feed [onInput] back for the knob to follow.
 * A disabled pad has a faded knob and ignores touches.
 */
@Composable
fun JoystickPad(
    bearingDeg: Double,
    magnitude: Double,
    onInput: (bearingDeg: Double, magnitude: Double) -> Unit,
    modifier: Modifier = Modifier,
    padSize: Dp = 140.dp,
    enabled: Boolean = true,
) {
    val c = HauntTheme.colors
    val density = LocalDensity.current
    val knobSize = padSize * 0.4f
    val maxTravel = with(density) { ((padSize - knobSize) / 2).toPx() }
    val input by rememberUpdatedState(onInput)
    // Bearing of the last input sent; the release keeps it, with magnitude 0.
    var lastBearing by remember { mutableDoubleStateOf(bearingDeg) }
    // Follows the finger exactly while dragging; springs back to the hoisted position on release.
    // Only read in the offset lambda, so a drag re-places the knob without recomposing.
    var drag by remember { mutableStateOf<Offset?>(null) }
    val dragging by remember { derivedStateOf(structuralEqualityPolicy()) { drag != null } }
    val rest = JoystickMath.toOffset(bearingDeg, magnitude, maxTravel)
    val spring = remember { Animatable(rest, Offset.VectorConverter) }
    var released by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(dragging, rest) {
        if (dragging) return@LaunchedEffect
        released?.let { spring.snapTo(it) }
        released = null
        spring.animateTo(rest, HauntMotion.bouncy())
    }

    val touch = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(maxTravel) {
            val center = Offset(size.width / 2f, size.height / 2f)
            fun emit(p: Offset) {
                var v = p - center
                val len = v.getDistance()
                if (len > maxTravel) v = v * (maxTravel / len)
                drag = v
                val (b, m) = JoystickMath.toPolar(v.x, v.y, maxTravel)
                lastBearing = b
                input(b, m)
            }
            detectDragGestures(
                onDragStart = { emit(it) },
                onDragEnd = {
                    released = drag
                    drag = null
                    input(lastBearing, 0.0)
                },
                onDragCancel = {
                    released = drag
                    drag = null
                    input(lastBearing, 0.0)
                },
                onDrag = { change, _ -> emit(change.position) },
            )
        }
    }

    GlassSurface(
        modifier
            .size(padSize)
            .semantics { contentDescription = if (enabled) "Joystick. Drag to move." else "Joystick. Press Start to steer." }
            .then(touch),
        shape = HauntShapes.pill,
    ) {
        val tickInset = padSize * 0.07f
        Tick(Modifier.align(Alignment.TopCenter).padding(top = tickInset), horizontal = false)
        Tick(Modifier.align(Alignment.BottomCenter).padding(bottom = tickInset), horizontal = false)
        Tick(Modifier.align(Alignment.CenterStart).padding(start = tickInset), horizontal = true)
        Tick(Modifier.align(Alignment.CenterEnd).padding(end = tickInset), horizontal = true)
        Box(
            Modifier
                .align(Alignment.Center)
                .offset {
                    val knob = drag ?: spring.value
                    IntOffset(knob.x.roundToInt(), knob.y.roundToInt())
                }
                .size(knobSize)
                .dropShadow(HauntShapes.pill, Shadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 4.dp)))
                .clip(HauntShapes.pill)
                .background(if (enabled) c.accent else c.accent.copy(alpha = 0.35f))
                .border(3.dp, Color.White, HauntShapes.pill),
        )
    }
}

/**
 * Small round glass handle that sits on the joystick's corner; dragging it moves the pad.
 * [onDrag] gets the drag delta in px; [onDragEnd] fires once the finger lifts (persist the position then).
 *
 * @param gestures replaces the built-in drag handling (the floating pad moves its whole window and
 *   needs screen coordinates instead).
 */
@Composable
fun JoystickGrip(
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    gestures: Modifier? = null,
) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    GlassSurface(
        modifier
            .size(size)
            .semantics { contentDescription = "Move joystick" }
            .then(
                gestures ?: Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                        onDrag = { change, amount ->
                            change.consume()
                            drag(amount)
                        },
                    )
                },
            ),
        shape = HauntShapes.pill,
        contentAlignment = Alignment.Center,
    ) {
        Icon(HauntIcons.Move, null, size = size * 0.5f, tint = HauntTheme.colors.text)
    }
}
