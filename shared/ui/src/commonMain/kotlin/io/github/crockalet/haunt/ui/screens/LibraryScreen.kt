package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.Chip
import io.github.crockalet.haunt.ui.components.Dot
import io.github.crockalet.haunt.ui.components.FolderBadge
import io.github.crockalet.haunt.ui.components.IconBadge
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.ListGroup
import io.github.crockalet.haunt.ui.components.ListRow
import io.github.crockalet.haunt.ui.components.PrimaryButton
import io.github.crockalet.haunt.ui.components.RowDivider
import io.github.crockalet.haunt.ui.components.Tabs
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.Folder
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.state.Track
import io.github.crockalet.haunt.ui.theme.HauntTheme

enum class LibraryTab(val label: String) { Favourites("Favourites"), History("History"), Tracks("Tracks") }

@Immutable
data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.Favourites,
    val folders: List<Folder> = emptyList(),
    /** Selected folder id; null = All. */
    val folder: String? = null,
    val favourites: List<Place> = emptyList(),
    val history: List<Place> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@Immutable
data class LibraryActions(
    val onBack: () -> Unit = {},
    val onTab: (LibraryTab) -> Unit = {},
    val onFolder: (String?) -> Unit = {},
    val onHaunt: (Place) -> Unit = {},
    val onPlayTrack: (Track) -> Unit = {},
    val onImport: () -> Unit = {},
)

/** Library: Favourites (folders) · History · Tracks; import GPX / KML. */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Library", actions.onBack)
        Tabs(LibraryTab.entries, state.tab, actions.onTab, { it.label })

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.tab) {
                LibraryTab.Favourites -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("All · ${state.favourites.size}", state.folder == null, { actions.onFolder(null) }, height = 34.dp)
                        state.folders.forEach { f ->
                            Chip(f.name, state.folder == f.id, { actions.onFolder(f.id) }, height = 34.dp, leading = { Dot(f.color) })
                        }
                    }
                    val shown = state.favourites.filter { state.folder == null || it.folderId == state.folder }
                    if (shown.isEmpty()) {
                        Empty("No favourites yet. Tap ☆ on a place to save it.")
                    } else {
                        ListGroup {
                            shown.forEachIndexed { i, p ->
                                if (i > 0) RowDivider()
                                val color = state.folders.firstOrNull { it.id == p.folderId }?.color ?: c.muted
                                ListRow(
                                    title = p.name,
                                    subtitle = Format.coords(p.position),
                                    subtitleStyle = HauntTheme.type.monoSmall,
                                    minHeight = 64.dp,
                                    padding = PaddingValues(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                                    leading = { FolderBadge(color) },
                                    trailing = { PlayButton("Haunt ${p.name}") { actions.onHaunt(p) } },
                                )
                            }
                        }
                    }
                }
                LibraryTab.History -> if (state.history.isEmpty()) {
                    Empty("Places you haunt show up here.")
                } else {
                    ListGroup {
                        state.history.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            ListRow(
                                title = p.name,
                                subtitle = Format.coords(p.position),
                                subtitleStyle = HauntTheme.type.monoSmall,
                                minHeight = 64.dp,
                                padding = PaddingValues(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                                leading = { IconBadge(HauntIcons.Clock) },
                                trailing = {
                                    p.meta?.let { Text(it, style = HauntTheme.type.smallRegular, color = c.muted) }
                                    PlayButton("Haunt ${p.name}") { actions.onHaunt(p) }
                                },
                            )
                        }
                    }
                }
                LibraryTab.Tracks -> if (state.tracks.isEmpty()) {
                    Empty("Import a GPX or KML file to replay a recorded track.")
                } else {
                    ListGroup {
                        state.tracks.forEachIndexed { i, t ->
                            if (i > 0) RowDivider()
                            ListRow(
                                title = t.name,
                                subtitle = t.subtitle,
                                minHeight = 64.dp,
                                padding = PaddingValues(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                                leading = { IconBadge(HauntIcons.Route) },
                                trailing = { PlayButton("Play ${t.name}") { actions.onPlayTrack(t) } },
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            PrimaryButton("Import GPX / KML", actions.onImport, icon = HauntIcons.Upload, iconSize = 18.dp, height = 52.dp, shadow = true)
        }
    }
}

@Composable
private fun PlayButton(description: String, onClick: () -> Unit) {
    IconButton(HauntIcons.Play, description, onClick, iconSize = 16.dp, background = HauntTheme.colors.tile)
}

@Composable
internal fun Empty(text: String) {
    ListGroup {
        Text(text, Modifier.padding(18.dp), style = HauntTheme.type.caption, color = HauntTheme.colors.muted)
    }
}

