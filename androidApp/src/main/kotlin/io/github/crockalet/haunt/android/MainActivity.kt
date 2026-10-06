package io.github.crockalet.haunt.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import io.github.crockalet.haunt.android.data.TrackImport
import io.github.crockalet.haunt.android.ui.AndroidCommands
import io.github.crockalet.haunt.android.ui.OnboardingFlow
import io.github.crockalet.haunt.android.ui.OnboardingInputs
import io.github.crockalet.haunt.android.ui.UiMapping
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.PlacesSearchParams
import io.github.crockalet.haunt.ui.HauntApp
import io.github.crockalet.haunt.ui.HauntAppState
import io.github.crockalet.haunt.ui.Screen
import io.github.crockalet.haunt.ui.rememberHauntAppState
import io.github.crockalet.haunt.ui.screens.LibraryTab
import io.github.crockalet.haunt.ui.screens.OnboardingActions
import io.github.crockalet.haunt.ui.screens.OnboardingStep
import io.github.crockalet.haunt.ui.screens.OnboardingUiState
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.Notice
import io.github.crockalet.haunt.ui.state.PlaceSearch
import io.github.crockalet.haunt.ui.state.detectCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId

/**
 * Hosts the shared Compose UI on top of the process-wide [HauntRuntime]: engine state, favourites,
 * history, tracks, search, settings and the agent activity log come from the runtime; commands go
 * through its API (see [AndroidCommands]). Also runs onboarding and opens the ADB control socket.
 */
class MainActivity : ComponentActivity() {
    private lateinit var runtime: HauntRuntime

    /** Latest onboarding snapshot; null until the first check finishes. */
    private val onboarding = MutableStateFlow<OnboardingInputs?>(null)

    /** One-off UI events from outside composition (imports, saves). */
    private val uiEvents = Channel<UiEvent>(Channel.BUFFERED)

    private var locationDenials = 0

    private sealed interface UiEvent {
        data class Show(val notice: Notice) : UiEvent
        data object ShowTracks : UiEvent
    }

    private val prefs by lazy { getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (Manifest.permission.POST_NOTIFICATIONS in result) prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
        if (!runtime.hasLocationPermission()) locationDenials++
        refreshOnboarding()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importTrack)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        runtime = HauntRuntime.from(this)
        setContent { App() }
    }

    override fun onStart() {
        super.onStart()
        // Open the ADB socket while the app is visible so `haunt` connects instantly.
        if (runtime.settings.value.adbControlEnabled) runtime.startControlService()
    }

    override fun onResume() {
        super.onResume()
        refreshOnboarding()
    }

    @Composable
    private fun App() {
        val settings by runtime.settings.collectAsState()
        val favourites by runtime.favorites.favorites.collectAsState()
        val history by runtime.history.entries.collectAsState()
        val tracks by runtime.tracks.tracks.collectAsState()
        val log by runtime.activityLog.entries.collectAsState()
        val connections by runtime.controlServer.connections.collectAsState()
        val inputs by onboarding.collectAsState()

        val commands = remember {
            AndroidCommands(runtime.api, runtime.controller, runtime.router, runtime::checkCanMock, runtime::startMockingService)
        }
        val state = rememberHauntAppState(
            controller = runtime.controller,
            initialTheme = UiMapping.theme(settings.theme),
            defaults = UiMapping.defaults(settings),
            commands = commands,
        )
        val scope = rememberCoroutineScope()
        val placeSearch = remember {
            PlaceSearch(
                scope = scope,
                search = { query, near ->
                    runtime.api.searchPlaces(PlacesSearchParams(query, near, SEARCH_LIMIT)).map { UiMapping.place(it, near) }
                },
                nearby = { p -> withContext(Dispatchers.IO) { runtime.geocoder.reverse(p, NEARBY_LIMIT) }.map { UiMapping.place(it, p) } },
            )
        }

        LaunchedEffect(settings) {
            state.theme = UiMapping.theme(settings.theme)
            state.map.defaults = UiMapping.defaults(settings, state.map.defaults)
        }
        LaunchedEffect(inputs) {
            state.onboarding = inputs?.let(OnboardingFlow::step)?.let { OnboardingUiState.forStep(it) }
        }
        // While onboarding waits for a setting, keep checking (the user may come back via Recents).
        val onboardingShown = state.onboarding != null
        LaunchedEffect(onboardingShown) {
            while (onboardingShown) {
                delay(RECHECK_MILLIS)
                refreshOnboarding()
            }
        }
        LaunchedEffect(Unit) {
            launch {
                runtime.events.collect { e ->
                    if (e is HauntEvent.ErrorEvent) state.map.showNotice(Notice(e.message, e.hint))
                }
            }
            uiEvents.receiveAsFlow().collect { e ->
                when (e) {
                    is UiEvent.Show -> state.map.showNotice(e.notice)
                    UiEvent.ShowTracks -> {
                        state.navigate(Screen.Library)
                        state.libraryTab = LibraryTab.Tracks
                    }
                }
            }
        }

        val zone = remember { ZoneId.systemDefault() }
        val historyPlaces = remember(history) { UiMapping.history(history, System.currentTimeMillis(), zone) }
        val data = HauntAppData(
            folders = remember(favourites) { UiMapping.folders(favourites) },
            favourites = remember(favourites) { favourites.map(UiMapping::favourite) },
            history = historyPlaces,
            tracks = remember(tracks) { tracks.map(UiMapping::track) },
            recent = historyPlaces.take(RECENT_COUNT),
            placeSearch = placeSearch,
            adbControlEnabled = settings.adbControlEnabled,
            onAdbControlChange = { enabled ->
                runtime.setAdbControlEnabled(enabled)
                if (enabled) runtime.startControlService()
            },
            agentConnection = remember(connections, log) { UiMapping.agentConnection(connections, log, zone) },
            activityLog = remember(log) { UiMapping.log(log, zone) },
            services = remember(settings) { UiMapping.services(settings) },
            onImportTrack = { importLauncher.launch(arrayOf("*/*")) },
            onSaveFavourite = { place -> saveFavourite(place.name, place.position) },
            onDefaultsChange = { d -> runtime.updateSettings { UiMapping.applyDefaults(it, d) } },
            onServiceSave = { kind, url, profile -> runtime.updateSettings { UiMapping.applyService(it, kind, url, profile) } },
            onClearLog = runtime.activityLog::clear,
        )

        HauntApp(
            controller = runtime.controller,
            data = data,
            mapStyle = UiMapping.mapStyle(settings),
            parseCoordinates = { detectCoordinates(it, runtime.lastFix?.position ?: state.map.local.lastPosition) },
            state = state,
            onThemeChange = { mode -> runtime.updateSettings { it.copy(theme = UiMapping.theme(mode)) } },
            onboardingActions = onboardingActions(state, inputs?.let(OnboardingFlow::step)),
        )
    }

    private fun onboardingActions(state: HauntAppState, step: OnboardingStep?) = OnboardingActions(
        onPrimary = {
            when (step) {
                OnboardingStep.DeveloperOptions -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
                OnboardingStep.SelectMockApp -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                OnboardingStep.Permissions -> requestPermissions()
                OnboardingStep.Done, null -> {
                    prefs.edit { putBoolean(KEY_ONBOARDING_DONE, true) }
                    refreshOnboarding()
                }
            }
        },
        onHelp = {
            val help = when (step) {
                OnboardingStep.DeveloperOptions -> Notice(
                    "Build number is in Settings → About phone (on some phones under Software information).",
                    "Tap it seven times; you may need to enter your PIN.",
                    error = false,
                )
                OnboardingStep.SelectMockApp -> Notice(
                    "Developer options are in Settings → System, or at the bottom of Settings.",
                    "If they're missing, tap Build number in About phone seven times first.",
                    error = false,
                )
                OnboardingStep.Permissions -> Notice(
                    "Android 14+ needs location permission for Haunt's location service.",
                    "Notifications show what Haunt is faking and let you stop it.",
                    error = false,
                )
                else -> null
            }
            help?.let(state.map::showNotice)
        },
    )

    private fun refreshOnboarding() {
        lifecycleScope.launch {
            onboarding.value = withContext(Dispatchers.Default) {
                val r = runtime.readiness()
                OnboardingInputs(
                    developerOptionsEnabled = developerOptionsEnabled(),
                    mockAppSelected = r.mockAppSelected,
                    locationPermission = r.locationPermission,
                    notificationPermission = r.notificationPermission,
                    notificationAsked = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false),
                    completedBefore = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
                )
            }
        }
    }

    private fun developerOptionsEnabled(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    private fun requestPermissions() {
        if (locationDenials >= 2 && !runtime.hasLocationPermission()) {
            // Denied twice ("don't ask again"): only the app's settings page can grant it now.
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            return
        }
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openSettings(action: String, data: Uri? = null) {
        val intent = Intent(action).apply { data?.let(::setData) }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $action", e)
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun saveFavourite(name: String, position: LatLng) {
        lifecycleScope.launch {
            val notice = withContext(Dispatchers.IO) {
                try {
                    val f = runtime.favorites.save(name, position)
                    Notice("Saved “${f.name}” to favourites", error = false)
                } catch (e: Exception) {
                    Notice("Couldn't save the favourite", e.message)
                }
            }
            uiEvents.send(UiEvent.Show(notice))
        }
    }

    private fun importTrack(uri: Uri) {
        lifecycleScope.launch {
            val notice = withContext(Dispatchers.IO) {
                try {
                    val text = contentResolver.openInputStream(uri)?.use(::readBounded)
                        ?: throw IllegalArgumentException("Couldn't open the file")
                    val parsed = TrackImport.parse(text)
                    val saved = runtime.tracks.add(parsed.routes, parsed.format, TrackImport.nameFromFile(displayName(uri)))
                    uiEvents.send(UiEvent.ShowTracks)
                    Notice(
                        if (saved.size == 1) "Imported “${saved.single().name}”" else "Imported ${saved.size} tracks",
                        "Tap ▶ to play it on the map.",
                        error = false,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Import failed", e)
                    Notice("Couldn't import the track", e.message)
                }
            }
            uiEvents.send(UiEvent.Show(notice))
        }
    }

    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            require(out.size() <= MAX_TRACK_BYTES) { "The file is too large (max ${MAX_TRACK_BYTES / 1_000_000} MB)" }
        }
        return out.toByteArray().decodeToString()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private companion object {
        const val TAG = "HauntMain"
        const val UI_PREFS = "haunt_ui"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val RECHECK_MILLIS = 2_000L
        const val SEARCH_LIMIT = 8
        const val NEARBY_LIMIT = 5
        const val RECENT_COUNT = 5
        const val MAX_TRACK_BYTES = 20_000_000
    }
}
