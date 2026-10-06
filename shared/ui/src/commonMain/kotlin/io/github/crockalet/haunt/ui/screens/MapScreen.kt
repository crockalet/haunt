package io.github.crockalet.haunt.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.ui.components.Chip
import io.github.crockalet.haunt.ui.components.DetailsCard
import io.github.crockalet.haunt.ui.components.Dot
import io.github.crockalet.haunt.ui.components.GlassToolbar
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.JoystickGrip
import io.github.crockalet.haunt.ui.components.JoystickPad
import io.github.crockalet.haunt.ui.components.ProgressBar
import io.github.crockalet.haunt.ui.components.SearchPill
import io.github.crockalet.haunt.ui.components.SegmentedControl
import io.github.crockalet.haunt.ui.components.Slider
import io.github.crockalet.haunt.ui.components.StatTiles
import io.github.crockalet.haunt.ui.components.StatusChip
import io.github.crockalet.haunt.ui.components.StopButton
import io.github.crockalet.haunt.ui.components.Switch
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.components.ToolbarButton
import io.github.crockalet.haunt.ui.components.VerticalHairline
import io.github.crockalet.haunt.ui.components.morph
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.JoystickDetails
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.state.MapUiState
import io.github.crockalet.haunt.ui.state.PinDetails
import io.github.crockalet.haunt.ui.state.RouteDetails
import io.github.crockalet.haunt.ui.state.SpeedPreset
import io.github.crockalet.haunt.ui.state.StatusUi
import io.github.crockalet.haunt.ui.theme.HauntMotion
import io.github.crockalet.haunt.ui.theme.HauntTheme

/** Callbacks of the map screen. All default to no-ops (previews). */
@Immutable
data class MapActions(
    val onModeSelect: (MapMode) -> Unit = {},
    val onToggleExpanded: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onLibrary: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onCopyCoordinates: () -> Unit = {},
    val onSaveFavourite: () -> Unit = {},
    val onSpeedPreset: (SpeedPreset) -> Unit = {},
    val onFollowRoads: (Boolean) -> Unit = {},
    val onLoop: (LoopMode) -> Unit = {},
    val onRate: () -> Unit = {},
    val onCustomSpeed: (Float) -> Unit = {},
    val onJoystick: (bearingDeg: Double, magnitude: Double) -> Unit = { _, _ -> },
    val onJoystickMaxSpeed: (Float) -> Unit = {},
    val onJoystickSize: (JoystickSize) -> Unit = {},
    val onFloatingJoystick: (Boolean) -> Unit = {},
    /** The pad was dragged to a new offset (dp from its default spot); persist it. */
    val onJoystickMoved: (xDp: Float, yDp: Float) -> Unit = { _, _ -> },
)

/**
 * Map chrome: search pill + status chip on top, joystick pad, details card and the toolbar with
 * the Stop button at the bottom. Drawn over the map by `HauntApp`.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    actions: MapActions,
    modifier: Modifier = Modifier,
) {
    var area by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { area = it.boundsInRoot() },
    ) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SearchPill(
                placeholder = state.searchPlaceholder,
                leadingIcon = if (state.mode == MapMode.Route) HauntIcons.Plus else HauntIcons.Search,
                onClick = actions.onSearch,
                onLibrary = actions.onLibrary,
                onSettings = actions.onSettings,
                modifier = Modifier.morph(MorphKeys.Search),
            )
            AnimatedVisibility(
                !state.expanded,
                enter = fadeIn(HauntMotion.snappy()) + scaleIn(HauntMotion.smooth(), initialScale = 0.9f),
                exit = fadeOut(HauntMotion.snappy()) + scaleOut(HauntMotion.smooth(), targetScale = 0.9f),
            ) {
                Status(state.status)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp)) {
            AnimatedVisibility(
                state.joystick != null,
                enter = fadeIn(HauntMotion.snappy()) + scaleIn(HauntMotion.bouncy(), initialScale = 0.6f, transformOrigin = TransformOrigin(0f, 1f)),
                exit = fadeOut(HauntMotion.snappy()) + scaleOut(HauntMotion.smooth(), targetScale = 0.6f, transformOrigin = TransformOrigin(0f, 1f)),
            ) {
                // Keep showing the last pad while it animates out.
                val last = remember { mutableStateOf(state.joystick) }
                state.joystick?.let { last.value = it }
                last.value?.let { j ->
                    MovableJoystick(j, area, actions)
                    Spacer(Modifier.height(12.dp))
                }
            }
            AnimatedVisibility(
                state.expanded,
                // The card grows out of the toolbar, like a tray morphing from its trigger.
                enter = fadeIn(HauntMotion.snappy()) +
                    expandVertically(HauntMotion.smooth(), expandFrom = Alignment.Bottom) +
                    scaleIn(HauntMotion.smooth(), initialScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut(HauntMotion.snappy()) +
                    shrinkVertically(HauntMotion.smooth(), shrinkTowards = Alignment.Bottom) +
                    scaleOut(HauntMotion.smooth(), targetScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 1f)),
            ) {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                    // Switching mode morphs the card between its contents and springs to the new size.
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it.mode },
                        transitionSpec = {
                            (fadeIn(HauntMotion.snappy()) + scaleIn(HauntMotion.smooth(), initialScale = 0.96f))
                                .togetherWith(fadeOut(HauntMotion.snappy()))
                                .using(SizeTransform(clip = false) { _, _ -> HauntMotion.smooth() })
                        },
                        label = "card",
                    ) { s ->
                        when {
                            s.pin != null -> PinCard(s.pin, actions)
                            s.route != null -> RouteCard(s.route, actions)
                            s.joystick != null -> JoystickCard(s.joystick, actions)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Toolbar(state, actions)
                StopButton(
                    onClick = actions.onStop,
                    enabled = state.active,
                    contentDescription = if (state.mode == MapMode.Route) "Stop route" else "Stop haunting",
                )
            }
        }
    }
}

/** Shared-element keys for [morph] transitions between screens. */
object MorphKeys {
    const val Search = "search"
}

/**
 * The joystick pad at its default spot (bottom-left) shifted by the user's offset. Drag the grip
 * on its corner to move it; it stays inside [area] (the screen's safe area, in root px).
 */
@Composable
private fun MovableJoystick(j: JoystickDetails, area: Rect, actions: MapActions) {
    val density = LocalDensity.current
    val stored = Offset(j.offsetX, j.offsetY)
    var live by remember { mutableStateOf<Offset?>(null) }
    var base by remember { mutableStateOf(Rect.Zero) }
    val offset = live ?: stored
    val padSize = j.size.dp.dp

    fun clamp(o: Offset): Offset {
        if (area == Rect.Zero || base == Rect.Zero) return o
        with(density) {
            val minX = (area.left - base.left).toDp().value
            val maxX = (area.right - base.right).toDp().value
            val minY = (area.top - base.top).toDp().value
            val maxY = (area.bottom - base.bottom).toDp().value
            return Offset(o.x.coerceIn(minX, maxOf(minX, maxX)), o.y.coerceIn(minY, maxOf(minY, maxY)))
        }
    }

    Box(
        Modifier
            .padding(start = 14.dp)
            .onGloballyPositioned { base = it.boundsInRoot() }
            .offset { IntOffset(offset.x.dp.roundToPx(), offset.y.dp.roundToPx()) },
    ) {
        JoystickPad(
            bearingDeg = j.bearingDeg,
            magnitude = j.magnitude,
            onInput = actions.onJoystick,
            padSize = padSize,
            modifier = Modifier.padding(top = 12.dp, end = 12.dp),
        )
        JoystickGrip(
            onDrag = { d -> live = clamp((live ?: stored) + Offset(d.x / density.density, d.y / density.density)) },
            onDragEnd = {
                live?.let { actions.onJoystickMoved(it.x, it.y) }
                live = null
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun Status(status: StatusUi) {
    AnimatedContent(
        targetState = status,
        contentKey = { it::class },
        transitionSpec = {
            fadeIn(HauntMotion.snappy()).togetherWith(fadeOut(HauntMotion.snappy()))
                .using(SizeTransform(clip = false) { _, _ -> HauntMotion.smooth() })
        },
        label = "status",
    ) { s -> StatusContent(s) }
}

@Composable
private fun StatusContent(status: StatusUi) {
    val c = HauntTheme.colors
    when (status) {
        is StatusUi.Message -> StatusChip {
            Dot(if (status.active) c.accent else c.muted)
            Text(status.text, maxLines = 1)
        }
        is StatusUi.Progress -> StatusChip(height = 40.dp, horizontalPadding = 16.dp, spacing = 10.dp) {
            ProgressBar(status.fraction, Modifier.width(64.dp))
            Text(status.distance, style = HauntTheme.type.monoMedium, maxLines = 1)
            status.eta?.let { Text(it, color = c.muted, maxLines = 1) }
        }
        is StatusUi.Joystick -> StatusChip {
            Dot(c.accent)
            val mono = HauntTheme.type.monoMedium
            BasicText(
                buildAnnotatedString {
                    append("Joystick · ${status.direction} · ")
                    withStyle(SpanStyle(fontFamily = mono.fontFamily, fontWeight = mono.fontWeight)) { append(status.speed) }
                },
                style = HauntTheme.type.label.copy(color = c.text),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Toolbar(state: MapUiState, actions: MapActions) {
    val route = state.mode == MapMode.Route
    GlassToolbar(spacing = if (route) 2.dp else 4.dp) {
        ToolbarButton(HauntIcons.Pin, "Pin mode", state.mode == MapMode.Pin, { actions.onModeSelect(MapMode.Pin) })
        ToolbarButton(HauntIcons.Route, "Route mode", route, { actions.onModeSelect(MapMode.Route) })
        ToolbarButton(HauntIcons.Joystick, "Joystick mode", state.mode == MapMode.Joystick, { actions.onModeSelect(MapMode.Joystick) })
        VerticalHairline(Modifier.padding(horizontal = if (route) 3.dp else 2.dp))
        if (route) {
            val playing = state.route?.playing == true
            ToolbarButton(
                icon = if (playing) HauntIcons.Pause else HauntIcons.Play,
                contentDescription = if (playing) "Pause" else "Resume",
                selected = false,
                neutral = true,
                onClick = actions.onPlayPause,
            )
        }
        ToolbarButton(
            icon = if (state.expanded) HauntIcons.ChevronDown else HauntIcons.ChevronUp,
            contentDescription = if (state.expanded) "Hide details" else "Show details",
            selected = false,
            neutral = true,
            onClick = actions.onToggleExpanded,
        )
    }
}

@Composable
private fun PinCard(pin: PinDetails, actions: MapActions) {
    val c = HauntTheme.colors
    DetailsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(pin.title, style = HauntTheme.type.title, maxLines = 1)
                Text(pin.coordinates, style = HauntTheme.type.coords, color = c.muted, maxLines = 1)
            }
            IconButton(HauntIcons.Copy, "Copy coordinates", actions.onCopyCoordinates, iconSize = 18.dp, background = c.tile)
            IconButton(HauntIcons.Star, "Save to favourites", actions.onSaveFavourite, iconSize = 18.dp, background = c.tile)
        }
        StatTiles(listOf("Altitude" to pin.altitude, "Accuracy" to pin.accuracy, "Updates" to pin.updates))
        Text(pin.footer, style = HauntTheme.type.captionStrong, color = c.muted)
    }
}

@Composable
private fun RouteCard(route: RouteDetails, actions: MapActions) {
    val c = HauntTheme.colors
    DetailsCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(route.title, style = HauntTheme.type.title.copy(fontSize = HauntTheme.type.title.fontSize * 0.95f), maxLines = 1)
            ProgressBar(route.fraction, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(route.distance, style = HauntTheme.type.coords, color = c.muted)
                route.eta?.let { Text(it, style = HauntTheme.type.coords, color = c.muted) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpeedPreset.entries.forEach { p ->
                val selected = p == route.speedPreset
                Chip(
                    text = if (selected) "${p.label} · ${route.speedLabel}" else p.label,
                    selected = selected,
                    onClick = { actions.onSpeedPreset(p) },
                )
            }
        }
        if (route.speedPreset == SpeedPreset.Custom) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("Custom speed", style = HauntTheme.type.bodyStrong)
                    Text(route.speedLabel, style = HauntTheme.type.coords, color = c.muted)
                }
                Slider(
                    route.customKmh,
                    actions.onCustomSpeed,
                    valueRange = MapStateHolder.CustomSpeedRangeKmh,
                    contentDescription = "Custom speed",
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Follow roads", Modifier.weight(1f), style = HauntTheme.type.bodyStrong)
            Switch(route.followRoads, actions.onFollowRoads, contentDescription = "Follow roads")
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SegmentedControl(
                options = LoopMode.entries,
                selected = route.loop,
                onSelect = actions.onLoop,
                label = {
                    when (it) {
                        LoopMode.Once -> "Once"
                        LoopMode.Loop -> "Loop"
                        LoopMode.PingPong -> "Ping-pong"
                    }
                },
            )
            Chip(route.rateLabel, selected = false, onClick = actions.onRate, textStyle = HauntTheme.type.label.copy(fontFamily = HauntTheme.type.mono))
        }
    }
}

@Composable
private fun JoystickCard(j: JoystickDetails, actions: MapActions) {
    val c = HauntTheme.colors
    DetailsCard {
        StatTiles(listOf("Heading" to j.heading, "Speed" to j.speed, "Moved" to j.moved))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Max speed", style = HauntTheme.type.bodyStrong)
                Text("Walk · Cycle · Drive", style = HauntTheme.type.small, color = c.muted)
            }
            Slider(j.maxSpeedKmh, actions.onJoystickMaxSpeed, valueRange = 1f..120f, contentDescription = "Max speed")
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pad size", style = HauntTheme.type.bodyStrong)
            SegmentedControl(
                options = JoystickSize.entries,
                selected = j.size,
                onSelect = actions.onJoystickSize,
                label = { it.label },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Float over other apps", style = HauntTheme.type.bodyStrong)
                Text("Keep steering after you leave Haunt", style = HauntTheme.type.small, color = c.muted)
            }
            Switch(j.floating, actions.onFloatingJoystick, contentDescription = "Float over other apps")
        }
    }
}
