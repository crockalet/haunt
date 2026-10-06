package io.github.crockalet.haunt.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.state.MapContent

/**
 * The map behind everything. Android renders MapLibre ([styleUrl] is a MapLibre style JSON URL,
 * OpenFreeMap by default); other targets draw [DrawnMap], an illustrative stand-in.
 *
 * Shows the ghost marker + accuracy halo at [MapContent.fix], the route polyline and joystick
 * trail, and reports long-presses via [onLongPress].
 */
@Composable
expect fun HauntMap(
    content: MapContent,
    styleUrl: String,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
)
