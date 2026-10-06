package io.github.crockalet.haunt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.theme.HauntTheme

private const val GhostPathData =
    "M22 2C11 2 4 10 4 21V48L10 43L16 48L22 43L28 48L34 43L40 48V21C40 10 33 2 22 2Z"

private val ghostPath: Path by lazy { PathParser().parsePathString(GhostPathData).toPath() }

/** The ghost glyph's outline as a shape (44×52 viewBox scaled to the layout size). */
private val GhostShape = GenericShape { size, _ ->
    addPath(ghostPath)
    transform(Matrix().apply { scale(size.width / 44f, size.height / 52f) })
}

/**
 * Haunt's location marker: an accent ghost with a white outline and two eyes.
 * [width] 44dp in Pin mode, 38dp while moving (from the mockups).
 */
@Composable
fun GhostMarker(
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    contentDescription: String = "Fake location",
    strokeWidth: Float = 3f,
    shadowOffset: Dp = 6.dp,
) {
    val c = HauntTheme.colors
    val height = width * (52f / 44f)
    Canvas(
        modifier
            .size(width, height)
            .dropShadow(GhostShape, Shadow(radius = shadowOffset * 1.67f, color = c.markerShadow, offset = DpOffset(0.dp, shadowOffset)))
            .semantics { this.contentDescription = contentDescription },
    ) {
        val sx = size.width / 44f
        scale(sx, sx, pivot = Offset.Zero) {
            drawPath(ghostPath, c.accent)
            drawPath(ghostPath, Color.White, style = Stroke(width = strokeWidth))
            drawCircle(c.onAccent, radius = 3f, center = Offset(16f, 21f))
            drawCircle(c.onAccent, radius = 3f, center = Offset(28f, 21f))
        }
    }
}

/**
 * Soft accuracy halo drawn under the marker: a [radius] disc in the selection tint plus a
 * 24dp outer ring in the halo tint (from the Pin mockup: 100dp disc, 24dp spread).
 */
@Composable
fun AccuracyHalo(modifier: Modifier = Modifier, radius: Dp = 50.dp, ring: Dp = 24.dp) {
    val c = HauntTheme.colors
    Canvas(modifier.size((radius + ring) * 2)) {
        drawCircle(c.halo)
        drawCircle(c.selected, radius = radius.toPx())
    }
}

/** Halo + ghost, centred on the same point. */
@Composable
fun LocationMarker(
    modifier: Modifier = Modifier,
    showHalo: Boolean = true,
    haloRadius: Dp = 50.dp,
    moving: Boolean = false,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (showHalo) AccuracyHalo(radius = haloRadius)
        if (moving) {
            GhostMarker(width = 38.dp, contentDescription = "Fake location, moving", shadowOffset = 4.dp)
        } else {
            GhostMarker()
        }
    }
}

/** Route endpoint dot: filled start (accent, white ring) or hollow end (white, accent ring). */
@Composable
fun RouteEndpoint(start: Boolean, modifier: Modifier = Modifier) {
    val c = HauntTheme.colors
    Canvas(modifier.size(26.dp)) {
        val r = 11.dp.toPx()
        if (start) {
            drawCircle(c.accent, r)
            drawCircle(Color.White, r, style = Stroke(3.dp.toPx()))
        } else {
            drawCircle(Color.White, r)
            drawCircle(c.accent, r, style = Stroke(4.dp.toPx()))
        }
    }
}

