package io.github.crockalet.haunt.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.crockalet.haunt.ui.generated.Res
import io.github.crockalet.haunt.ui.generated.jetbrains_mono_medium
import io.github.crockalet.haunt.ui.generated.jetbrains_mono_regular
import io.github.crockalet.haunt.ui.generated.plus_jakarta_sans_bold
import io.github.crockalet.haunt.ui.generated.plus_jakarta_sans_medium
import io.github.crockalet.haunt.ui.generated.plus_jakarta_sans_regular
import io.github.crockalet.haunt.ui.generated.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

/** Plus Jakarta Sans (UI) — bundled, OFL. */
@Composable
fun plusJakartaSans(): FontFamily = FontFamily(
    Font(Res.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

/** JetBrains Mono (coordinates and data) — bundled, OFL. */
@Composable
fun jetBrainsMono(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Type scale lifted from the mockups (px → sp 1:1). */
@Immutable
data class HauntTypography(
    val sans: FontFamily,
    val mono: FontFamily,
) {
    private fun s(size: Float, weight: FontWeight, spacing: Float = 0f, lineHeight: Float? = null) = TextStyle(
        fontFamily = sans,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
        lineHeight = lineHeight?.em ?: TextStyle.Default.lineHeight,
    )

    private fun m(size: Float, weight: FontWeight, spacing: Float = 0f) = TextStyle(
        fontFamily = mono,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
    )

    /** Screen titles ("Library", "Settings"). */
    val display = s(28f, FontWeight.Bold, -0.6f)
    /** Onboarding headline. */
    val headline = s(26f, FontWeight.Bold, -0.6f, 1.2f)
    /** Details card title. */
    val title = s(20f, FontWeight.Bold, -0.3f)
    val titleSmall = s(17f, FontWeight.Bold)
    val bodyLarge = s(16f, FontWeight.Bold)
    val body = s(15f, FontWeight.Normal)
    val bodyStrong = s(15f, FontWeight.SemiBold)
    val button = s(15f, FontWeight.Bold)
    val paragraph = s(15f, FontWeight.Normal, lineHeight = 1.5f)
    val paragraphSmall = s(14f, FontWeight.Normal, lineHeight = 1.5f)
    val tab = s(14f, FontWeight.SemiBold)
    val label = s(13f, FontWeight.SemiBold)
    val caption = s(13f, FontWeight.Normal)
    val captionStrong = s(13f, FontWeight.Medium)
    val section = s(13f, FontWeight.Bold)
    val small = s(12f, FontWeight.SemiBold)
    val smallRegular = s(12f, FontWeight.Normal)
    val badge = s(12f, FontWeight.Bold)

    val monoLarge = m(21f, FontWeight.Medium, -0.3f)
    val monoValue = m(15f, FontWeight.Medium)
    val monoValueSmall = m(14f, FontWeight.Medium)
    val coords = m(13f, FontWeight.Normal)
    val monoMedium = m(13f, FontWeight.Medium)
    val monoCode = m(12.5f, FontWeight.Normal)
    val monoSmall = m(12f, FontWeight.Normal)
}

val LocalHauntTypography = staticCompositionLocalOf {
    HauntTypography(FontFamily.SansSerif, FontFamily.Monospace)
}
