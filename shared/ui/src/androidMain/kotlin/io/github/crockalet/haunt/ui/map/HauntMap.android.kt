package io.github.crockalet.haunt.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.components.LocationMarker
import io.github.crockalet.haunt.ui.components.RouteEndpoint
import io.github.crockalet.haunt.ui.state.MapContent
import io.github.crockalet.haunt.ui.state.MapStyle
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
import kotlin.math.roundToInt

private fun LatLng.toPosition() = Position(longitude = lng, latitude = lat)

/** Accuracy halo radius on screen, in whole dp (sub-dp changes aren't worth a recomposition). */
internal fun haloRadius(accuracyMeters: Float?, metersPerDp: Double?): Dp =
    accuracyMeters
        ?.let { acc -> metersPerDp?.takeIf { it > 0 }?.let { (acc / it).roundToInt().dp } }
        ?.coerceIn(18.dp, 120.dp) ?: 50.dp

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
    style: MapStyle,
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
    // Haunt's style is generated from the theme, so it switches with light / dark.
    val baseStyle = remember(style, c) {
        when (style) {
            is MapStyle.Haunt -> BaseStyle.Json(HauntMapStyle.json(c, style.tilesUrl))
            is MapStyle.Custom -> BaseStyle.Uri(style.url)
        }
    }
    val mapState = rememberMapState(
        baseStyle = baseStyle,
        initialCameraPosition = CameraPosition(target = start.toPosition(), zoom = 15.5),
    ) {
        val route = rememberGeoJsonSource(remember(content.route) { GeoJsonData.JsonString(lineJson(content.route)) })
        val traveled = rememberGeoJsonSource(remember(content.traveled) { GeoJsonData.JsonString(lineJson(content.traveled)) })
        val trail = rememberGeoJsonSource(remember(content.trail) { GeoJsonData.JsonString(lineJson(content.trail)) })
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

    // Derived so camera frames (every frame while following) only recompose the overlay when the halo's size changes.
    val accuracy by rememberUpdatedState(content.accuracyMeters)
    val halo by remember(mapState) {
        derivedStateOf(structuralEqualityPolicy()) { haloRadius(accuracy, mapState.viewport?.metersPerDpAtTarget) }
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
