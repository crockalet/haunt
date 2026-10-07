package io.github.crockalet.haunt.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
    bottomInset: Dp,
) {
    val attribution = remember { AttributionState() }
    Box(modifier) {
        DrawnMap(
            content,
            onLongPress = {
                attribution.onMapGesture()
                onLongPress(it)
            },
            Modifier.fillMaxSize(),
        )
        MapAttributionOverlay(remember(style) { MapAttributions.forStyle(style, emptyList()) }, attribution, bottomInset)
    }
}
