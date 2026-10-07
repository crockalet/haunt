package io.github.crockalet.haunt.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.RouteProgress
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.ui.screens.LibraryTab
import io.github.crockalet.haunt.ui.screens.OnboardingStep
import io.github.crockalet.haunt.ui.screens.OnboardingUiState
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.Geo
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.LicenceDoc
import io.github.crockalet.haunt.ui.state.LocalUiState
import io.github.crockalet.haunt.ui.state.MapMode
import io.github.crockalet.haunt.ui.state.Notice
import io.github.crockalet.haunt.ui.state.SampleData
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.state.SpeedPreset
import io.github.crockalet.haunt.ui.theme.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders key screens at 390×844 dp (2× density) in light and dark into
 * `shared/ui/build/screenshots/` via Compose Desktop's [ImageComposeScene].
 */
class Screenshots {
    private val outDir = File(System.getProperty("haunt.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private val t0 = 1_760_000_000_000L

    private fun shot(name: String, height: Int = 844, content: @Composable (ThemeMode) -> Unit) {
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            val scale = 2f
            ImageComposeScene(
                width = (390 * scale).toInt(),
                height = (height * scale).toInt(),
                density = Density(scale),
            ) { content(mode) }.use { scene ->
                // A few frames so resources (fonts), layout and animations settle.
                var time = 0L
                repeat(6) {
                    scene.render(time)
                    time += 500_000_000L
                }
                val image = scene.render(time)
                val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                val file = File(outDir, "$name-${mode.name.lowercase()}.png")
                file.writeBytes(png)
                assertTrue(file.length() > 0)
            }
        }
    }

    private fun fix(p: LatLng, speed: Float? = null) = Fix(p, altitude = 38.0, accuracy = 5f, speed = speed, timeMillis = t0)

    @Composable
    private fun App(
        mode: ThemeMode,
        engine: HauntState,
        local: LocalUiState = LocalUiState(),
        screen: Screen = Screen.Map,
        onboarding: OnboardingUiState? = null,
        liveBlur: Boolean = true,
        configure: (HauntAppState) -> Unit = {},
    ) {
        val controller = FakeHauntController(engine, clock = { t0 })
        val state = HauntAppState(controller, screen, mode, local, onboarding = onboarding).also(configure)
        HauntApp(controller = controller, data = HauntAppData.Sample, state = state, liveBlur = liveBlur)
    }

    private val holding = HauntState.Holding(fix(SampleData.ShibuyaCrossing), "Shibuya Crossing")
    private val pinLocal = LocalUiState(activeSinceMillis = t0 - 12 * 60_000L)

    private val route = SampleData.routeShibuyaToYoyogi
    private val moving: HauntState.Moving
        get() {
            val total = Geo.length(route)
            val traveled = total * 0.35
            return HauntState.Moving(
                fix = fix(Geo.along(route, traveled), Speed.Walk.metersPerSecond.toFloat()),
                routeName = "Shibuya Stn → Yoyogi Park",
                progress = RouteProgress(traveled, total, 26 * 60L),
                speed = Speed.Walk,
                loop = LoopMode.Once,
                paused = false,
            )
        }
    private val routeLocal = LocalUiState(mode = MapMode.Route, draftRoute = route, draftName = "Shibuya Stn → Yoyogi Park")

    private val joyPos = SampleData.ShibuyaCrossing
    private val joystick = HauntState.Joystick(fix(joyPos, (12 / 3.6).toFloat()), Speed.kmh(12.0), 42.0, 640.0)
    private val joyLocal = LocalUiState(
        mode = MapMode.Joystick,
        joystickMaxKmh = 12f,
        joystickBearing = 45.0,
        joystickMagnitude = 0.74,
        trail = listOf(1000.0, 750.0, 500.0, 250.0, 0.0).map { back ->
            Geo.destination(joyPos, 222.0, back)
        },
    )

    @Test
    fun mapPin() {
        shot("01-map-pin") { App(it, holding, pinLocal) }
        shot("02-map-pin-expanded") { App(it, holding, pinLocal.copy(expanded = true)) }
    }

    @Test
    fun mapRoute() {
        shot("03-map-route") { App(it, moving, routeLocal) }
        shot("04-map-route-expanded") { App(it, moving, routeLocal.copy(expanded = true)) }
    }

    @Test
    fun mapRouteCustomSpeedAndNotice() {
        shot("11-map-route-custom-notice") {
            App(it, moving, routeLocal.copy(expanded = true, speedPreset = SpeedPreset.Custom)) { s ->
                s.map.showNotice(
                    Notice(
                        "Haunt isn't the selected mock location app",
                        "Developer options → Select mock location app → Haunt.",
                    ),
                )
            }
        }
    }

    @Test
    fun mapJoystick() {
        shot("05-map-joystick") { App(it, joystick, joyLocal) }
        shot("06-map-joystick-expanded") { App(it, joystick, joyLocal.copy(expanded = true)) }
    }

    /** Joystick mode while a pin still runs: the pad waits (dimmed) for Start; floating is on but not allowed yet. */
    @Test
    fun mapJoystickReady() {
        shot("17-map-joystick-ready") { mode ->
            val controller = FakeHauntController(holding, clock = { t0 })
            val state = HauntAppState(
                controller, Screen.Map, mode, pinLocal.copy(mode = MapMode.Joystick, expanded = true),
                defaults = HauntDefaults(floatingJoystick = true),
            )
            HauntApp(controller = controller, data = HauntAppData.Sample.copy(canDrawOverlays = false), state = state)
        }
    }

    @Test
    fun search() {
        shot("07-search") {
            App(it, holding, pinLocal, Screen.Search) { s -> s.searchQuery = "35°39'34\"N 139°42'02\"E" }
        }
    }

    @Test
    fun library() {
        shot("08-library") { App(it, holding, pinLocal, Screen.Library) { s -> s.libraryTab = LibraryTab.Favourites } }
    }

    @Test
    fun settings() {
        shot("09-settings", height = 1400) { App(it, holding, pinLocal, Screen.Settings) }
    }

    @Test
    fun dataLicences() {
        shot("15-data-licences", height = 1790) { App(it, holding, pinLocal, Screen.DataLicences) }
        shot("16-licence-text") {
            App(it, holding, pinLocal, Screen.DataLicences) { s ->
                val apache = SampleData.openSource.groups.first()
                s.openLicenceDoc(
                    LicenceDoc(
                        apache.licence.name,
                        "Used by ${apache.libraries.size} libraries",
                        apache.libraries.map { l -> "${l.name} ${l.version}" },
                        apache.licence.url,
                    ) { apache.licence.text.orEmpty() },
                )
            }
        }
    }

    @Test
    fun settingsDetails() {
        shot("12-activity-log") { App(it, holding, pinLocal, Screen.ActivityLog) }
        shot("13-service-routing") {
            App(it, holding, pinLocal, Screen.Settings) { s ->
                s.editService(HauntAppData.DefaultServices.single { e -> e.kind == ServiceKind.Routing })
                s.serviceProfile = "foot"
            }
        }
    }

    /** Android's glass: no live blur, denser tints over the sharp map. Not part of docs/screenshots. */
    @Test
    fun noBlur() {
        shot("noblur-01-map-pin") { App(it, holding, pinLocal, liveBlur = false) }
        shot("noblur-04-map-route-expanded") { App(it, moving, routeLocal.copy(expanded = true), liveBlur = false) }
        shot("noblur-05-map-joystick") { App(it, joystick, joyLocal, liveBlur = false) }
        shot("noblur-07-search") {
            App(it, holding, pinLocal, Screen.Search, liveBlur = false) { s -> s.searchQuery = "35°39'34\"N 139°42'02\"E" }
        }
        shot("noblur-08-library") {
            App(it, holding, pinLocal, Screen.Library, liveBlur = false) { s -> s.libraryTab = LibraryTab.Favourites }
        }
    }

    @Test
    fun onboarding() {
        shot("10-onboarding") { App(it, HauntState.Idle, onboarding = OnboardingUiState.forStep(OnboardingStep.SelectMockApp)) }
    }
}
