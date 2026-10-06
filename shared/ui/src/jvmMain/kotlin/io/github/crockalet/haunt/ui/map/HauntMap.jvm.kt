package io.github.crockalet.haunt.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.state.MapContent
import io.github.crockalet.haunt.ui.state.MapStyle

/** Desktop/JVM: the drawn stand-in map (MapLibre native is not wired up for desktop yet). */
@Composable
actual fun HauntMap(
    content: MapContent,
    style: MapStyle,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier,
) {
    DrawnMap(content, onLongPress, modifier)
}
