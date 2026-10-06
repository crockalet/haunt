package io.github.crockalet.haunt.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.SampleData
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.theme.ThemeMode
import kotlin.test.Test

/** Drives real screen changes frame by frame: every transition must run to the end without crashing. */
class TransitionsTest {
    @Test
    fun navigatingEveryScreenDoesNotCrash() {
        val t0 = 1_760_000_000_000L
        val controller = FakeHauntController(
            HauntState.Holding(Fix(SampleData.ShibuyaCrossing, accuracy = 5f, timeMillis = t0), "Shibuya Crossing"),
            clock = { t0 },
        )
        val state = HauntAppState(controller, Screen.Map, ThemeMode.Light)
        ImageComposeScene(390, 844, Density(1f)) {
            HauntApp(controller = controller, data = HauntAppData.Sample, state = state)
        }.use { scene ->
            var time = 0L
            fun frames(n: Int = 40) = repeat(n) {
                scene.render(time)
                time += 16_000_000L
            }
            frames()
            val steps: List<() -> Unit> = listOf(
                { state.map.toggleExpanded() }, { state.map.toggleExpanded() },
                { state.navigate(Screen.Search) }, { state.back() },
                { state.navigate(Screen.Library) }, { state.back() },
                { state.navigate(Screen.Settings) }, { state.navigate(Screen.ActivityLog) }, { state.back() },
                { state.editService(HauntAppData.Sample.services.first { it.kind == ServiceKind.Routing }) }, { state.back() },
                { state.back() },
            )
            for (step in steps) {
                step()
                frames(3)
            }
            // Same again but interrupting each transition after one frame.
            for (step in steps) {
                step()
                frames(1)
            }
            frames()
        }
    }
}
