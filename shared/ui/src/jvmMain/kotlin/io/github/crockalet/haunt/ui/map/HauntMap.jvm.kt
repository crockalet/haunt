package io.github.crockalet.haunt.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.state.MapContent

/** Desktop/JVM: the drawn stand-in map (MapLibre native is not wired up for desktop yet). */
@Composable
actual fun HauntMap(
    content: MapContent,
    styleUrl: String,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier,
) {
    DrawnMap(content, onLongPress, modifier)
}
