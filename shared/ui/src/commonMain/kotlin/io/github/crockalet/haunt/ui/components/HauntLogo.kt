package io.github.crockalet.haunt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.crockalet.haunt.ui.theme.HauntColors
import io.github.crockalet.haunt.ui.theme.HauntTheme

// Logo 3a "Big Glass Ghost", in the design's 100×100 units (docs/brand/icon-3a.svg).
private fun path(data: String): Path = PathParser().parsePathString(data).toPath()

private val ghost by lazy { path("M30 48a20 20 0 0 1 40 0v28l-6.67-5-6.66 5-6.67-5-6.67 5-6.66-5L30 76z") }
private val park by lazy { path("M-5-5H48C44 18 24 30-5 34z") }
private val water by lazy { path("M105 60C82 64 66 82 62 105H105z") }
private val trail by lazy { path("M56 40Q62 30 68 28") }
private val pin by lazy { path("M78 12a10 10 0 0 1 10 10c0 7-10 18-10 18s-10-11-10-18a10 10 0 0 1 10-10z") }

private val IconShape = RoundedCornerShape(23)

private fun DrawScope.ghostEyes(c: HauntColors) {
    drawOval(c.muted, topLeft = Offset(39.6f, 43f), size = Size(6.8f, 10f))
    drawOval(c.muted, topLeft = Offset(53.6f, 43f), size = Size(6.8f, 10f))
}

/** The app icon drawn with the current theme's palette (the launcher icon is its light version). */
@Composable
fun HauntAppIcon(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val c = HauntTheme.colors
    val unit = size / 100
    Canvas(
        modifier
            .size(size)
            .dropShadow(IconShape, Shadow(radius = unit * 30, color = c.shadow, offset = DpOffset(0.dp, unit * 14)))
            .clip(IconShape)
            .clearAndSetSemantics { },
    ) {
        scale(this.size.width / 100f, pivot = Offset.Zero) {
            drawRect(c.map, size = Size(100f, 100f))
            drawPath(park, c.park)
            drawPath(water, c.water)
            drawLine(c.majorRoad, Offset(-5f, 50f), Offset(105f, 28f), strokeWidth = 6f)
            drawLine(c.majorRoad, Offset(46f, -5f), Offset(56f, 105f), strokeWidth = 4f)
            withTransform({
                translate(40f, 60f)
                scale(0.78f, 0.78f, pivot = Offset.Zero)
                translate(-50f, -52f)
            }) {
                drawPath(ghost, c.glassFallback)
                drawPath(ghost, c.divider, style = Stroke(width = 2.6f))
                ghostEyes(c)
            }
            drawPath(
                trail, c.accent,
                style = Stroke(3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.5f))),
            )
            drawPath(pin, c.accent)
            drawCircle(c.majorRoad, radius = 3.5f, center = Offset(78f, 22f))
        }
    }
}

/** Wordmark lockup: glass pill with the ghost outline, "haunt" and the accent dot. */
@Composable
fun HauntWordmark(modifier: Modifier = Modifier) {
    val c = HauntTheme.colors
    GlassSurface(modifier.clearAndSetSemantics { contentDescription = "Haunt" }) {
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(17.dp, 20.dp)) {
                // The glyph's viewBox is 27 25 46 54.
                scale(size.width / 46f, pivot = Offset.Zero) {
                    translate(-27f, -25f) {
                        drawPath(ghost, c.glassFallback)
                        drawPath(ghost, c.muted, style = Stroke(width = 4f, join = StrokeJoin.Round))
                        ghostEyes(c)
                    }
                }
            }
            Text(
                "haunt",
                style = HauntTheme.type.bodyStrong.copy(fontSize = 30.sp, letterSpacing = (-0.8).sp),
                color = c.text,
            )
            Dot(c.accent, size = 20.dp)
        }
    }
}
