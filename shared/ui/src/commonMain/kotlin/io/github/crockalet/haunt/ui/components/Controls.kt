package io.github.crockalet.haunt.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.theme.HauntMotion
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme

/** Pill chip (`.chip`). Selected = accent tint. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    textStyle: TextStyle = HauntTheme.type.label,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = HauntTheme.colors
    val fill = animateColorAsState(if (selected) c.selected else c.tile, HauntMotion.snappy(), label = "chip")
    Row(
        modifier
            .pressScale(interaction)
            .height(height)
            .clip(HauntShapes.pill)
            .drawBehind { drawRect(fill.value) }
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = textStyle, color = if (selected) c.selectedContent else c.text, maxLines = 1)
    }
}

/** Single-select group of [Chip]s. */
@Composable
fun <T> ChipGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    leading: (@Composable (T) -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            Chip(
                text = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                height = height,
                leading = leading?.let { { it(option) } },
            )
        }
    }
}

/** Segmented control (`.seg`): tile track, glass thumb on the selected segment. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    Row(modifier.clip(HauntShapes.pill).background(c.tile).padding(3.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .height(30.dp)
                    .clip(HauntShapes.pill)
                    .background(if (on) c.glass else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = HauntTheme.type.small, color = if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

/** Tabs (Library): glass track, raised thumb on the selected tab. */
@Composable
fun <T> Tabs(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    GlassSurface(modifier.fillMaxWidth(), shape = HauntShapes.pill) {
        Row(Modifier.padding(4.dp)) {
            options.forEach { option ->
                val on = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(HauntShapes.pill)
                        .background(if (on) c.tabOn else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(option) }
                        .semantics { this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = HauntTheme.type.tab, color = if (on) c.text else c.muted, maxLines = 1)
                }
            }
        }
    }
}

/** iOS-style switch (`.sw`): 50×30 track, 24dp white thumb. */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val c = HauntTheme.colors
    val x = animateDpAsState(if (checked) 23.dp else 3.dp)
    Box(
        modifier
            .size(50.dp, 30.dp)
            .clip(HauntShapes.pill)
            .background(if (checked) c.accent else c.switchTrack)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                .offset { IntOffset(x.value.roundToPx(), 3.dp.roundToPx()) }
                .size(24.dp)
                .dropShadow(HauntShapes.pill, Shadow(radius = 3.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 1.dp)))
                .clip(HauntShapes.pill)
                .background(Color.White),
        )
    }
}

/** Continuous slider: tile track, accent fill, white thumb. */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    contentDescription: String? = null,
) {
    val c = HauntTheme.colors
    val onChange by rememberUpdatedState(onValueChange)
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { onChange(it.coerceIn(valueRange)); true }
            },
    ) {
        val thumb = 22.dp
        val trackWidth = maxWidth
        val widthPx = constraints.maxWidth.toFloat()
        fun update(x: Float) {
            onChange(valueRange.start + (x / widthPx).coerceIn(0f, 1f) * span)
        }
        val input = Modifier
            .pointerInput(widthPx, valueRange) { detectTapGestures { update(it.x) } }
            .pointerInput(widthPx, valueRange) { detectDragGestures { change, _ -> update(change.position.x) } }
        Box(Modifier.matchParentSize().then(input)) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(HauntShapes.pill).background(c.track))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(6.dp).clip(HauntShapes.pill).background(c.accent))
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((trackWidth - thumb) * fraction).roundToPx(), 0) }
                    .size(thumb)
                    .dropShadow(HauntShapes.pill, Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.25f), offset = DpOffset(0.dp, 1.dp)))
                    .clip(HauntShapes.pill)
                    .background(Color.White),
            )
        }
    }
}

/** Linear progress bar (6dp track). */
@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val c = HauntTheme.colors
    val target = progress.coerceIn(0f, 1f)
    val p = animateFloatAsState(target)
    Box(
        modifier
            .height(height)
            .clip(HauntShapes.pill)
            .background(c.track)
            .drawBehind {
                val fill = Size(size.width * p.value, size.height)
                drawRoundRect(c.accent, size = fill, cornerRadius = CornerRadius(fill.minDimension / 2))
            }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) },
    )
}

/** Key/value tile in the details card (`.tile`). */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    Column(
        modifier.clip(HauntShapes.tile).background(c.tile).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = HauntTheme.type.small, color = c.muted, maxLines = 1)
        Text(value, style = HauntTheme.type.monoValue, color = c.text, maxLines = 1)
    }
}

/** A row of equally sized [StatTile]s. */
@Composable
fun StatTiles(tiles: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (k, v) -> StatTile(k, v, Modifier.weight(1f)) }
    }
}

/** Muted bold section header above a list (`.h`). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 6.dp, bottom = 8.dp),
        style = HauntTheme.type.section,
        color = HauntTheme.colors.muted,
    )
}

/** Glass container for list rows; rows are separated by hairlines. */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    shape: Shape = HauntShapes.list,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier.fillMaxWidth(), shape = shape) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Hairline between rows of a [ListGroup]. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HauntTheme.colors.hair))
}

/** List row: optional leading, title + subtitle, optional trailing. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleStyle: TextStyle = HauntTheme.type.caption,
    titleStyle: TextStyle = HauntTheme.type.bodyStrong,
    titleColor: Color = HauntTheme.colors.text,
    minHeight: Dp = 60.dp,
    padding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = titleStyle, color = titleColor, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = subtitleStyle, color = HauntTheme.colors.muted, maxLines = 2)
        }
        trailing?.invoke(this)
    }
}

/** Segmented step progress (onboarding). */
@Composable
fun StepProgress(steps: Int, current: Int, modifier: Modifier = Modifier) {
    val c = HauntTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(steps) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(HauntShapes.pill).background(if (i < current) c.accent else c.track))
        }
    }
}

/** Fixed-width spacer helper. */
@Composable
fun HSpace(width: Dp) = Box(Modifier.width(width))
