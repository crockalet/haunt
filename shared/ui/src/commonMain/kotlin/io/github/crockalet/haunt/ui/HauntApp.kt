package io.github.crockalet.haunt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.components.GlassVeil
import io.github.crockalet.haunt.ui.components.LocalHazeState
import io.github.crockalet.haunt.ui.map.HauntMap
import io.github.crockalet.haunt.ui.platform.PlatformBackHandler
import io.github.crockalet.haunt.ui.screens.DetectedUi
import io.github.crockalet.haunt.ui.screens.LibraryActions
import io.github.crockalet.haunt.ui.screens.LibraryScreen
import io.github.crockalet.haunt.ui.screens.LibraryUiState
import io.github.crockalet.haunt.ui.screens.MapActions
import io.github.crockalet.haunt.ui.screens.MapScreen
import io.github.crockalet.haunt.ui.screens.OnboardingActions
import io.github.crockalet.haunt.ui.screens.OnboardingScreen
import io.github.crockalet.haunt.ui.screens.SearchActions
import io.github.crockalet.haunt.ui.screens.SearchScreen
import io.github.crockalet.haunt.ui.screens.SearchUiState
import io.github.crockalet.haunt.ui.screens.SettingsActions
import io.github.crockalet.haunt.ui.screens.SettingsScreen
import io.github.crockalet.haunt.ui.screens.SettingsUiState
import io.github.crockalet.haunt.ui.screens.ConnectAgentCommand
import io.github.crockalet.haunt.ui.state.DetectedCoordinates
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.MapStyle
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.state.collectUiState
import io.github.crockalet.haunt.ui.state.parseSimpleCoordinates
import io.github.crockalet.haunt.ui.theme.HauntTheme
import io.github.crockalet.haunt.ui.theme.ThemeMode

/**
 * Haunt's UI entry point: full-screen map with the Glass chrome, plus Search, Library, Settings
 * and Onboarding sheets over the blurred map.
 *
 * @param controller engine; the UI only reads [HauntController.state] and calls its commands.
 * @param data app-provided lists and hooks (favourites, agent status…).
 * @param mapStyle MapLibre style URLs (light/dark). OpenFreeMap by default.
 * @param parseCoordinates turns pasted text into coordinates (the full parser is supplied by the app).
 * @param onThemeChange persist the theme override chosen in Settings.
 * @param onboardingActions drives onboarding when [HauntAppState.onboarding] is set.
 */
@Composable
fun HauntApp(
    controller: HauntController,
    modifier: Modifier = Modifier,
    data: HauntAppData = HauntAppData(),
    mapStyle: MapStyle = MapStyle.OpenFreeMap,
    parseCoordinates: (String) -> DetectedCoordinates? = ::parseSimpleCoordinates,
    state: HauntAppState = rememberHauntAppState(controller),
    onThemeChange: (ThemeMode) -> Unit = {},
    onboardingActions: OnboardingActions = OnboardingActions(),
) {
    HauntTheme(state.theme) {
        val haze = rememberHazeState()
        val holder = state.map
        val ui = holder.collectUiState()
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        val colors = HauntTheme.colors

        PlatformBackHandler(enabled = state.screen != Screen.Map || ui.expanded) { state.back() }

        CompositionLocalProvider(LocalHazeState provides haze) {
            Box(modifier.fillMaxSize().background(colors.map)) {
                HauntMap(
                    content = ui.map,
                    styleUrl = mapStyle.url(colors.isDark),
                    onLongPress = holder::onMapLongPress,
                    modifier = Modifier.fillMaxSize().hazeSource(haze),
                )

                val onboarding = state.onboarding
                when {
                    onboarding != null -> GlassVeil(Modifier.fillMaxSize(), blurRadius = HauntDefaultsUi.onboardingBlur) {
                        OnboardingScreen(onboarding, onboardingActions)
                    }
                    state.screen == Screen.Map -> MapScreen(
                        state = ui,
                        actions = remember(holder, state) {
                            MapActions(
                                onModeSelect = holder::selectMode,
                                onToggleExpanded = holder::toggleExpanded,
                                onPlayPause = holder::playPause,
                                onStop = holder::stop,
                                onSearch = { state.navigate(Screen.Search) },
                                onLibrary = { state.navigate(Screen.Library) },
                                onSettings = { state.navigate(Screen.Settings) },
                                onSpeedPreset = holder::setSpeedPreset,
                                onFollowRoads = holder::setFollowRoads,
                                onLoop = holder::setLoop,
                                onRate = holder::cycleRate,
                                onJoystick = holder::joystickInput,
                                onJoystickMaxSpeed = holder::setJoystickMaxSpeed,
                            )
                        }.copy(
                            onCopyCoordinates = {
                                ui.map.fix?.let { clipboard.setText(AnnotatedString(Format.coords(it))) }
                            },
                            onSaveFavourite = {
                                ui.map.fix?.let { data.onSaveFavourite(Place(ui.pin?.title ?: "Dropped pin", it)) }
                            },
                        ),
                    )
                    else -> GlassVeil(Modifier.fillMaxSize()) {
                        when (state.screen) {
                            Screen.Search -> Search(state, data, parseCoordinates) { clipboard.getText()?.text }
                            Screen.Library -> LibraryScreen(
                                state = LibraryUiState(
                                    tab = state.libraryTab,
                                    folders = data.folders,
                                    folder = state.libraryFolder,
                                    favourites = data.favourites,
                                    history = data.history,
                                    tracks = data.tracks,
                                ),
                                actions = LibraryActions(
                                    onBack = { state.back() },
                                    onTab = { state.libraryTab = it },
                                    onFolder = { state.libraryFolder = it },
                                    onHaunt = {
                                        holder.hauntAt(it.position, it.name)
                                        state.back()
                                    },
                                    onPlayTrack = {
                                        if (it.points.size >= 2) holder.loadRoute(it.points, it.name)
                                        state.back()
                                    },
                                    onImport = data.onImportTrack,
                                ),
                            )
                            Screen.Settings -> SettingsScreen(
                                state = SettingsUiState(
                                    adbEnabled = data.adbControlEnabled,
                                    connection = data.agentConnection,
                                    log = data.activityLog,
                                    services = data.services,
                                    theme = state.theme,
                                    defaults = holder.defaults,
                                ),
                                actions = SettingsActions(
                                    onBack = { state.back() },
                                    onAdbChange = data.onAdbControlChange,
                                    onSeeAllLog = data.onSeeAllLog,
                                    onCopyCommand = { clipboard.setText(AnnotatedString(ConnectAgentCommand)) },
                                    onService = data.onServiceClick,
                                    onTheme = {
                                        state.theme = it
                                        onThemeChange(it)
                                    },
                                    onUpdateRate = {
                                        val rates = listOf(1, 2, 5, 10)
                                        val d = holder.defaults
                                        holder.defaults = d.copy(updateRateHz = rates[(rates.indexOf(d.updateRateHz) + 1) % rates.size])
                                    },
                                    onAccuracy = {
                                        val values = listOf(3f, 5f, 10f, 20f)
                                        val d = holder.defaults
                                        holder.defaults = d.copy(accuracyMeters = values[(values.indexOf(d.accuracyMeters) + 1) % values.size])
                                    },
                                    onUnits = { holder.defaults = holder.defaults.copy(metric = !holder.defaults.metric) },
                                ),
                            )
                            Screen.Map -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Search(
    state: HauntAppState,
    data: HauntAppData,
    parseCoordinates: (String) -> DetectedCoordinates?,
    readClipboard: () -> String?,
) {
    val holder = state.map
    val query = state.searchQuery
    val detected = remember(query) { query.takeIf { it.isNotBlank() }?.let(parseCoordinates) }
    val results = remember(query, detected) { if (detected == null && query.isNotBlank()) data.search(query) else emptyList() }
    val nearbyOf: LatLng? = detected?.position ?: holder.local.lastPosition
    SearchScreen(
        state = SearchUiState(
            query = query,
            detected = detected?.let { DetectedUi(it.position, data.areaName(it.position)) },
            results = results,
            nearby = nearbyOf?.let(data.nearby).orEmpty(),
            recent = if (detected == null && query.isBlank()) data.recent else if (detected != null) data.recent else emptyList(),
        ),
        actions = SearchActions(
            onQueryChange = { state.searchQuery = it },
            onBack = { state.back() },
            onHauntHere = {
                holder.hauntAt(it, data.areaName(it))
                state.back()
            },
            onShowOnMap = {
                holder.showOnMap(it)
                state.back()
            },
            onSave = { data.onSaveFavourite(Place(data.areaName(it) ?: "Saved place", it)) },
            onPlace = {
                holder.hauntAt(it.position, it.name)
                state.back()
            },
            onPaste = { readClipboard()?.let { state.searchQuery = it } },
        ),
    )
}

/** Small UI constants that aren't theme tokens. */
internal object HauntDefaultsUi {
    val onboardingBlur = androidx.compose.ui.unit.Dp(18f)
}
