package io.github.crockalet.haunt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The backdrop glass surfaces blur. Provide it around the map's `hazeSource`. When null, there is
 * no live blur and glass uses the denser [GlassMode.Solid] tint.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** How [GlassSurface] renders its backdrop. */
enum class GlassMode {
    /** Frosted blur of [LocalHazeState] (CSS `backdrop-filter: blur(22px) saturate(1.7)`). */
    Blur,

    /** Plain translucent glass tint, used over an already-blurred veil. */
    Tint,

    /** Denser tint (`glassSolid`) when there is no live blur behind the glass. */
    Solid,
}

val LocalGlassMode = staticCompositionLocalOf { GlassMode.Blur }

/**
 * Drop shadow drawn only outside [shape], like CSS `box-shadow` (which never paints under the
 * element). Keeps translucent glass from going muddy.
 */
fun Modifier.outerShadow(
    shape: Shape,
    color: Color,
    blur: Dp = 30.dp,
    offsetY: Dp = 10.dp,
): Modifier = this then OuterShadowElement(shape, color, blur, offsetY)

private data class OuterShadowElement(
    val shape: Shape,
    val color: Color,
    val blur: Dp,
    val offsetY: Dp,
) : ModifierNodeElement<OuterShadowNode>() {
    override fun create() = OuterShadowNode(OuterShadowCache(shape, color, blur, offsetY))

    override fun update(node: OuterShadowNode) {
        if (!node.cache.isFor(shape, color, blur, offsetY)) node.cache = OuterShadowCache(shape, color, blur, offsetY)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "outerShadow"
        properties["shape"] = shape
        properties["color"] = color
        properties["blur"] = blur
        properties["offsetY"] = offsetY
    }
}

private class OuterShadowNode(var cache: OuterShadowCache) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        with(cache) { drawShadow() }
        drawContent()
    }
}

/**
 * Renders an [outerShadow] once and redraws the bitmap. Where the shape has uniform corners, the
 * bitmap is a nine-patch: it's rendered for a small box and its middle row and column are
 * stretched, so resizing (e.g. a size animation) never re-blurs on the CPU.
 */
internal class OuterShadowCache(
    private val shape: Shape,
    private val color: Color,
    private val blur: Dp,
    private val offsetY: Dp,
) {
    private var cachedSize = Size.Unspecified
    private var cachedDensity = 0f
    private var cachedDirection: LayoutDirection? = null
    private var clip: Path? = null
    private var xAxis = ShadowAxis(0, 0, 0)
    private var yAxis = ShadowAxis(0, 0, 0)
    private var xSlices = emptyList<ShadowSlice>()
    private var ySlices = emptyList<ShadowSlice>()
    private var pad = 0
    private var image: ImageBitmap? = null

    // The bitmap is an alpha mask (a quarter of the memory of ARGB); this restores the colour.
    private val tint = ColorFilter.tint(color.copy(alpha = 1f))

    /** How many times the blurred bitmap was rendered. */
    internal var renders = 0
        private set

    fun isFor(shape: Shape, color: Color, blur: Dp, offsetY: Dp) =
        shape == this.shape && color == this.color && blur == this.blur && offsetY == this.offsetY

    fun DrawScope.drawShadow() {
        if (size.width < 1f || size.height < 1f) return
        if (size != cachedSize || density != cachedDensity || layoutDirection != cachedDirection) prepare()
        val bitmap = image ?: return
        clipPath(clip!!, ClipOp.Difference) {
            translate(-pad.toFloat(), (offsetY.roundToPx() - pad).toFloat()) {
                for (x in xSlices) {
                    for (y in ySlices) {
                        drawImage(
                            bitmap,
                            srcOffset = IntOffset(x.src, y.src),
                            srcSize = IntSize(x.srcLength, y.srcLength),
                            dstOffset = IntOffset(x.dst, y.dst),
                            dstSize = IntSize(x.dstLength, y.dstLength),
                            filterQuality = FilterQuality.None,
                            colorFilter = tint,
                        )
                    }
                }
            }
        }
    }

    private fun DrawScope.prepare() {
        val outline = shape.createOutline(size, layoutDirection, this)
        clip = Path().apply { addOutline(outline) }
        val blurPx = blur.toPx()
        val newPad = ceil(blurPx).toInt()
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val corner = outline.uniformCorner()
        var x = ShadowAxis(w, 0, 0)
        var y = ShadowAxis(h, 0, 0)
        if (corner != null) {
            // Skia blurs with a kernel reaching 3 sigma; sigma = 0.57735 * radius + 0.5.
            val reach = ceil(3f * (0.57735f * blurPx + 0.5f)).toInt() + 1
            val edge = ceil(corner).toInt()
            x = shadowAxis(w, edge, reach)
            y = shadowAxis(h, edge, reach)
            // A shape whose corners scale with its size (e.g. a percent radius) can't be stretched.
            val small = shape.createOutline(Size(x.render.toFloat(), y.render.toFloat()), layoutDirection, this)
            if (small.uniformCorner() != corner) {
                x = ShadowAxis(w, 0, 0)
                y = ShadowAxis(h, 0, 0)
            }
        }
        val rebuild = image == null || x.render != xAxis.render || y.render != yAxis.render || newPad != pad ||
            density != cachedDensity || layoutDirection != cachedDirection
        xAxis = x
        yAxis = y
        pad = newPad
        xSlices = x.slices(newPad)
        ySlices = y.slices(newPad)
        cachedSize = size
        cachedDensity = density
        cachedDirection = layoutDirection
        if (rebuild) image = renderShadow(x.render, y.render, newPad, Density(density, fontScale), layoutDirection)
    }

    private fun renderShadow(width: Int, height: Int, pad: Int, density: Density, layoutDirection: LayoutDirection): ImageBitmap {
        renders++
        val bitmap = ImageBitmap(width + 2 * pad, height + 2 * pad, ImageBitmapConfig.Alpha8)
        val painter = DropShadowPainter(shape, Shadow(radius = blur, color = color))
        CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            translate(pad.toFloat(), pad.toFloat()) {
                with(painter) { draw(Size(width.toFloat(), height.toFloat())) }
            }
        }
        return bitmap
    }
}

/** Corner radius in px when all four corners share one circular radius, else null. */
private fun Outline.uniformCorner(): Float? = when (this) {
    is Outline.Rectangle -> 0f
    is Outline.Rounded -> roundRect.topLeftCornerRadius.x.takeIf { r ->
        listOf(roundRect.topLeftCornerRadius, roundRect.topRightCornerRadius, roundRect.bottomRightCornerRadius, roundRect.bottomLeftCornerRadius)
            .all { it.x == r && it.y == r }
    }
    is Outline.Generic -> null
}

/**
 * One axis of the shadow bitmap: it is rendered [render] px long (plus padding) and the 1px line
 * at [seam] (in the unpadded box) is stretched by [extra] px to reach the element's length.
 */
internal data class ShadowAxis(val render: Int, val seam: Int, val extra: Int) {
    fun slices(pad: Int): List<ShadowSlice> {
        val total = render + 2 * pad
        if (extra == 0) return listOf(ShadowSlice(0, total, 0, total))
        val cut = pad + seam
        return listOf(
            ShadowSlice(0, cut, 0, cut),
            ShadowSlice(cut, 1, cut, 1 + extra),
            ShadowSlice(cut + 1, total - cut - 1, cut + 1 + extra, total - cut - 1),
        )
    }
}

/** Source span [src, src + srcLength) of the bitmap drawn to [dst, dst + dstLength). */
internal data class ShadowSlice(val src: Int, val srcLength: Int, val dst: Int, val dstLength: Int)

/**
 * Plans one axis of [length] px for a shape with [corner] px corners and a blur reaching [reach] px:
 * the middle line of a box `2 * (corner + reach) + 1` long is untouched by the corners, so it can
 * be stretched. Shorter axes are rendered at full length.
 */
internal fun shadowAxis(length: Int, corner: Int, reach: Int): ShadowAxis {
    val core = 2 * (corner + reach) + 1
    return if (length > core) ShadowAxis(core, corner + reach, length - core) else ShadowAxis(length, 0, 0)
}

private val Saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.7f) })

/**
 * Frosted glass surface: translucent tint over a blurred backdrop (Haze), a 1dp light border
 * and a soft outer shadow. Without a backdrop to blur it uses a denser tint instead.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = HauntShapes.pill,
    shadow: Boolean = true,
    blurRadius: Dp = 22.dp,
    border: Boolean = true,
    borderColor: Color? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HauntTheme.colors
    val haze = LocalHazeState.current
    val mode = LocalGlassMode.current.let { if (it == GlassMode.Blur && haze == null) GlassMode.Solid else it }
    val style = remember(colors, blurRadius) {
        HazeBlurStyle {
            blurRadius(blurRadius)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.colorFilter(Saturate), HazeColorEffect.tint(colors.glass)))
            fallbackColorEffect(HazeColorEffect.tint(colors.glassSolid))
        }
    }
    val surface = when (mode) {
        GlassMode.Blur -> Modifier.hazeBlur(HazeInput.Sources(haze!!), style)
        GlassMode.Tint -> Modifier.background(colors.glass)
        GlassMode.Solid -> Modifier.background(colors.glassSolid)
    }
    Box(
        modifier
            .then(if (shadow) Modifier.outerShadow(shape, colors.shadow) else Modifier)
            .clip(shape)
            .then(surface)
            .then(if (border) Modifier.border(1.dp, borderColor ?: colors.glassBorder, shape) else Modifier),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Full-screen veil behind overlay screens (Search, Library, Settings, Onboarding): a strong blur
 * of the map plus a scrim, or a denser scrim without live blur. Glass inside it switches to
 * [GlassMode.Tint].
 */
@Composable
fun GlassVeil(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HauntTheme.colors
    val haze = LocalHazeState.current
    val style = remember(colors, blurRadius) {
        HazeBlurStyle {
            blurRadius(blurRadius)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(colors.scrim)))
            fallbackColorEffect(HazeColorEffect.tint(colors.scrimSolid))
        }
    }
    Box(modifier) {
        Box(
            Modifier.matchParentSize().then(
                if (haze != null) Modifier.hazeBlur(HazeInput.Sources(haze), style)
                else Modifier.background(colors.scrimSolid),
            ),
        )
        CompositionLocalProvider(LocalGlassMode provides GlassMode.Tint) {
            content()
        }
    }
}
