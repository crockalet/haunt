package io.github.crockalet.haunt.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import io.github.crockalet.haunt.ui.state.UiScale

/** The factor [ScaledChrome] applies by default ([UiScale.factor] of the user's setting); 1 = none. */
val LocalChromeScale = staticCompositionLocalOf { 1f }

/**
 * Draws [content] [scale] times its dp / sp size by scaling the density, so px-based things (window
 * insets, root coordinates) stay right. Inside, [LocalChromeScale] is 1, so nesting doesn't scale twice.
 */
@Composable
fun ScaledChrome(scale: Float = LocalChromeScale.current, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val density = remember(base, scale) { if (scale == 1f) base else ScaledDensity(base, scale) }
    CompositionLocalProvider(LocalDensity provides density, LocalChromeScale provides 1f, content = content)
}

/** [base] with [density] scaled; sp still converts through [base], keeping the platform's font scaling curve. */
private class ScaledDensity(private val base: Density, private val scale: Float) : Density {
    override val density: Float get() = base.density * scale
    override val fontScale: Float get() = base.fontScale

    override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }

    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }

    override fun equals(other: Any?): Boolean = other is ScaledDensity && other.base == base && other.scale == scale

    override fun hashCode(): Int = 31 * base.hashCode() + scale.hashCode()
}
