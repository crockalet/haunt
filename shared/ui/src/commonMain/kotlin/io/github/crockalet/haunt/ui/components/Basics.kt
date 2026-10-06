package io.github.crockalet.haunt.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.theme.HauntMotion
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme
import io.github.crockalet.haunt.ui.theme.LocalContentColor
import io.github.crockalet.haunt.ui.theme.LocalTextStyle

/** Text using [LocalTextStyle] and [LocalContentColor] (no Material). */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    val c = if (color.isSpecified) color else style.color.takeIf { it.isSpecified } ?: LocalContentColor.current
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = c, textAlign = textAlign ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Provides content colour and text style to [content]. */
@Composable
fun ProvideContent(color: Color, style: TextStyle? = null, content: @Composable () -> Unit) {
    if (style != null) {
        CompositionLocalProvider(LocalContentColor provides color, LocalTextStyle provides style, content = content)
    } else {
        CompositionLocalProvider(LocalContentColor provides color, content = content)
    }
}

/** Tinted vector icon. */
@Composable
fun Icon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * Round icon button (`.rb` in the mockups). Transparent by default; pass [background] for the
 * tile-filled variant.
 */
@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 21.dp,
    background: Color = Color.Transparent,
    tint: Color = HauntTheme.colors.text,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .size(size)
            .clip(HauntShapes.pill)
            .background(background)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, size = iconSize, tint = tint)
    }
}

/** Mode button inside the [GlassToolbar]. Selected = accent-tinted pill. */
@Composable
fun ToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    neutral: Boolean = false,
    iconSize: Dp = 22.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = HauntTheme.colors
    Box(
        modifier
            .pressScale(interaction)
            .size(48.dp)
            .clip(HauntShapes.pill)
            .background(animateColorAsState(if (selected) c.selected else c.selected.copy(alpha = 0f), HauntMotion.snappy(), label = "toolbar").value)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null, size = iconSize,
            tint = when {
                selected -> c.selectedContent
                neutral -> c.text
                else -> c.muted
            },
        )
    }
}

/** Filled accent pill button — the main action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconSize: Dp = 16.dp,
    height: Dp = 48.dp,
    style: TextStyle = HauntTheme.type.button,
    shadow: Boolean = false,
    horizontalPadding: Dp = 20.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = HauntTheme.colors
    Row(
        modifier
            .pressScale(interaction)
            .height(height)
            .then(if (shadow) Modifier.outerShadow(HauntShapes.pill, c.shadow) else Modifier)
            .clip(HauntShapes.pill)
            .background(c.accent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, size = iconSize, tint = c.onAccent)
        Text(text, style = style, color = c.onAccent, maxLines = 1)
    }
}

/** Neutral tile-filled pill button. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    style: TextStyle = HauntTheme.type.bodyStrong,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = HauntTheme.colors
    Box(
        modifier
            .pressScale(interaction)
            .height(height)
            .clip(HauntShapes.pill)
            .background(c.tile)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = c.text, maxLines = 1)
    }
}

/** Glass pill button (e.g. "Paste from clipboard"). */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    // Press feedback scales the label, not the glass: a scaled blur is re-rendered every frame.
    GlassSurface(
        modifier
            .height(height)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Modifier.pressScale(interaction).padding(horizontal = 16.dp), style = HauntTheme.type.label, maxLines = 1)
    }
}

/**
 * Round glass button (back button on overlay screens). [iconModifier] transforms the icon only
 * (e.g. a spinner's rotation), never the glass.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    iconModifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    GlassSurface(
        modifier
            .size(size)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, modifier = Modifier.pressScale(interaction).then(iconModifier), size = iconSize, tint = HauntTheme.colors.text)
    }
}

/** Accent dot (status chips, folder chips). */
@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(HauntShapes.pill).background(color))
}

/** Thin vertical separator used inside the toolbar. */
@Composable
fun VerticalHairline(modifier: Modifier = Modifier, height: Dp = 26.dp) {
    Box(modifier.size(1.dp, height).background(HauntTheme.colors.divider))
}

/** Round, filled container for a list leading icon / dot. */
@Composable
fun LeadingBadge(
    modifier: Modifier = Modifier,
    background: Color = HauntTheme.colors.tile,
    size: Dp = 36.dp,
    shape: Shape = HauntShapes.pill,
    content: @Composable () -> Unit,
) {
    Box(modifier.size(size).clip(shape).background(background), contentAlignment = Alignment.Center) {
        content()
    }
}

/** Row shorthand with centred items. */
@Composable
inline fun CenterRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically, content = content)
}
