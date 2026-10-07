package io.github.crockalet.haunt.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.ui.components.Chip
import io.github.crockalet.haunt.ui.components.DetailsCard
import io.github.crockalet.haunt.ui.components.Dot
import io.github.crockalet.haunt.ui.components.GlassIconButton
import io.github.crockalet.haunt.ui.components.GlassSurface
import io.github.crockalet.haunt.ui.components.GlassToolbar
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.JoystickGrip
import io.github.crockalet.haunt.ui.components.JoystickPad
import io.github.crockalet.haunt.ui.components.ProgressBar
import io.github.crockalet.haunt.ui.components.SearchPill
import io.github.crockalet.haunt.ui.components.SegmentedControl
import io.github.crockalet.haunt.ui.components.Slider
import io.github.crockalet.haunt.ui.components.StartButton
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
    /** Start faking what the selected mode has ready ([MapUiState.startAction]). */
    val onStart: () -> Unit = {},
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
    /** Ask for "Display over other apps" (the floating joystick's permission). */
    val onAllowOverlay: () -> Unit = {},
    /** The pad was dragged to a new offset (dp from its default spot); persist it. */
    val onJoystickMoved: (xDp: Float, yDp: Float) -> Unit = { _, _ -> },
    /** Locate button (real device location); null hides it. */
    val onLocate: (() -> Unit)? = null,
    /** Height from the bottom of the window that the map's attribution must clear (toolbar and locate button). */
    val onBottomChrome: (Dp) -> Unit = {},
)

/**
 * Map chrome: search pill + status chip on top, joystick pad, details card and the toolbar with
 * the Start / Stop button at the bottom. Drawn over the map by `HauntApp`.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    actions: MapActions,
    modifier: Modifier = Modifier,
) {
    // Only read while dragging the pad, so not state: layout passes (e.g. the card expanding) mustn't recompose.
    val area = remember { Bounds() }
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { area.rect = it.boundsInRoot() },
    ) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.active)
                SearchPill(
                    placeholder = state.searchPlaceholder,
                    leadingIcon = if (state.mode == MapMode.Route) HauntIcons.Plus else HauntIcons.Search,
                    onClick = actions.onSearch,
                    onLibrary = actions.onLibrary,
                    onSettings = actions.onSettings,
                    modifier = Modifier.weight(1f).morph(MorphKeys.Search),
                )
            }
            // Glass is a live blur of the map: fade or move it, never scale it (a scaled blur is
            // re-captured and re-blurred at every frame of the animation).
            AnimatedVisibility(
                !state.expanded,
                enter = fadeIn(HauntMotion.snappy()),
                exit = fadeOut(HauntMotion.snappy()),
            ) {
                Status(state.status)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    // Shown in Joystick mode right away, but it only steers once Start has started the joystick.
                    AnimatedVisibility(
                        state.joystick != null,
                        enter = fadeIn(HauntMotion.snappy()),
                        exit = fadeOut(HauntMotion.snappy()),
                    ) {
                        // Keep showing the last pad while it animates out. A plain holder: writing state here
                        // would recompose this twice per joystick update.
                        val last = remember { LastJoystick() }
                        state.joystick?.let { last.value = it }
                        last.value?.let { j ->
                            Column {
                                MovableJoystick(j, area, actions)
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
                actions.onLocate?.let { onLocate ->
                    LocateButton(state.locating, onLocate, Modifier.padding(end = 12.dp, bottom = LocateButtonGap))
                }
            }
            AnimatedVisibility(
                state.expanded,
                // The card is revealed upwards out of the toolbar: a clip grows over it while it stays put
                // on screen, so its blurred backdrop is captured once rather than every frame.
                enter = fadeIn(HauntMotion.snappy()) + expandVertically(HauntMotion.smooth(), expandFrom = Alignment.Bottom),
                exit = fadeOut(HauntMotion.snappy()) + shrinkVertically(HauntMotion.smooth(), shrinkTowards = Alignment.Bottom),
            ) {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                    // Switching mode morphs the card between its contents and springs to the new size.
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it.mode },
                        transitionSpec = {
                            fadeIn(HauntMotion.snappy())
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
            val density = LocalDensity.current
            Row(
                Modifier.fillMaxWidth().onGloballyPositioned { toolbar ->
                    // Measured from the window bottom, which is also the map's (it fills the window).
                    val fromBottom = toolbar.findRootCoordinates().size.height - toolbar.boundsInRoot().top
                    actions.onBottomChrome(with(density) { fromBottom.toDp() })
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Toolbar(state, actions)
                StartStop(state, actions)
            }
        }
    }
}

/**
 * Start when the selected mode has something ready that isn't running, Stop while something runs.
 * When both apply (e.g. a new pin picked while a route plays) a small glass Stop sits beside Start.
 */
@Composable
private fun StartStop(state: MapUiState, actions: MapActions) {
    val start = state.startAction
    val stopLabel = if (state.route?.started == true) "Stop route" else "Stop haunting"
    if (start != null && state.active) {
        // Small enough that toolbar + Stop + Start fit a 360 dp wide phone.
        GlassIconButton(HauntIcons.Stop, stopLabel, actions.onStop, size = 44.dp, iconSize = 16.dp)
    }
    if (start != null || !state.active) {
        StartButton(onClick = actions.onStart, enabled = start != null, contentDescription = start ?: "Start haunting")
    } else {
        StopButton(onClick = actions.onStop, contentDescription = stopLabel)
    }
}

/** Glass disc beside the search pill: accent while faking, red while the real location shows. */
@Composable
private fun StatusDot(active: Boolean) {
    val c = HauntTheme.colors
    val color by animateColorAsState(if (active) c.accent else c.danger, HauntMotion.snappy())
    val description = if (active) "Faking location" else "Not faking location"
    GlassSurface(
        Modifier.size(52.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Dot(color, size = 14.dp)
    }
}

/** Round glass button: centres the map on the device's real location. Spins while locating. */
@Composable
private fun LocateButton(locating: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Spin the icon only; rotating the glass would re-blur it every frame.
    val spin = if (locating) {
        val angle = rememberInfiniteTransition(label = "locate")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
        Modifier.graphicsLayer { rotationZ = angle.value }
    } else {
        Modifier
    }
    GlassIconButton(
        icon = if (locating) HauntIcons.Spinner else HauntIcons.Locate,
        contentDescription = if (locating) "Finding your location" else "Show my location",
        onClick = onClick,
        modifier = modifier,
        size = LocateButtonSize,
        iconModifier = spin,
    )
}

private val LocateButtonSize = 52.dp
private val LocateButtonGap = 12.dp

private class LastJoystick {
    var value: JoystickDetails? = null
}

/** Layout bounds kept outside snapshot state: written on every layout pass, read only in gestures. */
private class Bounds {
    var rect: Rect = Rect.Zero
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
private fun MovableJoystick(j: JoystickDetails, areaBounds: Bounds, actions: MapActions) {
    val density = LocalDensity.current
    val stored = Offset(j.offsetX, j.offsetY)
    var live by remember { mutableStateOf<Offset?>(null) }
    val baseBounds = remember { Bounds() }
    val padSize = j.size.dp.dp

    fun clamp(o: Offset): Offset {
        val area = areaBounds.rect
        val base = baseBounds.rect
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
            .onGloballyPositioned { baseBounds.rect = it.boundsInRoot() }
            .offset {
                val o = live ?: stored
                IntOffset(o.x.dp.roundToPx(), o.y.dp.roundToPx())
            },
    ) {
        JoystickPad(
            bearingDeg = j.bearingDeg,
            magnitude = j.magnitude,
            onInput = actions.onJoystick,
            padSize = padSize,
            modifier = Modifier.padding(top = 12.dp, end = 12.dp),
            enabled = j.live,
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
    // Pause / resume only while a route plays; starting one is the Start button's job.
    val started = state.route?.takeIf { it.started }
    val route = started != null
    GlassToolbar(spacing = if (route) 2.dp else 4.dp) {
        ToolbarButton(HauntIcons.Pin, "Pin mode", state.mode == MapMode.Pin, { actions.onModeSelect(MapMode.Pin) })
        ToolbarButton(HauntIcons.Route, "Route mode", state.mode == MapMode.Route, { actions.onModeSelect(MapMode.Route) })
        ToolbarButton(HauntIcons.Joystick, "Joystick mode", state.mode == MapMode.Joystick, { actions.onModeSelect(MapMode.Joystick) })
        VerticalHairline(Modifier.padding(horizontal = if (route) 3.dp else 2.dp))
        started?.let { r ->
            val playing = r.playing
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Follow roads", style = HauntTheme.type.bodyStrong)
                route.roadsNote?.let {
                    Text(it, style = HauntTheme.type.small, color = c.muted, maxLines = 2)
                }
            }
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
        if (j.floatingNeedsPermission) OverlayPermissionRow(actions.onAllowOverlay)
    }
}

/** The floating joystick is on but Android won't draw it over other apps yet; [onAllow] asks for that. */
@Composable
internal fun OverlayPermissionRow(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Needs “Display over other apps”",
            Modifier.weight(1f),
            style = HauntTheme.type.small,
            color = HauntTheme.colors.muted,
            maxLines = 2,
        )
        Chip("Allow", selected = true, onClick = onAllow, modifier = Modifier.semantics { contentDescription = "Allow display over other apps" })
    }
}
