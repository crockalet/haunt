package io.github.crockalet.haunt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.ui.screens.LibraryTab
import io.github.crockalet.haunt.ui.screens.OnboardingUiState
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.theme.ThemeMode

/** Top-level destinations. The map is always underneath; the others are glass sheets over it. */
enum class Screen { Map, Search, Library, Settings }

/**
 * App-level UI state: navigation, theme override, per-screen UI state and the map presenter.
 * Plain state-based navigation; no navigation library.
 */
@Stable
class HauntAppState(
    controller: HauntController,
    initialScreen: Screen = Screen.Map,
    initialTheme: ThemeMode = ThemeMode.System,
    initialLocal: LocalUiState = LocalUiState(),
    defaults: HauntDefaults = HauntDefaults(),
    onboarding: OnboardingUiState? = null,
    initialQuery: String = "",
    initialLibraryTab: LibraryTab = LibraryTab.Favourites,
) {
    val map = MapStateHolder(controller, initialLocal, defaults)

    var screen by mutableStateOf(initialScreen)
        private set
    var theme by mutableStateOf(initialTheme)
    var onboarding by mutableStateOf(onboarding)

    var searchQuery by mutableStateOf(initialQuery)
    var libraryTab by mutableStateOf(initialLibraryTab)
    var libraryFolder by mutableStateOf<String?>(null)

    fun navigate(to: Screen) {
        if (to == Screen.Search) searchQuery = ""
        screen = to
    }

    /** Returns true when back was handled (i.e. we weren't on the map). */
    fun back(): Boolean {
        if (screen == Screen.Map) {
            if (map.local.expanded) {
                map.setExpanded(false)
                return true
            }
            return false
        }
        screen = Screen.Map
        return true
    }
}

@Composable
fun rememberHauntAppState(
    controller: HauntController,
    initialScreen: Screen = Screen.Map,
    initialTheme: ThemeMode = ThemeMode.System,
    initialLocal: LocalUiState = LocalUiState(),
    defaults: HauntDefaults = HauntDefaults(),
    onboarding: OnboardingUiState? = null,
): HauntAppState {
    val state = remember(controller) {
        HauntAppState(controller, initialScreen, initialTheme, initialLocal, defaults, onboarding)
    }
    val engine by controller.state.collectAsState()
    LaunchedEffect(state, engine) { state.map.onEngineState(engine) }
    return state
}
