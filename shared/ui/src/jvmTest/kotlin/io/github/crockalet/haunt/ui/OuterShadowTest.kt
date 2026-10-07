package io.github.crockalet.haunt.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.OuterShadowCache
import io.github.crockalet.haunt.ui.components.ShadowAxis
import io.github.crockalet.haunt.ui.components.shadowAxis
import io.github.crockalet.haunt.ui.theme.HauntShapes
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OuterShadowTest {
    private val color = Color(0xFF3355AA).copy(alpha = 0.3f)

    @Test
    fun shortAxisIsNotStretched() {
        assertEquals(ShadowAxis(100, 0, 0), shadowAxis(100, corner = 20, reach = 40))
        assertEquals(ShadowAxis(121, 0, 0), shadowAxis(121, corner = 20, reach = 40))
    }

    @Test
    fun longAxisStretchesTheMiddleLine() {
        val axis = shadowAxis(500, corner = 20, reach = 40)
        assertEquals(ShadowAxis(121, 60, 379), axis)
        val pad = 30
        val slices = axis.slices(pad)
        assertEquals(axis.render + 2 * pad, slices.sumOf { it.srcLength })
        assertEquals(500 + 2 * pad, slices.sumOf { it.dstLength })
        slices.zipWithNext { a, b ->
            assertEquals(a.src + a.srcLength, b.src)
            assertEquals(a.dst + a.dstLength, b.dst)
        }
    }

    @Test
    fun matchesTheDirectShadow() {
        val cases = listOf(
            Triple(HauntShapes.card, Size(360f, 230f), 2f),
            Triple(HauntShapes.card, Size(120f, 90f), 2f),
            Triple(HauntShapes.pill, Size(300f, 64f), 3f),
            Triple(HauntShapes.pill, Size(52f, 52f), 2f),
            Triple(HauntShapes.card, Size(380f, 260f), 2.75f),
            Triple(RectangleShape, Size(240f, 200f), 2f),
            // Corners scale with size, so this falls back to a full render.
            Triple(RoundedCornerShape(percent = 20), Size(300f, 200f), 2f),
        )
        for ((shape, sizeDp, density) in cases) {
            val expected = render(sizeDp, density) { direct(shape) }
            val cache = OuterShadowCache(shape, color, 30.dp, 10.dp)
            val actual = render(sizeDp, density) { with(cache) { drawShadow() } }
            assertTrue(maxAlpha(expected) > 0.1f, "no shadow drawn for $shape")
            val diff = maxDiff(expected, actual)
            assertTrue(diff <= 2, "$shape $sizeDp @${density}x differs by $diff/255")
        }
    }

    @Test
    fun resizingAStretchableShadowKeepsTheBitmap() {
        val cache = OuterShadowCache(HauntShapes.card, color, 30.dp, 10.dp)
        for (h in 240..300 step 7) render(Size(360f, h.toFloat()), 2f) { with(cache) { drawShadow() } }
        assertEquals(1, cache.renders)
    }

    /** The previous implementation: the painter's blur, clipped to outside the shape. */
    private fun DrawScope.direct(shape: Shape) {
        val painter = DropShadowPainter(shape, Shadow(radius = 30.dp, color = color, offset = DpOffset(0.dp, 10.dp)))
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@direct)) }
        clipPath(path, ClipOp.Difference) { with(painter) { draw(size) } }
    }

    /** Draws [block] for an element of [sizeDp] with room for its shadow around it. */
    private fun render(sizeDp: Size, density: Float, block: DrawScope.() -> Unit): ImageBitmap {
        val margin = (60 * density).toInt()
        val w = (sizeDp.width * density).toInt()
        val h = (sizeDp.height * density).toInt()
        val bitmap = ImageBitmap(w + 2 * margin, h + 2 * margin)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            translate(margin.toFloat(), margin.toFloat()) { block() }
        }
        return bitmap
    }

    private fun maxAlpha(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        var alpha = 0f
        for (y in 0 until image.height) for (x in 0 until image.width) alpha = max(alpha, pixels[x, y].alpha)
        return alpha
    }

    private fun maxDiff(a: ImageBitmap, b: ImageBitmap): Int {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var worst = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                val channels = listOf(
                    ca.alpha - cb.alpha,
                    ca.red * ca.alpha - cb.red * cb.alpha,
                    ca.green * ca.alpha - cb.green * cb.alpha,
                    ca.blue * ca.alpha - cb.blue * cb.alpha,
                )
                worst = max(worst, channels.maxOf { (abs(it) * 255).toInt() })
            }
        }
        return worst
    }
}
