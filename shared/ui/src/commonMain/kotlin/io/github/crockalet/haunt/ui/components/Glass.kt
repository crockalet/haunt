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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme

/**
 * The backdrop glass surfaces blur. Provide it around the map's `hazeSource`. When null, glass
 * falls back to a solid, near-opaque surface.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** How [GlassSurface] renders its backdrop. */
enum class GlassMode {
    /** Frosted blur of [LocalHazeState] (CSS `backdrop-filter: blur(22px) saturate(1.7)`). */
    Blur,

    /** Plain translucent glass tint, used over an already-blurred veil. */
    Tint,

    /** Near-opaque solid fallback when blur is unavailable. */
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
): Modifier = drawWithCache {
    val painter = DropShadowPainter(shape, Shadow(radius = blur, color = color, offset = DpOffset(0.dp, offsetY)))
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawBehind {
        clipPath(path, ClipOp.Difference) {
            with(painter) { draw(size) }
        }
    }
}

private val Saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.7f) })

/**
 * Frosted glass surface: translucent tint over a blurred backdrop (Haze), a 1dp light border
 * and a soft outer shadow. Falls back to a solid surface when there is no backdrop.
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
            fallbackColorEffect(HazeColorEffect.tint(colors.glassFallback))
        }
    }
    val surface = when (mode) {
        GlassMode.Blur -> Modifier.hazeBlur(HazeInput.Sources(haze!!), style)
        GlassMode.Tint -> Modifier.background(colors.glass)
        GlassMode.Solid -> Modifier.background(colors.glassFallback)
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
 * of the map plus a scrim. Glass inside it switches to [GlassMode.Tint].
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
            fallbackColorEffect(HazeColorEffect.tint(colors.scrim.copy(alpha = 0.94f)))
        }
    }
    Box(modifier) {
        Box(
            Modifier.matchParentSize().then(
                if (haze != null) Modifier.hazeBlur(HazeInput.Sources(haze), style)
                else Modifier.background(colors.map).background(colors.scrim),
            ),
        )
        CompositionLocalProvider(LocalGlassMode provides GlassMode.Tint) {
            content()
        }
    }
}
