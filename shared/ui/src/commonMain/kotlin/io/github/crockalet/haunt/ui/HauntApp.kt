package io.github.crockalet.haunt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.NoticeBanner
import io.github.crockalet.haunt.ui.state.Notice
import kotlinx.coroutines.delay
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
import io.github.crockalet.haunt.ui.state.detectCoordinates
import io.github.crockalet.haunt.ui.theme.HauntTheme
import io.github.crockalet.haunt.ui.theme.ThemeMode

/**
 * Haunt's UI entry point: full-screen map with the Glass chrome, plus Search, Library, Settings
 * and Onboarding sheets over the blurred map.
 *
 * @param controller engine; the UI only reads [HauntController.state] and calls its commands.
 * @param data app-provided lists and hooks (favourites, agent status…).
 * @param mapStyle MapLibre style URLs (light/dark). OpenFreeMap by default.
 * @param parseCoordinates turns pasted text into coordinates; the app passes [detectCoordinates] with
 *   a reference point for short plus codes.
 * @param onThemeChange persist the theme override chosen in Settings.
 * @param onboardingActions drives onboarding when [HauntAppState.onboarding] is set.
 */
@Composable
fun HauntApp(
    controller: HauntController,
    modifier: Modifier = Modifier,
    data: HauntAppData = HauntAppData(),
    mapStyle: MapStyle = MapStyle.OpenFreeMap,
    parseCoordinates: (String) -> DetectedCoordinates? = { detectCoordinates(it) },
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
                                onCustomSpeed = holder::setCustomSpeed,
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
                                        if (it.points.size >= 2 || (it.route?.points?.size ?: 0) >= 2) holder.loadTrack(it)
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
                                        data.onDefaultsChange(holder.defaults)
                                    },
                                    onAccuracy = {
                                        val values = listOf(3f, 5f, 10f, 20f)
                                        val d = holder.defaults
                                        holder.defaults = d.copy(accuracyMeters = values[(values.indexOf(d.accuracyMeters) + 1) % values.size])
                                        data.onDefaultsChange(holder.defaults)
                                    },
                                    onUnits = {
                                        holder.defaults = holder.defaults.copy(metric = !holder.defaults.metric)
                                        data.onDefaultsChange(holder.defaults)
                                    },
                                ),
                            )
                            Screen.Map -> Unit
                        }
                    }
                }

                Notices(holder.notice, holder::dismissNotice, Modifier.align(Alignment.TopCenter))
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
    val nearbyOf: LatLng? = detected?.position ?: holder.local.lastPosition
    val placeSearch = data.placeSearch

    val results: List<Place>
    val nearby: List<Place>
    val areaName: (LatLng) -> String?
    val notice: String?
    if (placeSearch != null) {
        LaunchedEffect(placeSearch, query, detected, nearbyOf) {
            placeSearch.update(if (detected == null) query else "", nearbyOf)
        }
        val s by placeSearch.state.collectAsState()
        val current = detected == null && s.query == query.trim()
        results = if (current) s.results else emptyList()
        nearby = s.nearbyFor(nearbyOf)
        areaName = { s.areaFor(it) ?: data.areaName(it) }
        notice = when {
            detected != null || query.isBlank() -> null
            s.searching && results.isEmpty() -> "Searching…"
            current && s.error != null -> s.error
            current && results.isEmpty() && query.trim().length >= 2 -> "No places found"
            else -> null
        }
    } else {
        results = remember(query, detected) { if (detected == null && query.isNotBlank()) data.search(query) else emptyList() }
        nearby = nearbyOf?.let(data.nearby).orEmpty()
        areaName = data.areaName
        notice = null
    }

    fun labelFor(p: LatLng) = detected?.name?.takeIf { p == detected.position } ?: areaName(p)

    SearchScreen(
        state = SearchUiState(
            query = query,
            detected = detected?.let { DetectedUi(it.position, areaName(it.position), it.label) },
            results = results,
            nearby = nearby,
            recent = if (detected == null && query.isBlank()) data.recent else if (detected != null) data.recent else emptyList(),
            notice = notice,
        ),
        actions = SearchActions(
            onQueryChange = { state.searchQuery = it },
            onBack = { state.back() },
            onHauntHere = {
                holder.hauntAt(it, labelFor(it))
                state.back()
            },
            onShowOnMap = {
                holder.showOnMap(it)
                state.back()
            },
            onSave = { data.onSaveFavourite(Place(labelFor(it) ?: "Saved place", it)) },
            onPlace = {
                holder.hauntAt(it.position, it.name)
                state.back()
            },
            onPaste = { readClipboard()?.let { state.searchQuery = it } },
        ),
    )
}

/** The current [Notice] as a toast under the search pill and status chip; auto-dismissed after a while. */
@Composable
private fun Notices(notice: Notice?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(if (notice.hint != null) 9_000 else 5_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        notice != null,
        modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp, end = 12.dp, top = 112.dp),
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        // Keep showing the last notice while it animates out.
        val last = remember { mutableStateOf(notice) }
        if (notice != null) last.value = notice
        last.value?.let { NoticeBanner(it, onDismiss) }
    }
}

/** Small UI constants that aren't theme tokens. */
internal object HauntDefaultsUi {
    val onboardingBlur = androidx.compose.ui.unit.Dp(18f)
}
