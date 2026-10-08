package io.github.crockalet.haunt.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.JoystickStyle
import io.github.crockalet.haunt.ui.theme.HauntColors
import io.github.crockalet.haunt.ui.theme.HauntMotion
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Moves the whole joystick after a hold on the knob. [onMove] gets the pointer delta in px since the
 * last event (in the pad's own coordinates); a host that moves its window can ignore it and track raw
 * screen coordinates instead.
 */
interface JoystickMover {
    fun onMoveStart()
    fun onMove(delta: Offset)
    fun onMoveEnd()
}

/** Joystick vector math, shared with tests. */
object JoystickMath {
    /** Design units: the travel ring's outer diameter, which [JoystickSize] values are. */
    const val RING = 140f

    /** Touch box edge in design units; everything is centred in it. */
    const val FOOTPRINT = 180f

    /** Max knob travel in design units. */
    const val TRAVEL = 48f

    /** Above this magnitude the pad counts as driving (arc, lit chevrons, readout). */
    const val DRIVE_MAGNITUDE = 0.12

    /** Above this magnitude the Compass style snaps to the nearest of 8 headings. */
    const val SNAP_MAGNITUDE = 7.0 / 48.0

    /** A finger held this long without moving more than [HOLD_SLOP_DP] enters move mode. */
    const val HOLD_MILLIS = 450L
    const val HOLD_SLOP_DP = 6f

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

    /** Nearest of the 8 compass headings: 0 = N, 1 = NE, … 7 = NW. */
    fun heading(bearingDeg: Double): Int = (((bearingDeg % 360.0 + 360.0) % 360.0) / 45.0).roundToInt() % 8

    /** Whether chevron [chevron] (0 = N, 1 = E, 2 = S, 3 = W) is lit for [heading]: the 3 headings around it. */
    fun isChevronLit(chevron: Int, heading: Int): Boolean {
        val d = abs(heading - chevron * 2) % 8
        return min(d, 8 - d) <= 1
    }

    /** Whether a pointer at [dx],[dy] from where it went down has moved too far for a hold. */
    fun breaksHold(dx: Float, dy: Float, slopPx: Float): Boolean = hypot(dx, dy) > slopPx

    /**
     * Knob position and emitted vector for a pointer at [dx],[dy] from the pad centre: clamped to [maxTravel],
     * and with [snap] past [SNAP_MAGNITUDE] snapped to the nearest 45° ([Steer.heading] is then non-null).
     */
    fun steer(dx: Float, dy: Float, maxTravel: Float, snap: Boolean): Steer {
        val (b, m) = toPolar(dx, dy, maxTravel)
        if (snap && m > SNAP_MAGNITUDE) {
            val h = heading(b)
            val sb = h * 45.0
            return Steer(toOffset(sb, m, maxTravel), sb, m, h)
        }
        return Steer(toOffset(b, m, maxTravel), b, m, null)
    }

    @Immutable
    data class Steer(val knob: Offset, val bearingDeg: Double, val magnitude: Double, val heading: Int?)
}

/** A fixed visual state for screenshots; [JoystickPadImpl] then ignores touches and timers. */
@Immutable
internal data class JoystickPreview(
    val bearingDeg: Double = 0.0,
    val magnitude: Double = 0.0,
    val touched: Boolean = magnitude > 0.0,
    val moveMode: Boolean = false,
)

/**
 * Joystick: a [padSize] travel ring (Halo) or four chevrons (Compass) around a knob. Drag the knob;
 * [onInput] receives bearing (0 = north) and magnitude (0..1), and magnitude 0 on release.
 * Holding the knob still for a moment instead hands the drag to [mover] (null: no hold-to-move).
 *
 * When not dragging, the knob shows [bearingDeg]/[magnitude] (hoisted state); while dragging it follows
 * the finger without the caller feeding [onInput] back. A disabled pad has a faded knob and doesn't steer,
 * but can still be moved. A pill above the pad shows [readout] while driving, a coach hint while
 * [showMoveHint], and "Drag to move" in move mode; the pad's bounds include room for it.
 */
@Composable
fun JoystickPad(
    bearingDeg: Double,
    magnitude: Double,
    onInput: (bearingDeg: Double, magnitude: Double) -> Unit,
    modifier: Modifier = Modifier,
    padSize: Dp = JoystickSize.Medium.dp.dp,
    style: JoystickStyle = JoystickStyle.Halo,
    enabled: Boolean = true,
    mover: JoystickMover? = null,
    readout: String? = null,
    showMoveHint: Boolean = false,
) = JoystickPadImpl(bearingDeg, magnitude, onInput, modifier, padSize, style, enabled, mover, readout, showMoveHint)

private val LabelSlot = 14.dp
private const val IDLE_FADE_MILLIS = 2500L
private const val DEMO_COUNT = 3

private enum class PillKind { Move, Hint, Readout }

@Composable
internal fun JoystickPadImpl(
    bearingDeg: Double,
    magnitude: Double,
    onInput: (bearingDeg: Double, magnitude: Double) -> Unit,
    modifier: Modifier = Modifier,
    padSize: Dp = JoystickSize.Medium.dp.dp,
    style: JoystickStyle = JoystickStyle.Halo,
    enabled: Boolean = true,
    mover: JoystickMover? = null,
    readout: String? = null,
    showMoveHint: Boolean = false,
    preview: JoystickPreview? = null,
) {
    val c = HauntTheme.colors
    val density = LocalDensity.current
    val k = padSize.value / JoystickMath.RING
    val footprint = padSize * (JoystickMath.FOOTPRINT / JoystickMath.RING)
    val maxTravel = with(density) { (padSize * (JoystickMath.TRAVEL / JoystickMath.RING)).toPx() }
    val snap = style == JoystickStyle.Compass
    val input by rememberUpdatedState(onInput)
    val currentMover by rememberUpdatedState(mover)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val scope = rememberCoroutineScope()

    // Bearing of the last input sent; the release keeps it, with magnitude 0.
    var lastBearing by remember { mutableDoubleStateOf(bearingDeg) }
    // Knob offset while steering; only read in draw so a drag redraws without recomposing.
    var drag by remember { mutableStateOf<Offset?>(null) }
    var touchedState by remember { mutableStateOf(false) }
    var moveModeState by remember { mutableStateOf(false) }
    var heading by remember { mutableStateOf<Int?>(null) }
    var tapHints by remember { mutableIntStateOf(0) }
    var tapHint by remember { mutableStateOf(false) }

    val previewKnob = preview?.let {
        val p = JoystickMath.toOffset(it.bearingDeg, it.magnitude, maxTravel)
        if (it.moveMode) Offset.Zero else JoystickMath.steer(p.x, p.y, maxTravel, snap).knob
    }
    val touched = preview?.touched ?: touchedState
    val moveMode = preview?.moveMode ?: moveModeState

    val rest = JoystickMath.toOffset(bearingDeg, magnitude, maxTravel)
    val spring = remember { Animatable(rest, Offset.VectorConverter) }
    var released by remember { mutableStateOf<Offset?>(null) }
    val steering = drag != null
    LaunchedEffect(steering, rest, moveModeState) {
        if (steering) return@LaunchedEffect
        released?.let { spring.snapTo(it) }
        released = null
        spring.animateTo(if (moveModeState) Offset.Zero else rest, HauntMotion.bouncy())
    }

    val knobScale by animateFloatAsState(if (moveMode) 1.12f else 1f, HauntMotion.bouncy(), label = "knobScale")
    // Halo rests as just the knob; the ring springs out from under the thumb while steering.
    val ringOut = touched && !moveMode
    val ringScale by animateFloatAsState(if (ringOut) 1f else 0.4f, HauntMotion.bouncy(), label = "ringScale")
    val ringAlpha by animateFloatAsState(if (ringOut) 1f else 0f, HauntMotion.snappy(), label = "ringAlpha")
    val holdScale = remember { Animatable(0.6f) }
    val holdAlpha = remember { Animatable(0f) }
    val pops = remember { List(4) { Animatable(1f) } }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    fun startHold() {
        holdJob?.cancel()
        holdJob = scope.launch {
            holdAlpha.snapTo(1f)
            holdScale.snapTo(0.6f)
            holdScale.animateTo(1f, tween(JoystickMath.HOLD_MILLIS.toInt(), easing = LinearEasing))
        }
    }

    fun hideHold() {
        holdJob?.cancel()
        holdJob = scope.launch { holdAlpha.animateTo(0f, tween(150)) }
    }

    fun pop(h: Int) {
        for (d in 0..3) {
            if (!JoystickMath.isChevronLit(d, h)) continue
            scope.launch {
                pops[d].animateTo(1.3f, tween(60))
                delay(90)
                pops[d].animateTo(1f, HauntMotion.bouncy())
            }
        }
    }

    // Idle fade, held off while the coach hint is up so the demo stays readable.
    var faded by remember { mutableStateOf(false) }
    LaunchedEffect(touched, showMoveHint, preview, style) {
        faded = false
        // A lone Halo dot is small enough already; fading it would just make it hard to find.
        if (touched || showMoveHint || preview != null || style == JoystickStyle.Halo) return@LaunchedEffect
        delay(IDLE_FADE_MILLIS)
        faded = true
    }
    val fade by animateFloatAsState(if (faded) 0.45f else 1f, tween(500), label = "idleFade")

    LaunchedEffect(tapHints) {
        if (tapHints == 0) return@LaunchedEffect
        tapHint = true
        delay(1500)
        tapHint = false
    }

    // First-run coach: replays the hold ring a few times while the pad sits idle.
    var demosPlayed by remember { mutableIntStateOf(0) }
    LaunchedEffect(showMoveHint, touched, preview) {
        if (!showMoveHint || touched || preview != null || mover == null) return@LaunchedEffect
        while (demosPlayed < DEMO_COUNT) {
            delay(900)
            holdAlpha.snapTo(1f)
            holdScale.snapTo(0.6f)
            holdScale.animateTo(1f, tween(JoystickMath.HOLD_MILLIS.toInt(), easing = LinearEasing))
            holdAlpha.animateTo(0f, tween(150))
            demosPlayed++
            delay(IDLE_FADE_MILLIS - 900 - JoystickMath.HOLD_MILLIS - 150)
        }
    }

    val driving by remember(maxTravel, preview) {
        derivedStateOf {
            val d = drag
            preview?.let { it.touched && !it.moveMode && it.magnitude > JoystickMath.DRIVE_MAGNITUDE }
                ?: (touchedState && !moveModeState && d != null && d.getDistance() / maxTravel > JoystickMath.DRIVE_MAGNITUDE)
        }
    }

    val touch = if (preview != null || (!enabled && mover == null)) {
        Modifier
    } else {
        Modifier.pointerInput(enabled, mover != null, maxTravel, snap) {
            val slop = JoystickMath.HOLD_SLOP_DP.dp.toPx()
            val canMove = mover != null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val center = Offset(size.width / 2f, size.height / 2f)
                touchedState = true
                heading = null

                fun steer(p: Offset) {
                    val s = JoystickMath.steer(p.x - center.x, p.y - center.y, maxTravel, snap)
                    drag = s.knob
                    if (s.heading != null && s.heading != heading) {
                        pop(s.heading)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                    heading = s.heading
                    lastBearing = s.bearingDeg
                    input(s.bearingDeg, s.magnitude)
                }

                fun stopSteering() {
                    if (drag == null) return
                    released = drag
                    drag = null
                    input(lastBearing, 0.0)
                }

                var holding = canMove
                var moving = false
                var tapped = false
                try {
                    if (enabled) steer(down.position)
                    if (holding) startHold()
                    var last = down.uptimeMillis
                    while (true) {
                        val event = if (holding) {
                            withTimeoutOrNull(max(1L, JoystickMath.HOLD_MILLIS - (last - down.uptimeMillis))) {
                                awaitPointerEvent()
                            }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            holding = false
                            moving = true
                            hideHold()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            stopSteering()
                            heading = null
                            moveModeState = true
                            currentMover?.onMoveStart()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.uptimeMillis
                        if (!change.pressed) {
                            tapped = holding
                            break
                        }
                        if (change.positionChanged()) change.consume()
                        if (moving) {
                            currentMover?.onMove(change.position - change.previousPosition)
                            continue
                        }
                        val fromDown = change.position - down.position
                        if (holding && JoystickMath.breaksHold(fromDown.x, fromDown.y, slop)) {
                            holding = false
                            hideHold()
                        }
                        if (enabled) steer(change.position)
                    }
                } finally {
                    if (holding) hideHold()
                    if (moving) {
                        moveModeState = false
                        currentMover?.onMoveEnd()
                    } else {
                        stopSteering()
                    }
                    if (tapped) tapHints++
                    heading = null
                    touchedState = false
                }
            }
        }
    }

    val label: Pair<PillKind, String>? = when {
        moveMode -> PillKind.Move to "Drag to move"
        (tapHint || showMoveHint) && !touched -> PillKind.Hint to "Hold to move"
        readout != null && driving -> PillKind.Readout to readout
        else -> null
    }

    Box(
        modifier
            .size(footprint, footprint + LabelSlot)
            .semantics {
                contentDescription = if (enabled) "Joystick. Drag to move." else "Joystick. Press Start to steer."
            },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(footprint)
                .graphicsLayer { alpha = fade }
                .then(touch)
                .drawBehind {
                    val knob = previewKnob ?: drag ?: spring.value
                    val pad = PadDraw(
                        c = c,
                        k = k * this.density,
                        style = style,
                        enabled = enabled,
                        touched = touched,
                        moveMode = moveMode,
                        knob = knob,
                        magnitude = if (touched && !moveMode) knob.getDistance() / maxTravel else 0f,
                        knobScale = knobScale,
                        ringScale = ringScale,
                        ringAlpha = ringAlpha,
                        holdScale = holdScale.value,
                        holdAlpha = holdAlpha.value,
                        pops = FloatArray(4) { pops[it].value },
                    )
                    with(pad) { draw() }
                },
        )
        AnimatedContent(
            targetState = label,
            modifier = Modifier.align(Alignment.TopCenter),
            contentKey = { it?.first },
            transitionSpec = { fadeIn(HauntMotion.snappy()).togetherWith(fadeOut(HauntMotion.snappy())) },
            contentAlignment = Alignment.TopCenter,
            label = "joystickLabel",
        ) { l ->
            if (l != null) LabelPill(l.second, Modifier.widthIn(max = footprint))
        }
    }
}

@Composable
private fun LabelPill(text: String, modifier: Modifier = Modifier) {
    val c = HauntTheme.colors
    Box(
        modifier
            .background(c.glassSolid, HauntShapes.pill)
            .border(1.dp, c.glassBorder, HauntShapes.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = HauntTheme.type.monoSmall, color = c.text, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** One frame of the pad, in px; [k] is px per design unit. */
private class PadDraw(
    val c: HauntColors,
    val k: Float,
    val style: JoystickStyle,
    val enabled: Boolean,
    val touched: Boolean,
    val moveMode: Boolean,
    val knob: Offset,
    val magnitude: Float,
    val knobScale: Float,
    val ringScale: Float,
    val ringAlpha: Float,
    val holdScale: Float,
    val holdAlpha: Float,
    val pops: FloatArray,
) {
    private val driving = touched && !moveMode && magnitude > JoystickMath.DRIVE_MAGNITUDE
    private val shadow = c.markerShadow

    /** Knob colours: a disabled pad (waiting for Start) shows a faded knob. */
    private fun Color.fade() = if (enabled) this else copy(alpha = alpha * 0.35f)

    fun DrawScope.draw() {
        val o = center
        if (style == JoystickStyle.Halo) halo(o) else compass(o)
        if (moveMode) {
            drawCircle(
                c.text.copy(alpha = 0.7f),
                radius = 84 * k,
                center = o,
                style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * k, 5 * k))),
            )
        }
        if (holdAlpha > 0f) {
            drawCircle(c.accent.copy(alpha = holdAlpha), radius = 38 * k * holdScale, center = o, style = Stroke(max(2.5f * k, 1.5f * density)))
        }
    }

    private fun DrawScope.halo(o: Offset) {
        val ringWidth = max(2 * k, 1.5f * density)
        if (ringAlpha > 0f) {
            val ringR = 66 * k * ringScale
            softShadow(o + Offset(0f, density), ringR, ringWidth, 3 * density, shadow.copy(alpha = shadow.alpha * ringAlpha))
            drawCircle(c.text.copy(alpha = ringAlpha), radius = ringR, center = o, style = Stroke(ringWidth))
        }
        if (driving) {
            val band = 5 * k
            val r = (70 * k - band / 2) * ringScale
            val bearing = JoystickMath.toPolar(knob.x, knob.y, 1f).first.toFloat()
            // Round caps add half the band at each end; trim the sweep so the visible arc stays 70°.
            val capDeg = (band / 2 / r) * 180f / PI.toFloat()
            val sweep = 70f - 2 * capDeg
            drawArc(
                c.accent.copy(alpha = ringAlpha),
                startAngle = bearing - 90f - sweep / 2,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(o.x - r, o.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(band, cap = StrokeCap.Round),
            )
        }
        if (touched && !moveMode) {
            drawLine(c.accent, o, o + knob, strokeWidth = 3 * k, cap = StrokeCap.Round)
        }
        val r = 26 * k * knobScale
        softShadow(o + knob + Offset(0f, 4 * density), r, 2 * r, 14 * density, shadow.fade(), filled = true)
        drawCircle(c.accent.fade(), radius = r, center = o + knob)
        drawCircle(c.text.fade(), radius = r - 1.5f * k, center = o + knob, style = Stroke(3 * k))
    }

    private fun DrawScope.compass(o: Offset) {
        val heading = if (driving) JoystickMath.heading(JoystickMath.toPolar(knob.x, knob.y, 1f).first) else null
        val arm = 14 * k
        val h = arm / sqrt(2f)
        val chevron = Path().apply {
            moveTo(-h, h / 2)
            lineTo(0f, -h / 2)
            lineTo(h, h / 2)
        }
        val stroke = 3.5f * k
        for (d in 0..3) {
            val lit = heading != null && JoystickMath.isChevronLit(d, heading)
            rotate(d * 90f, pivot = o) {
                translate(o.x, o.y - 58 * k) {
                    scale(pops[d], pivot = Offset.Zero) {
                        translate(0f, 1 * density) {
                            for (spread in 1..2) {
                                val w = stroke + spread * 1.5f * density
                                drawPath(chevron, shadow.copy(alpha = shadow.alpha * 0.45f), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        drawPath(
                            chevron,
                            if (lit) c.accent else c.text,
                            style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
        val r = 22 * k * knobScale
        val border = 3 * k
        if (driving) {
            softShadow(o + knob + Offset(0f, 3 * density), r, 2 * r, 10 * density, shadow.fade(), filled = true)
            drawCircle(c.accent.fade(), radius = r, center = o + knob)
        } else {
            softShadow(o + knob + Offset(0f, 3 * density), r - border / 2, border, 10 * density, shadow.fade())
        }
        drawCircle(c.text.fade(), radius = r - border / 2, center = o + knob, style = Stroke(border))
    }

    /**
     * Cheap blurred shadow for a ring of [width] (or a disc when [filled]): a radial gradient instead of a
     * blur pass. Like CSS box-shadow, nothing is drawn inside a ring's hole.
     */
    private fun DrawScope.softShadow(at: Offset, radius: Float, width: Float, blur: Float, color: Color, filled: Boolean = false) {
        val edge = if (filled) radius else radius + width / 2
        val outer = edge + blur
        val clear = color.copy(alpha = 0f)
        fun f(px: Float) = (px / outer).coerceIn(0f, 1f)
        val fall = arrayOf(
            f(edge - blur / 3) to color,
            f(edge + blur / 3) to color.copy(alpha = color.alpha * 0.35f),
            1f to clear,
        )
        val stops = if (filled) arrayOf(0f to color, *fall) else arrayOf(0f to clear, f(radius - width / 2) to clear, *fall)
        drawCircle(Brush.radialGradient(*stops, center = at, radius = outer), radius = outer, center = at)
    }
}
