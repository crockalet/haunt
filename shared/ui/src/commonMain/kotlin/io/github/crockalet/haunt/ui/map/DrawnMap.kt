package io.github.crockalet.haunt.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.components.LocationMarker
import io.github.crockalet.haunt.ui.components.RouteEndpoint
import io.github.crockalet.haunt.ui.state.Geo
import io.github.crockalet.haunt.ui.state.MapContent
import io.github.crockalet.haunt.ui.state.SampleData
import io.github.crockalet.haunt.ui.theme.HauntTheme
import kotlin.math.roundToInt

/**
 * Illustrative, offline map (the mockups' drawn Shibuya scene) with real overlays projected on
 * top. Used on desktop/JVM (screenshots, previews) and as the fallback when no map engine is
 * available. Projection: local tangent plane around [MapContent.camera], [metersPerDp] scale,
 * camera at 50% x / 45% y of the viewport.
 */
@Composable
fun DrawnMap(
    content: MapContent,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
    metersPerDp: Double = SampleData.MetersPerDp,
) {
    val c = HauntTheme.colors
    val sans = HauntTheme.type.sans
    val measurer = rememberTextMeasurer()
    val longPress by rememberUpdatedState(onLongPress)
    val camera = content.camera ?: SampleData.ShibuyaCrossing

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val anchorX = w * 0.5f
        val anchorY = h * 0.45f
        fun project(p: LatLng): Pair<Dp, Dp> {
            val (e, n) = Geo.toLocal(camera, p)
            return (anchorX + (e / metersPerDp).dp) to (anchorY - (n / metersPerDp).dp)
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(camera, metersPerDp) {
                    detectTapGestures(onLongPress = { o ->
                        val e = (o.x / density - anchorX.value) * metersPerDp
                        val n = -(o.y / density - anchorY.value) * metersPerDp
                        longPress(Geo.offset(camera, e, n))
                    })
                },
        ) {
            drawRect(c.map)
            // Scene from the mockups, in a 390-wide design frame, scaled to the viewport width.
            val s = size.width / 390.dp.toPx()
            scale(s * density, s * density, pivot = Offset.Zero) {
                drawScene(c.park, c.water, c.minorRoad, c.casing, c.majorRoad)
            }
            val label = TextStyle(fontFamily = sans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.mapLabel)
            listOf("Yoyogi Park" to Offset(258f, 238f), "Shibuya" to Offset(186f, 366f), "Shibuya River" to Offset(40f, 688f)).forEach { (t, o) ->
                drawText(measurer, t, topLeft = Offset(o.x * s * density, o.y * s * density), style = label)
            }

            // Overlays.
            fun toPx(p: LatLng): Offset = project(p).let { (x, y) -> Offset(x.toPx(), y.toPx()) }
            val accent = c.accent
            if (content.route.size >= 2) {
                drawPolyline(content.route.map(::toPx), accent.copy(alpha = 0.45f), 6.dp.toPx(), dotted = true)
            }
            if (content.traveled.size >= 2) {
                drawPolyline(content.traveled.map(::toPx), accent, 6.dp.toPx(), dotted = false)
            }
            if (content.trail.size >= 2) {
                drawPolyline(content.trail.map(::toPx), accent.copy(alpha = 0.5f), 5.dp.toPx(), dotted = true, gap = 10f)
            }
        }

        if (content.route.isNotEmpty()) {
            Endpoint(project(content.route.first()), start = true)
            if (content.route.size >= 2) Endpoint(project(content.route.last()), start = false)
        }
        content.pending?.let { Endpoint(project(it), start = false) }
        content.fix?.let { fix ->
            val (x, y) = project(fix)
            Box(Modifier.centeredAt(x, y)) {
                LocationMarker(showHalo = content.showHalo, moving = content.moving)
            }
        }
    }
}

@Composable
private fun Endpoint(at: Pair<Dp, Dp>, start: Boolean) {
    Box(Modifier.centeredAt(at.first, at.second)) { RouteEndpoint(start) }
}

/** Places the child so its centre sits at ([x], [y]) in the parent. */
internal fun Modifier.centeredAt(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) {
        p.place((x.toPx() - p.width / 2f).roundToInt(), (y.toPx() - p.height / 2f).roundToInt())
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, color: Color, width: Float, dotted: Boolean, gap: Float = 11f) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path, color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(1f * density, gap * density)) else null,
        ),
    )
}

private val waterPath by lazy {
    PathParser().parsePathString("M0 640 C90 615 150 670 230 652 S350 625 390 640 V730 C310 716 240 748 150 734 S50 716 0 730 Z").toPath()
}
private val minorRoads = listOf(
    "M0 120 L390 90", "M0 230 L390 190", "M0 480 L390 430", "M0 570 L390 540",
    "M40 0 L70 844", "M220 0 L250 844", "M320 0 L340 844", "M0 800 L390 780",
).map { PathParser().parsePathString(it).toPath() }
private val majorRoads = listOf(
    "M-10 360 L400 310", "M130 -10 L172 860", "M-10 610 C120 585 260 557 400 505",
).map { PathParser().parsePathString(it).toPath() }

/** Draws the mockup scene in design units (1 unit = 1 dp before scaling). */
private fun DrawScope.drawScene(park: Color, water: Color, minor: Color, casing: Color, major: Color) {
    // In this scope 1 unit == 1 px; the caller scaled by density so units are dp.
    drawRoundRect(park, topLeft = Offset(232f, 150f), size = Size(140f, 190f), cornerRadius = CornerRadius(30f))
    drawPath(waterPath, water)
    minorRoads.forEach { drawPath(it, minor, style = Stroke(6f, cap = StrokeCap.Round)) }
    majorRoads.forEach { drawPath(it, casing, style = Stroke(15f, cap = StrokeCap.Round)) }
    majorRoads.forEach { drawPath(it, major, style = Stroke(11f, cap = StrokeCap.Round)) }
}

