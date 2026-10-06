package io.github.crockalet.haunt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Theme preference: follow the system, or force light / dark. */
enum class ThemeMode { System, Light, Dark }

/** Corner radii used across the Glass UI (from the mockups). */
@Immutable
object HauntShapes {
    val pill = RoundedCornerShape(percent = 50)
    val card = RoundedCornerShape(30.dp)
    val sheet = RoundedCornerShape(26.dp)
    val list = RoundedCornerShape(24.dp)
    val listSmall = RoundedCornerShape(22.dp)
    val tile = RoundedCornerShape(16.dp)
    val inset = RoundedCornerShape(14.dp)
}

/** Current content colour, like Material's LocalContentColor but ours. */
val LocalContentColor = compositionLocalOf { Color.Black }

/** Current text style for [io.github.crockalet.haunt.ui.components.Text]. */
val LocalTextStyle = compositionLocalOf { TextStyle.Default }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.System }

@Composable
fun HauntTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkHauntColors else LightHauntColors
    val sans = plusJakartaSans()
    val mono = jetBrainsMono()
    val typography = remember(sans, mono) { HauntTypography(sans, mono) }
    val selection = remember(colors) {
        TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.3f))
    }
    CompositionLocalProvider(
        LocalThemeMode provides mode,
        LocalHauntColors provides colors,
        LocalHauntTypography provides typography,
        LocalContentColor provides colors.text,
        LocalTextStyle provides typography.body,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}

/** Shorthand accessors: `HauntTheme.colors`, `HauntTheme.type`. */
object HauntTheme {
    val colors: HauntColors
        @Composable get() = LocalHauntColors.current
    val type: HauntTypography
        @Composable get() = LocalHauntTypography.current
    val shapes: HauntShapes get() = HauntShapes
}
