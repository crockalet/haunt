package io.github.crockalet.haunt.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.state.MapContent
import io.github.crockalet.haunt.ui.state.MapStyle

/**
 * The map behind everything. Android renders MapLibre with [style] (Haunt's own style from the theme's
 * map colours by default, see [HauntMapStyle]); other targets draw [DrawnMap], an illustrative stand-in.
 *
 * Shows the ghost marker + accuracy halo at [MapContent.fix], the route polyline and joystick
 * trail, a ring at the [MapContent.pending] spot, and reports long-presses via [onLongPress].
 */
@Composable
expect fun HauntMap(
    content: MapContent,
    style: MapStyle,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
)
