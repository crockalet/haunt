package io.github.crockalet.haunt.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.crockalet.haunt.android.control.AdbGatedHauntApi
import io.github.crockalet.haunt.android.control.AndroidHauntApi
import io.github.crockalet.haunt.android.control.ControlServer
import io.github.crockalet.haunt.android.control.EventHub
import io.github.crockalet.haunt.android.control.EventPipeline
import io.github.crockalet.haunt.android.control.MockingEnvironment
import io.github.crockalet.haunt.android.control.TrackedController
import io.github.crockalet.haunt.android.control.summarizeParams
import io.github.crockalet.haunt.android.data.ActivityLog
import io.github.crockalet.haunt.android.data.ActivitySource
import io.github.crockalet.haunt.android.data.FavoritesStore
import io.github.crockalet.haunt.android.inject.FlavourInjection
import io.github.crockalet.haunt.android.inject.InjectionPipeline
import io.github.crockalet.haunt.android.inject.InjectionStatus
import io.github.crockalet.haunt.android.net.Geocoder
import io.github.crockalet.haunt.android.net.OsrmRouter
import io.github.crockalet.haunt.android.net.PhotonGeocoder
import io.github.crockalet.haunt.android.net.Router
import io.github.crockalet.haunt.android.net.UrlConnectionHttpClient
import io.github.crockalet.haunt.android.net.hauntUserAgent
import io.github.crockalet.haunt.android.settings.HauntSettings
import io.github.crockalet.haunt.android.settings.SettingsStore
import io.github.crockalet.haunt.android.settings.SharedPreferencesKeyValueStore
import io.github.crockalet.haunt.core.DefaultHauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.protocol.HauntApi
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.Protocol
import io.github.crockalet.haunt.protocol.RpcCallListener
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.RpcServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Process-wide Haunt runtime: the single controller and everything around it. Obtain it with
 * [HauntRuntime.from]; it lives as long as the process (created by [HauntApplication]).
 *
 * ## For the UI (MainActivity / shared/ui)
 * - **State:** [state] (`HauntState`), [injectionStatus] (are fixes reaching the platform?),
 *   [events] (same event stream agents see), [lastFix].
 * - **Commands:** prefer [api] (suspend; validates, geocodes, routes, checks permissions, starts the
 *   foreground service; throws [RpcException] with a `hint`), e.g. `runtime.api.setLocation(SetLocationParams(lat, lng))`.
 *   For low-level/high-frequency control (joystick) use [controller] — always this tracked one,
 *   never a raw `DefaultHauntController` — then [startMockingService] is called automatically when
 *   the state leaves Idle.
 * - **Settings:** [settings] (StateFlow) and [updateSettings] / [setAdbControlEnabled].
 * - **Agent activity log:** [activityLog]`.entries` (StateFlow, newest last) and `clear()`.
 * - **Favourites:** [favorites] (`favorites` StateFlow, `save`, `delete`; file I/O → call off main).
 * - **Onboarding checks:** [mockAppSelected], [hasLocationPermission], [hasNotificationPermission],
 *   [readiness].
 * - **Services:** [startControlService] (open the ADB socket now), [startMockingService], [stopMocking].
 */
class HauntRuntime internal constructor(context: Context) {
    private val app: Context = context.applicationContext

    /** Application scope (Default dispatcher, supervisor). */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in runtime scope", e) },
    )

    val appVersion: String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            legacyVersionName()
        }
    }.getOrNull() ?: "0"

    val flavour: String = FlavourInjection.FLAVOUR

    // --- settings, log, favourites ----------------------------------------------------------------

    private val settingsStore = SettingsStore(SharedPreferencesKeyValueStore(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)))

    val settings: StateFlow<HauntSettings> = settingsStore.settings

    fun updateSettings(transform: (HauntSettings) -> HauntSettings): HauntSettings = settingsStore.update(transform)

    fun setAdbControlEnabled(enabled: Boolean) = settingsStore.setAdbControlEnabled(enabled)

    val activityLog = ActivityLog()

    val favorites = FavoritesStore(File(app.filesDir, FAVORITES_FILE))

    // --- controller & events ----------------------------------------------------------------------

    private val eventHub = EventHub()

    private val engine = DefaultHauntController(
        scope = scope,
        tickInterval = settings.value.updateIntervalMillis.milliseconds,
        defaults = settings.value.defaults,
    )

    /** The process-wide controller (route-tracking decorator). Drive Haunt only through this or [api]. */
    val controller = TrackedController(engine, eventHub::emit)

    val state: StateFlow<HauntState> get() = controller.state

    /** Events as agents see them (fix, state, routeProgress, routeFinished, error). */
    val events: SharedFlow<HauntEvent> = eventHub.events

    val lastFix get() = controller.lastFix

    @Suppress("unused")
    private val eventPipeline = EventPipeline(scope, controller, eventHub)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val injection = InjectionPipeline(
        scope = scope,
        controller = controller,
        dispatcher = Dispatchers.IO.limitedParallelism(1),
        injectorFactory = { FlavourInjection.createInjector(app) },
        onDenied = { status ->
            when (status) {
                is InjectionStatus.Denied -> reportError(RpcException.mockAppNotSelected())
                is InjectionStatus.Failed -> reportError(RpcException.unavailable("Location injection failed: ${status.message}"))
                else -> Unit
            }
        },
    )

    val injectionStatus: StateFlow<InjectionStatus> = injection.status

    // --- network ----------------------------------------------------------------------------------

    private val http = UrlConnectionHttpClient(hauntUserAgent(appVersion))

    val geocoder: Geocoder = PhotonGeocoder(http) { settings.value.searchUrl }

    val router: Router = OsrmRouter(http, { settings.value.routingUrl }, { settings.value.routingProfile })

    // --- API & ADB control ------------------------------------------------------------------------

    private val environment = object : MockingEnvironment {
        override val appVersion get() = this@HauntRuntime.appVersion
        override val flavour get() = this@HauntRuntime.flavour
        override fun mockAppSelected() = this@HauntRuntime.mockAppSelected()
        override fun checkCanMock() = this@HauntRuntime.checkCanMock()
        override fun onMockingStarted() = startMockingService()
    }

    /** The full API (no ADB gate) — for the UI. Suspend functions; throw [RpcException]. */
    val api: HauntApi = AndroidHauntApi(controller, favorites, geocoder, router, environment)

    /** [api] behind the "Allow ADB control" switch — what the socket and broadcasts use. */
    val adbApi: HauntApi = AdbGatedHauntApi(api) { settings.value.adbControlEnabled }

    private val rpcServer = RpcServer(
        api = adbApi,
        events = events,
        listener = RpcCallListener { method, params, error ->
            activityLog.record(ActivitySource.Socket, method, summarizeParams(params), error)
        },
    )

    /** The control socket; hosted (started/stopped) by [ControlService]. */
    val controlServer = ControlServer(scope, rpcServer, activityLog)

    private val foregroundServices = MutableStateFlow(0)

    init {
        // Live-apply accuracy/altitude defaults.
        scope.launch {
            settings.map { it.defaults }.distinctUntilChanged().collect { engine.defaults = it }
        }
        // Whatever starts mocking (UI, socket, broadcast), keep the foreground service running.
        scope.launch {
            state.map { it !is HauntState.Idle }.distinctUntilChanged().collect { active -> if (active) startMockingService() }
        }
        // Keep the shared notification current while a service shows it.
        @OptIn(FlowPreview::class)
        scope.launch {
            combine(state.sample(NOTIFICATION_INTERVAL_MS.milliseconds), controlServer.connections, settings, foregroundServices) { s, c, st, fg ->
                if (fg > 0) Triple(s, c, st) else null
            }.collect { snapshot -> snapshot?.let { (s, c, st) -> refreshNotification(s, c, st) } }
        }
    }

    // --- checks -----------------------------------------------------------------------------------

    /** Whether Haunt is the selected mock location app (AppOps, with a test-provider probe fallback). */
    fun mockAppSelected(): Boolean = MockLocationCheck.isMockAppSelected(app)

    fun hasLocationPermission(): Boolean = MockLocationCheck.hasLocationPermission(app)

    fun hasNotificationPermission(): Boolean = MockLocationCheck.hasNotificationPermission(app)

    /** Everything the onboarding wizard needs in one snapshot. */
    data class Readiness(
        val mockAppSelected: Boolean,
        val locationPermission: Boolean,
        /** False only on API 34+ without location permission: the location foreground service can't start. */
        val canRunLocationService: Boolean,
        val notificationPermission: Boolean,
        val adbControlEnabled: Boolean,
    ) {
        val ready: Boolean get() = mockAppSelected && canRunLocationService
    }

    fun readiness() = Readiness(
        mockAppSelected = mockAppSelected(),
        locationPermission = hasLocationPermission(),
        canRunLocationService = MockLocationCheck.canRunLocationService(app),
        notificationPermission = hasNotificationPermission(),
        adbControlEnabled = settings.value.adbControlEnabled,
    )

    /** @throws RpcException when mocking can't start (see [MockingEnvironment.checkCanMock]). */
    fun checkCanMock() {
        if (!mockAppSelected()) throw RpcException.mockAppNotSelected()
        if (!MockLocationCheck.canRunLocationService(app)) {
            throw RpcException.unavailable(
                "Location permission not granted (Android 14+ needs it for Haunt's location service)",
                LOCATION_PERMISSION_HINT,
            )
        }
    }

    // --- services ---------------------------------------------------------------------------------

    /** Starts (or pokes) [HauntService]. Failures are reported as an `event.error`, not thrown. */
    fun startMockingService() {
        try {
            app.startForegroundService(Intent(app, HauntService::class.java))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException.
            Log.w(TAG, "Could not start HauntService", e)
            reportError(RpcException.unavailable("Could not start the location service: ${e.message}", LOCATION_PERMISSION_HINT))
        }
    }

    /** Starts [ControlService] so the ADB socket is listening (it stops itself after an idle timeout). */
    fun startControlService() {
        try {
            app.startForegroundService(Intent(app, ControlService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start ControlService", e)
        }
    }

    /** Stops mocking (test providers removed; services stop themselves). */
    fun stopMocking() = controller.stop()

    /** Emits an `event.error` (and logs it). */
    fun reportError(e: RpcException) {
        Log.w(TAG, "Error event: ${e.message}")
        eventHub.emit(HauntEvent.ErrorEvent(e.code, e.message ?: "", e.hint))
    }

    internal fun onServiceForeground(started: Boolean) = foregroundServices.update { (it + if (started) 1 else -1).coerceAtLeast(0) }

    internal fun buildNotification() = HauntNotifications.build(app, state.value, settings.value.units, controlServer.connections.value)

    private fun refreshNotification(state: HauntState, clients: Int, settings: HauntSettings) {
        // Don't re-post after the last service removed it (it would linger, detached from any service).
        if (foregroundServices.value == 0) return
        runCatching {
            app.getSystemService(NotificationManager::class.java)
                .notify(HauntNotifications.NOTIFICATION_ID, HauntNotifications.build(app, state, settings.units, clients))
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionName(): String? = app.packageManager.getPackageInfo(app.packageName, 0).versionName

    companion object {
        private const val TAG = "HauntRuntime"
        private const val PREFS_NAME = "haunt_settings"
        private const val FAVORITES_FILE = "favorites.json"
        private const val NOTIFICATION_INTERVAL_MS = 1000L

        const val LOCATION_PERMISSION_HINT =
            "Open Haunt once and grant location permission (or run: adb shell pm grant ${Protocol.APP_PACKAGE} android.permission.ACCESS_FINE_LOCATION)."

        /** The process-wide runtime. */
        fun from(context: Context): HauntRuntime = (context.applicationContext as HauntApplication).runtime
    }
}
