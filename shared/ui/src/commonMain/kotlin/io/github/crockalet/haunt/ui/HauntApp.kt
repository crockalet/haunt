package io.github.crockalet.haunt.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.components.GlassMode
import io.github.crockalet.haunt.ui.components.GlassVeil
import io.github.crockalet.haunt.ui.components.LocalGlassMode
import io.github.crockalet.haunt.ui.components.LocalHazeState
import io.github.crockalet.haunt.ui.components.LocalMorphScope
import io.github.crockalet.haunt.ui.components.MorphScope
import io.github.crockalet.haunt.ui.components.NoticeBanner
import io.github.crockalet.haunt.ui.map.HauntMap
import io.github.crockalet.haunt.ui.platform.PlatformBackHandler
import io.github.crockalet.haunt.ui.screens.ActivityLogScreen
import io.github.crockalet.haunt.ui.screens.ConnectAgentCommand
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
import io.github.crockalet.haunt.ui.screens.ServiceActions
import io.github.crockalet.haunt.ui.screens.ServiceScreen
import io.github.crockalet.haunt.ui.screens.ServiceUiState
import io.github.crockalet.haunt.ui.screens.SettingsActions
import io.github.crockalet.haunt.ui.screens.SettingsScreen
import io.github.crockalet.haunt.ui.screens.SettingsUiState
import io.github.crockalet.haunt.ui.state.DetectedCoordinates
import io.github.crockalet.haunt.ui.state.Format
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.JoystickSize
import io.github.crockalet.haunt.ui.state.MapStyle
import io.github.crockalet.haunt.ui.state.Notice
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.state.detectCoordinates
import io.github.crockalet.haunt.ui.theme.HauntMotion
import io.github.crockalet.haunt.ui.theme.HauntTheme
import io.github.crockalet.haunt.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/**
 * Haunt's UI entry point: full-screen map with the Glass chrome, plus Search, Library, Settings
 * and Onboarding sheets over the blurred map.
 *
 * @param controller engine; the UI only reads [HauntController.state] and calls its commands.
 * @param data app-provided lists and hooks (favourites, agent status…).
 * @param mapStyle what the map draws; Haunt's own light / dark style over OpenFreeMap by default.
 * @param parseCoordinates turns pasted text into coordinates; the app passes [detectCoordinates] with
 *   a reference point for short plus codes.
 * @param onThemeChange persist the theme override chosen in Settings.
 * @param onboardingActions drives onboarding when [HauntAppState.onboarding] is set.
 * @param liveBlur blur the map behind glass (Haze). Only works when the map draws through Compose;
 *   when false there is no blur source at all and glass uses denser tints instead.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HauntApp(
    controller: HauntController,
    modifier: Modifier = Modifier,
    data: HauntAppData = HauntAppData(),
    mapStyle: MapStyle = MapStyle.Default,
    parseCoordinates: (String) -> DetectedCoordinates? = { detectCoordinates(it) },
    state: HauntAppState = rememberHauntAppState(controller),
    onThemeChange: (ThemeMode) -> Unit = {},
    onboardingActions: OnboardingActions = OnboardingActions(),
    liveBlur: Boolean = true,
) {
    HauntTheme(state.theme) {
        val haze = if (liveBlur) rememberHazeState() else null
        val holder = state.map
        val colors = HauntTheme.colors

        PlatformBackHandler(enabled = state.screen != Screen.Map || holder.expanded) { state.back() }

        // Blur at reduced resolution: indistinguishable at these radii, far cheaper while things move.
        CompositionLocalProvider(LocalHazeState provides haze, LocalHazePerformanceMode provides HazePerformanceMode.Performance) {
            Box(modifier.fillMaxSize().background(colors.map)) {
                MapBackground(state, mapStyle, Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier))

                val onboarding = state.onboarding
                if (onboarding != null) {
                    GlassVeil(Modifier.fillMaxSize(), blurRadius = HauntDefaultsUi.onboardingBlur) {
                        OnboardingScreen(onboarding, onboardingActions)
                    }
                } else {
                    // Overlay screens sit on one blurred veil that only fades (never scale a blur: it is
                    // re-captured every frame). Their content is plain tint, so it can cross-fade and
                    // scale cheaply; elements marked with `morph` (search pill → search field) spring
                    // from one screen's bounds to the other's.
                    AnimatedVisibility(
                        visible = state.screen != Screen.Map,
                        enter = fadeIn(HauntMotion.smooth()),
                        exit = fadeOut(HauntMotion.snappy()),
                    ) {
                        GlassVeil(Modifier.fillMaxSize()) {}
                    }
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = state.screen,
                            transitionSpec = { screenTransition(initialState, targetState) },
                            label = "screen",
                        ) { screen ->
                            CompositionLocalProvider(LocalMorphScope provides MorphScope(this@SharedTransitionLayout, this)) {
                                if (screen == Screen.Map) {
                                    MapLayer(state, data)
                                } else {
                                    CompositionLocalProvider(LocalGlassMode provides GlassMode.Tint) {
                                        Sheet(screen, state, data, parseCoordinates, onThemeChange)
                                    }
                                }
                            }
                        }
                    }
                }

                Notices(holder.notice, holder::dismissNotice, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/** Applies [transform] to the Settings defaults and persists them. */
private fun HauntAppState.updateDefaults(data: HauntAppData, transform: (HauntDefaults) -> HauntDefaults) {
    map.defaults = transform(map.defaults)
    data.onDefaultsChange(map.defaults)
}

/**
 * Screen-to-screen motion: deeper screens grow in, going back shrinks them away. The map screen
 * only fades: its chrome is blurred glass, which must not be scaled.
 */
private fun AnimatedContentTransitionScope<Screen>.screenTransition(from: Screen, to: Screen): ContentTransform {
    val forward = to.depth > from.depth
    val enter = fadeIn(HauntMotion.smooth()) +
        if (to == Screen.Map) EnterTransition.None else scaleIn(HauntMotion.smooth(), initialScale = if (forward) 0.94f else 1.04f)
    val exit = fadeOut(HauntMotion.snappy()) +
        if (from == Screen.Map) ExitTransition.None else scaleOut(HauntMotion.smooth(), targetScale = if (forward) 1.04f else 0.94f)
    return enter.togetherWith(exit).using(SizeTransform(clip = false))
}

private val Screen.depth: Int
    get() = when (this) {
        Screen.Map -> 0
        Screen.Search, Screen.Library, Screen.Settings -> 1
        Screen.ActivityLog, Screen.Service -> 2
    }

/** The map alone in its own scope: engine ticks recompose it (and [MapLayer]), not the whole app. */
@Composable
private fun MapBackground(state: HauntAppState, style: MapStyle, modifier: Modifier) {
    val holder = state.map
    HauntMap(
        content = holder.mapContent,
        style = style,
        onLongPress = holder::onMapLongPress,
        modifier = modifier,
        bottomInset = state.mapBottomInset,
    )
}

@Composable
private fun MapLayer(state: HauntAppState, data: HauntAppData) {
    MapScreen(state = state.map.shown, actions = rememberMapActions(state, data))
}

/**
 * The map screen's callbacks, built once: they read the current UI state and [data] when called,
 * so [MapScreen] can skip when only the engine state changed.
 */
@Composable
internal fun rememberMapActions(state: HauntAppState, data: HauntAppData): MapActions {
    val holder = state.map
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val currentData by rememberUpdatedState(data)
    val canLocate = data.locateMe != null
    return remember(state, clipboard, canLocate) {
        MapActions(
            onModeSelect = holder::selectMode,
            onToggleExpanded = holder::toggleExpanded,
            onPlayPause = holder::playPause,
            onStart = holder::start,
            onStop = holder::stop,
            onSearch = { state.navigate(Screen.Search) },
            onLibrary = { state.navigate(Screen.Library) },
            onSettings = { state.navigate(Screen.Settings) },
            onCopyCoordinates = {
                val ui = holder.shown
                (ui.pin?.position ?: ui.map.fix)?.let { clipboard.setText(AnnotatedString(Format.coords(it))) }
            },
            onSaveFavourite = {
                val ui = holder.shown
                (ui.pin?.position ?: ui.map.fix)?.let { currentData.onSaveFavourite(Place(ui.pin?.title ?: "Dropped pin", it)) }
            },
            onSpeedPreset = holder::setSpeedPreset,
            onFollowRoads = holder::setFollowRoads,
            onLoop = holder::setLoop,
            onRate = holder::cycleRate,
            onCustomSpeed = holder::setCustomSpeed,
            onJoystick = holder::joystickInput,
            onJoystickMaxSpeed = holder::setJoystickMaxSpeed,
            onJoystickSize = { size -> state.updateDefaults(currentData) { it.copy(joystickSize = size) } },
            onFloatingJoystick = { on -> state.updateDefaults(currentData) { it.copy(floatingJoystick = on) } },
            onJoystickMoved = { x, y -> state.updateDefaults(currentData) { it.copy(joystickOffsetX = x, joystickOffsetY = y) } },
            onLocate = if (canLocate) {
                { currentData.locateMe?.let(holder::locate) }
            } else {
                null
            },
            onBottomChrome = { state.mapBottomInset = it },
        )
    }
}

/** The glass sheets over the blurred map: Search, Library, Settings and Settings' sub-screens. */
@Composable
private fun Sheet(
    screen: Screen,
    state: HauntAppState,
    data: HauntAppData,
    parseCoordinates: (String) -> DetectedCoordinates?,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val holder = state.map
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    when (screen) {
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
                    holder.pick(it.position, it.name)
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
                onSeeAllLog = { state.navigate(Screen.ActivityLog) },
                onCopyCommand = { clipboard.setText(AnnotatedString(ConnectAgentCommand)) },
                onService = state::editService,
                onTheme = {
                    state.theme = it
                    onThemeChange(it)
                },
                onUpdateRate = {
                    state.updateDefaults(data) { d -> d.copy(updateRateHz = d.updateRateHz.next(listOf(1, 2, 5, 10))) }
                },
                onAccuracy = {
                    state.updateDefaults(data) { d -> d.copy(accuracyMeters = d.accuracyMeters.next(listOf(3f, 5f, 10f, 20f))) }
                },
                onUnits = { state.updateDefaults(data) { it.copy(metric = !it.metric) } },
                onJoystickSize = { state.updateDefaults(data) { d -> d.copy(joystickSize = d.joystickSize.next(JoystickSize.entries)) } },
                onFloatingJoystick = { on -> state.updateDefaults(data) { it.copy(floatingJoystick = on) } },
            ),
        )
        Screen.ActivityLog -> ActivityLogScreen(
            log = data.activityLog,
            onBack = { state.back() },
            onClear = data.onClearLog,
        )
        Screen.Service -> state.service?.let { endpoint ->
            ServiceScreen(
                state = ServiceUiState(
                    endpoint = endpoint,
                    url = state.serviceUrl,
                    profile = state.serviceProfile,
                    urlError = state.serviceUrlError,
                    profileError = state.serviceProfileError,
                ),
                actions = ServiceActions(
                    onBack = { state.back() },
                    onUrlChange = { state.serviceUrl = it },
                    onProfileChange = { state.serviceProfile = it },
                    onReset = state::resetService,
                    onSave = { state.saveService(data.onServiceSave) },
                ),
            )
        }
        Screen.Map -> Unit
    }
}

/** The option after this one in [options] (wrapping; the first if this one isn't listed). */
private fun <T> T.next(options: List<T>): T = options[(options.indexOf(this) + 1) % options.size]

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
    val nearbyOf: LatLng? = detected?.position ?: holder.lastPosition
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
                holder.pick(it, labelFor(it))
                state.back()
            },
            onShowOnMap = {
                holder.showOnMap(it)
                state.back()
            },
            onSave = { data.onSaveFavourite(Place(labelFor(it) ?: "Saved place", it)) },
            onPlace = {
                holder.pick(it.position, it.name)
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
