package io.github.crockalet.haunt.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the Glass design. Values are copied from the approved mockups
 * (`StyleGlass.dc.html` and friends, `tokens()`), one set for light and one for dark.
 */
@Immutable
data class HauntColors(
    val isDark: Boolean,
    // Map palette (used by the drawn fallback map and the MapLibre overlays).
    val map: Color,
    val park: Color,
    val water: Color,
    val minorRoad: Color,
    val casing: Color,
    val majorRoad: Color,
    val mapLabel: Color,
    // Surfaces.
    val glass: Color,
    /** Denser [glass] for when there is no live blur behind it, so text stays legible over a sharp map. */
    val glassSolid: Color,
    /** Opaque-ish glass for controls that must not show the map through them (e.g. a disabled button). */
    val glassFallback: Color,
    val glassBorder: Color,
    val scrim: Color,
    /** Denser [scrim] for the veil behind sheets when there is no live blur. */
    val scrimSolid: Color,
    val tile: Color,
    val tabOn: Color,
    val hair: Color,
    val divider: Color,
    val track: Color,
    val switchTrack: Color,
    val shadow: Color,
    val markerShadow: Color,
    // Content.
    val text: Color,
    val muted: Color,
    // Accent (location, active state and the main action only).
    val accent: Color,
    val onAccent: Color,
    val selected: Color,
    val selectedContent: Color,
    val halo: Color,
    /** Error dot in notices and the map's "not faking" status dot (the only non-accent hue outside the map). */
    val danger: Color = Color(0xFFE5484D),
)

val LightHauntColors = HauntColors(
    isDark = false,
    map = Color(0xFFF1F1EF),
    park = Color(0xFFE2EADC),
    water = Color(0xFFD6E3EC),
    minorRoad = Color(0xFFFFFFFF),
    casing = Color(0xFFE2E2DF),
    majorRoad = Color(0xFFFFFFFF),
    mapLabel = Color(0xFF8D8D94),
    glass = Color(255, 255, 255, (0.62f * 255).toInt()),
    glassSolid = Color(255, 255, 255, (0.76f * 255).toInt()),
    glassFallback = Color(0xF2F7F7F8),
    glassBorder = Color(255, 255, 255, (0.75f * 255).toInt()),
    scrim = Color(238, 240, 243, (0.70f * 255).toInt()),
    scrimSolid = Color(238, 240, 243, (0.85f * 255).toInt()),
    tile = Color(16, 19, 24, (0.05f * 255).toInt()),
    tabOn = Color(0xFFFFFFFF),
    hair = Color(16, 19, 24, (0.08f * 255).toInt()),
    divider = Color(16, 19, 24, (0.10f * 255).toInt()),
    track = Color(16, 19, 24, (0.12f * 255).toInt()),
    switchTrack = Color(16, 19, 24, (0.14f * 255).toInt()),
    shadow = Color(16, 24, 40, (0.14f * 255).toInt()),
    markerShadow = Color(0, 0, 0, (0.25f * 255).toInt()),
    text = Color(0xFF101318),
    muted = Color(0xFF535A68),
    accent = Color(0xFF2F6BFF),
    onAccent = Color(0xFFFFFFFF),
    selected = Color(47, 107, 255, (0.14f * 255).toInt()),
    selectedContent = Color(0xFF2558D9),
    halo = Color(47, 107, 255, (0.07f * 255).toInt()),
)

val DarkHauntColors = HauntColors(
    isDark = true,
    map = Color(0xFF18181A),
    park = Color(0xFF1C241D),
    water = Color(0xFF172029),
    minorRoad = Color(0xFF232326),
    casing = Color(0xFF232326),
    majorRoad = Color(0xFF2E2E33),
    mapLabel = Color(0xFF74747C),
    glass = Color(30, 32, 38, (0.58f * 255).toInt()),
    glassSolid = Color(30, 32, 38, (0.76f * 255).toInt()),
    glassFallback = Color(0xF0222429),
    glassBorder = Color(255, 255, 255, (0.10f * 255).toInt()),
    scrim = Color(17, 18, 20, (0.72f * 255).toInt()),
    scrimSolid = Color(17, 18, 20, (0.85f * 255).toInt()),
    tile = Color(255, 255, 255, (0.07f * 255).toInt()),
    tabOn = Color(255, 255, 255, (0.12f * 255).toInt()),
    hair = Color(255, 255, 255, (0.08f * 255).toInt()),
    divider = Color(255, 255, 255, (0.10f * 255).toInt()),
    track = Color(255, 255, 255, (0.14f * 255).toInt()),
    switchTrack = Color(255, 255, 255, (0.16f * 255).toInt()),
    shadow = Color(0, 0, 0, (0.45f * 255).toInt()),
    markerShadow = Color(0, 0, 0, (0.25f * 255).toInt()),
    text = Color(0xFFF2F4F8),
    muted = Color(0xFFA7ADB8),
    accent = Color(0xFF6E9BFF),
    onAccent = Color(0xFF0A1430),
    selected = Color(110, 155, 255, (0.20f * 255).toInt()),
    selectedContent = Color(0xFFA8C3FF),
    halo = Color(110, 155, 255, (0.08f * 255).toInt()),
)

/** Folder colours used in the Library (from `GLibrary.dc.html`). */
object FolderColors {
    val Blue = Color(0xFF5B8DEF)
    val Green = Color(0xFF2FA37A)
    val Orange = Color(0xFFD9822B)
    val all = listOf(Blue, Green, Orange)
}

val LocalHauntColors = staticCompositionLocalOf { LightHauntColors }
