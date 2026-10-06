package io.github.crockalet.haunt.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.ui.components.Chip
import io.github.crockalet.haunt.ui.components.DetailsCard
import io.github.crockalet.haunt.ui.components.Dot
import io.github.crockalet.haunt.ui.components.GlassToolbar
import io.github.crockalet.haunt.ui.components.IconButton
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
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.JoystickDetails
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.MapUiState
import io.github.crockalet.haunt.ui.state.PinDetails
import io.github.crockalet.haunt.ui.state.RouteDetails
import io.github.crockalet.haunt.ui.state.SpeedPreset
import io.github.crockalet.haunt.ui.state.StatusUi
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
    val onJoystick: (bearingDeg: Double, magnitude: Double) -> Unit = { _, _ -> },
    val onJoystickMaxSpeed: (Float) -> Unit = {},
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
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
            )
            AnimatedVisibility(!state.expanded, enter = fadeIn(), exit = fadeOut()) {
                Status(state.status)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp)) {
            state.joystick?.let { j ->
                JoystickPad(
                    bearingDeg = j.bearingDeg,
                    magnitude = j.magnitude,
                    onInput = actions.onJoystick,
                    modifier = Modifier.padding(start = 20.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
            AnimatedVisibility(
                state.expanded,
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut() + slideOutVertically { it / 4 },
            ) {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                    when {
                        state.pin != null -> PinCard(state.pin, actions)
                        state.route != null -> RouteCard(state.route, actions)
                        state.joystick != null -> JoystickCard(state.joystick, actions)
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

@Composable
private fun Status(status: StatusUi) {
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
            Chip("${route.rate}×", selected = false, onClick = actions.onRate, textStyle = HauntTheme.type.label.copy(fontFamily = HauntTheme.type.mono))
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
    }
}
