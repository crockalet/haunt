package io.github.crockalet.haunt.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.ui.screens.MapActions
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.Place
import io.github.crockalet.haunt.ui.theme.ThemeMode
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapActionsTest {
    private val a = LatLng(35.0, 139.0)
    private val b = LatLng(35.001, 139.001)

    @Test
    fun actionsSurviveEngineTicksAndNewDataButSeeTheLatest() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { failure = runCatching { check() }.exceptionOrNull() }
        failure?.let { throw it }
    }

    private fun check() {
        val controller = FakeHauntController(HauntState.Holding(Fix(a, timeMillis = 0), "Here"), clock = { 0L })
        val state = HauntAppState(controller, Screen.Map, ThemeMode.Light)
        val saved = mutableListOf<Pair<Int, Place>>()
        var version by mutableIntStateOf(0)
        val seen = mutableListOf<MapActions>()
        ImageComposeScene(100, 100, Density(1f)) {
            val v = version
            // Reading the UI state here makes every engine tick recompose this scope.
            state.map.shown
            seen += rememberMapActions(state, HauntAppData(onSaveFavourite = { saved += v to it }))
        }.use { scene ->
            scene.render(0)
            state.map.onEngineState(HauntState.Holding(Fix(b, timeMillis = 1_000), "There"))
            scene.render(16_000_000)
            version = 1
            scene.render(32_000_000)

            assertTrue(seen.size >= 3, "recomposed ${seen.size} times")
            assertTrue(seen.all { it === seen.first() }, "MapActions was rebuilt")
            assertNull(seen.first().onLocate)

            seen.last().onSaveFavourite()
            val (v, place) = saved.single()
            assertEquals(1, v)
            assertEquals("There" to b, place.name to place.position)
        }
    }

    @Test
    fun locateAppearsWhenTheAppProvidesIt() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            failure = runCatching {
                val state = HauntAppState(FakeHauntController(HauntState.Idle, clock = { 0L }))
                var actions: MapActions? = null
                ImageComposeScene(100, 100, Density(1f)) {
                    actions = rememberMapActions(state, HauntAppData(locateMe = { a }))
                }.use { it.render(0) }
                assertNotNull(assertNotNull(actions).onLocate)
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }

    @Test
    fun mapBottomInsetClearsTheToolbar() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            failure = runCatching {
                fun inset(data: HauntAppData): Dp {
                    val state = HauntAppState(FakeHauntController(HauntState.Idle, clock = { 0L }), Screen.Map, ThemeMode.Light)
                    ImageComposeScene(400, 800, Density(1f)) {
                        HauntApp(state.map.controller, data = data, state = state)
                    }.use { scene -> repeat(3) { scene.render(it * 500_000_000L) } }
                    return state.mapBottomInset
                }
                val toolbarOnly = inset(HauntAppData())
                // Toolbar row (≥ 40 dp) plus the 18 dp bottom margin, well under the screen height.
                assertTrue(toolbarOnly in 58.dp..200.dp, "inset $toolbarOnly")
                // The credits sit bottom-left, so the bottom-right locate button doesn't push them up.
                assertEquals(toolbarOnly, inset(HauntAppData(locateMe = { a })))
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }
}
