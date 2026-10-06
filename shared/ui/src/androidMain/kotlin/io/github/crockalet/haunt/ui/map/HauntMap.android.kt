package io.github.crockalet.haunt.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.components.LocationMarker
import io.github.crockalet.haunt.ui.components.RouteEndpoint
import io.github.crockalet.haunt.ui.state.MapContent
import io.github.crockalet.haunt.ui.theme.HauntTheme
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

private fun LatLng.toPosition() = Position(longitude = lng, latitude = lat)

private fun lineJson(points: List<LatLng>): String =
    if (points.size < 2) {
        """{"type":"FeatureCollection","features":[]}"""
    } else {
        points.joinToString(
            prefix = """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""",
            postfix = "]}}",
        ) { "[${it.lng},${it.lat}]" }
    }

/** Android: MapLibre Native via maplibre-compose, with our overlays. */
@Composable
actual fun HauntMap(
    content: MapContent,
    styleUrl: String,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier,
) {
    if (LocalInspectionMode.current) {
        DrawnMap(content, onLongPress, modifier)
        return
    }
    val c = HauntTheme.colors
    val longPress by rememberUpdatedState(onLongPress)
    val start = remember { content.camera ?: LatLng(35.65952, 139.70055) }
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(styleUrl),
        initialCameraPosition = CameraPosition(target = start.toPosition(), zoom = 15.5),
    ) {
        val route = rememberGeoJsonSource(GeoJsonData.JsonString(lineJson(content.route)))
        val traveled = rememberGeoJsonSource(GeoJsonData.JsonString(lineJson(content.traveled)))
        val trail = rememberGeoJsonSource(GeoJsonData.JsonString(lineJson(content.trail)))
        LineLayer(
            id = "haunt-route-rest", source = route,
            color = const(c.accent), opacity = const(0.45f), width = const(6.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
        LineLayer(
            id = "haunt-route-done", source = traveled,
            color = const(c.accent), width = const(6.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
        LineLayer(
            id = "haunt-trail", source = trail,
            color = const(c.accent), opacity = const(0.5f), width = const(5.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
    }

    // Follow the ghost while moving.
    LaunchedEffect(content.camera, content.follow) {
        val target = content.camera ?: return@LaunchedEffect
        if (content.follow) mapState.animateCamera(CameraUpdate(target = target.toPosition()))
    }

    val interactions = remember {
        MapInteractions {
            callbacks {
                longClick {
                    onEvent { e ->
                        e.position?.let { longPress(LatLng(it.latitude, it.longitude)) }
                        ClickResult.Consume
                    }
                }
            }
        }
    }

    MaplibreMap(
        modifier = modifier,
        state = mapState,
        interactions = interactions,
        overlay = {
            include(MapOverlay.AttributionOnly)
            content.route.firstOrNull()?.let { RouteEndpoint(start = true, modifier = Modifier.placedAt(it.toPosition())) }
            if (content.route.size >= 2) RouteEndpoint(start = false, modifier = Modifier.placedAt(content.route.last().toPosition()))
            content.fix?.let { fix ->
                val metersPerDp = mapState.viewport?.metersPerDpAtTarget
                val halo = content.accuracyMeters
                    ?.let { acc -> metersPerDp?.takeIf { it > 0 }?.let { (acc / it).dp } }
                    ?.coerceIn(18.dp, 120.dp) ?: 50.dp
                LocationMarker(
                    modifier = Modifier.placedAt(fix.toPosition()),
                    showHalo = content.showHalo,
                    haloRadius = halo,
                    moving = content.moving,
                )
            }
        },
    )
}
