package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.components.GlassButton
import io.github.crockalet.haunt.ui.components.GlassIconButton
import io.github.crockalet.haunt.ui.components.GlassSurface
import io.github.crockalet.haunt.ui.components.IconBadge
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.ListGroup
import io.github.crockalet.haunt.ui.components.ListRow
import io.github.crockalet.haunt.ui.components.PrimaryButton
import io.github.crockalet.haunt.ui.components.RowDivider
import io.github.crockalet.haunt.ui.components.SectionHeader
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.components.TonalButton
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme

@Immutable
data class DetectedUi(val position: LatLng, val area: String?, val label: String = "Coordinates detected")

@Immutable
data class SearchUiState(
    val query: String = "",
    val detected: DetectedUi? = null,
    val results: List<Place> = emptyList(),
    val nearby: List<Place> = emptyList(),
    val recent: List<Place> = emptyList(),
    /** Status line under the field ("Searching…", a search error). */
    val notice: String? = null,
)

@Immutable
data class SearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onBack: () -> Unit = {},
    val onHauntHere: (LatLng) -> Unit = {},
    val onShowOnMap: (LatLng) -> Unit = {},
    val onSave: (LatLng) -> Unit = {},
    val onPlace: (Place) -> Unit = {},
    val onPaste: () -> Unit = {},
)

/** Full-screen search over the blurred map: coordinate detection, nearby places, recent. */
@Composable
fun SearchScreen(
    state: SearchUiState,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val c = HauntTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(HauntIcons.Back, "Back to map", actions.onBack, size = 52.dp)
            GlassSurface(Modifier.weight(1f).height(52.dp), borderColor = c.accent) {
                Row(Modifier.height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        val looksLikeCoords = state.detected != null
                        val style = (if (looksLikeCoords) HauntTheme.type.coords else HauntTheme.type.body).copy(color = c.text)
                        if (state.query.isEmpty()) {
                            Text("Search places or coordinates", style = HauntTheme.type.body, color = c.muted, maxLines = 1)
                        }
                        BasicTextField(
                            value = state.query,
                            onValueChange = actions.onQueryChange,
                            singleLine = true,
                            textStyle = style,
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                state.detected?.let { actions.onShowOnMap(it.position) }
                                    ?: state.results.firstOrNull()?.let(actions.onPlace)
                            }),
                            modifier = Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
                        )
                    }
                    if (state.query.isNotEmpty()) {
                        IconButton(HauntIcons.Close, "Clear", { actions.onQueryChange("") }, iconSize = 18.dp)
                    }
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            state.detected?.let { DetectedCard(it, actions) }

            state.notice?.let {
                Text(it, Modifier.padding(horizontal = 6.dp), style = HauntTheme.type.caption, color = c.muted)
            }

            if (state.results.isNotEmpty()) {
                PlaceSection("Results", state.results, actions, recent = false)
            }
            if (state.nearby.isNotEmpty()) {
                PlaceSection(if (state.detected != null) "Near this point" else "Nearby", state.nearby, actions, recent = false)
            }
            if (state.recent.isNotEmpty()) {
                PlaceSection("Recent", state.recent, actions, recent = true)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton("Paste from clipboard", actions.onPaste)
            Text("Decimal · DMS · Maps links · plus codes", style = HauntTheme.type.smallRegular, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun DetectedCard(d: DetectedUi, actions: SearchActions) {
    val c = HauntTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = HauntShapes.sheet) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.height(24.dp).clip(HauntShapes.pill).background(c.selected).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(d.label, style = HauntTheme.type.badge, color = c.selectedContent, maxLines = 1)
                }
                d.area?.let { Text(it, style = HauntTheme.type.caption, color = c.muted, maxLines = 1) }
            }
            Text(Format.coords(d.position), style = HauntTheme.type.monoLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Haunt here", { actions.onHauntHere(d.position) }, Modifier.weight(1f), icon = HauntIcons.Play, horizontalPadding = 8.dp)
                TonalButton("Show on map", { actions.onShowOnMap(d.position) })
                IconButton(HauntIcons.Star, "Save to favourites", { actions.onSave(d.position) }, size = 48.dp, iconSize = 18.dp, background = c.tile)
            }
        }
    }
}

@Composable
private fun PlaceSection(title: String, places: List<Place>, actions: SearchActions, recent: Boolean) {
    val c = HauntTheme.colors
    Column {
        SectionHeader(title)
        ListGroup(shape = HauntShapes.listSmall) {
            places.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                ListRow(
                    title = p.name,
                    subtitle = p.subtitle ?: Format.coords(p.position),
                    subtitleStyle = if (p.subtitle == null) HauntTheme.type.monoSmall else HauntTheme.type.caption,
                    onClick = { actions.onPlace(p) },
                    leading = { IconBadge(if (recent) HauntIcons.Clock else HauntIcons.Pin) },
                    trailing = {
                        p.meta?.let {
                            Text(it, style = if (recent) HauntTheme.type.smallRegular else HauntTheme.type.monoSmall, color = c.muted, maxLines = 1)
                        }
                    },
                )
            }
        }
    }
}

