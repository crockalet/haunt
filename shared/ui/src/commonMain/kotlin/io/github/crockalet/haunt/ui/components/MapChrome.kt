package io.github.crockalet.haunt.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme

/** Top search pill with Library and Settings buttons. */
@Composable
fun SearchPill(
    placeholder: String,
    onClick: () -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = HauntIcons.Search,
) {
    val c = HauntTheme.colors
    GlassSurface(modifier.fillMaxWidth().height(52.dp)) {
        Row(Modifier.padding(start = 18.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(52.dp).clickable(role = Role.Button, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(leadingIcon, null, size = 19.dp, tint = c.muted)
                Text(placeholder, Modifier.padding(start = 10.dp), style = HauntTheme.type.body, color = c.muted, maxLines = 1)
            }
            IconButton(HauntIcons.Library, "Library", onLibrary)
            IconButton(HauntIcons.Settings, "Settings", onSettings)
        }
    }
}

/** Small glass status capsule below the search pill. */
@Composable
fun StatusChip(
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    horizontalPadding: Dp = 14.dp,
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(modifier.height(height)) {
        ProvideContent(HauntTheme.colors.text, HauntTheme.type.label) {
            Row(
                Modifier.height(height).padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Floating glass toolbar (64dp capsule). */
@Composable
fun GlassToolbar(
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(modifier.height(64.dp), shape = HauntShapes.pill) {
        Row(
            Modifier.height(64.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Round accent Stop button next to the toolbar. */
@Composable
fun StopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Stop haunting",
    enabled: Boolean = true,
) = RoundActionButton(HauntIcons.Stop, 20.dp, onClick, modifier, contentDescription, enabled)

/** Round accent Start button next to the toolbar; takes the Stop button's place while nothing runs. */
@Composable
fun StartButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Start haunting",
    enabled: Boolean = true,
) = RoundActionButton(HauntIcons.Play, 24.dp, onClick, modifier, contentDescription, enabled)

@Composable
private fun RoundActionButton(
    icon: ImageVector,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
    contentDescription: String,
    enabled: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = HauntTheme.colors
    Box(
        modifier
            .pressScale(interaction)
            .size(64.dp)
            .outerShadow(HauntShapes.pill, c.shadow)
            .clip(HauntShapes.pill)
            .background(if (enabled) c.accent else c.glassFallback)
            .then(if (!enabled) Modifier.border(1.dp, c.glassBorder, HauntShapes.pill) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, size = iconSize, tint = if (enabled) c.onAccent else c.muted)
    }
}

/** Expanded glass details card shown above the toolbar. */
@Composable
fun DetailsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier.fillMaxWidth(), shape = HauntShapes.card) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Coloured dot inside a 36dp tile badge (Library favourites). */
@Composable
fun FolderBadge(color: androidx.compose.ui.graphics.Color) {
    LeadingBadge { Dot(color, 10.dp) }
}

/** Marker for list items whose leading slot shows an icon. */
@Composable
fun IconBadge(icon: ImageVector, selected: Boolean = false) {
    val c = HauntTheme.colors
    LeadingBadge(background = if (selected) c.selected else c.tile) {
        Icon(icon, null, size = 18.dp, tint = if (selected) c.selectedContent else c.muted)
    }
}

/** Little tick marks used by the joystick pad. */
@Composable
internal fun Tick(modifier: Modifier, horizontal: Boolean) {
    Box(
        modifier
            .size(if (horizontal) 10.dp else 4.dp, if (horizontal) 4.dp else 10.dp)
            .clip(HauntShapes.pill)
            .background(HauntTheme.colors.muted.copy(alpha = 0.5f)),
    )
}
