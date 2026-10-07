package io.github.crockalet.haunt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.ui.screens.LibraryTab
import io.github.crockalet.haunt.ui.screens.OnboardingUiState
import androidx.compose.runtime.rememberCoroutineScope
import io.github.crockalet.haunt.ui.state.ControllerCommands
import io.github.crockalet.haunt.ui.state.HauntCommands
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.LicenceDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapStateHolder
import io.github.crockalet.haunt.ui.state.ServiceEndpoint
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.state.ServiceValidation
import io.github.crockalet.haunt.ui.theme.ThemeMode

/**
 * Destinations. The map is always underneath; the others are glass sheets over it.
 * [ActivityLog], [Service] and [DataLicences] are opened from Settings and go back to it;
 * [LicenceText] is opened from [DataLicences].
 */
enum class Screen { Map, Search, Library, Settings, ActivityLog, Service, DataLicences, LicenceText }

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
    commands: HauntCommands = ControllerCommands(controller),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) {
    val map = MapStateHolder(controller, initialLocal, defaults, commands, scope)

    var screen by mutableStateOf(initialScreen)
        private set
    var theme by mutableStateOf(initialTheme)
    var onboarding by mutableStateOf(onboarding)

    var searchQuery by mutableStateOf(initialQuery)
    var libraryTab by mutableStateOf(initialLibraryTab)
    var libraryFolder by mutableStateOf<String?>(null)

    /** How far up from the bottom the map keeps its attribution, clear of the toolbar (measured by the map screen). */
    var mapBottomInset by mutableStateOf(0.dp)

    /** The endpoint being edited on [Screen.Service], with the draft URL / profile. */
    var service by mutableStateOf<ServiceEndpoint?>(null)
        private set
    var serviceUrl by mutableStateOf("")
    var serviceProfile by mutableStateOf("")

    /** The licence or notice shown on [Screen.LicenceText]. */
    var licenceDoc by mutableStateOf<LicenceDoc?>(null)
        private set

    fun openLicenceDoc(doc: LicenceDoc) {
        licenceDoc = doc
        screen = Screen.LicenceText
    }

    fun navigate(to: Screen) {
        if (to == Screen.Search) searchQuery = ""
        screen = to
    }

    /** Opens the editor for [endpoint] (display-only rows without a kind are ignored). */
    fun editService(endpoint: ServiceEndpoint) {
        if (endpoint.kind == null) return
        service = endpoint
        serviceUrl = endpoint.value
        serviceProfile = endpoint.profile.orEmpty()
        screen = Screen.Service
    }

    /** Draft URL / profile errors; both null when the draft can be saved. */
    val serviceUrlError: String? get() = ServiceValidation.urlError(serviceUrl)
    val serviceProfileError: String? get() = if (service?.profile != null) ServiceValidation.profileError(serviceProfile) else null

    /** Validates the draft and, if valid, hands it to [save] and returns to Settings. Returns whether it saved. */
    fun saveService(save: (ServiceKind, url: String, profile: String?) -> Unit): Boolean {
        val endpoint = service ?: return false
        val kind = endpoint.kind ?: return false
        if (serviceUrlError != null || serviceProfileError != null) return false
        save(kind, serviceUrl.trim(), if (endpoint.profile != null) serviceProfile.trim() else null)
        screen = Screen.Settings
        return true
    }

    /** Puts the defaults back into the draft (saved only on [saveService]). */
    fun resetService() {
        val endpoint = service ?: return
        serviceUrl = endpoint.defaultValue
        endpoint.defaultProfile?.let { serviceProfile = it }
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
        screen = when (screen) {
            Screen.ActivityLog, Screen.Service, Screen.DataLicences -> Screen.Settings
            Screen.LicenceText -> Screen.DataLicences
            else -> Screen.Map
        }
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
    commands: HauntCommands = ControllerCommands(controller),
): HauntAppState {
    val scope = rememberCoroutineScope()
    val state = remember(controller, commands) {
        HauntAppState(
            controller, initialScreen, initialTheme, initialLocal, defaults, onboarding,
            commands = commands, scope = scope,
        )
    }
    // Collected outside composition: an engine tick must not recompose the caller of this function.
    LaunchedEffect(state) { controller.state.collect(state.map::onEngineState) }
    return state
}
