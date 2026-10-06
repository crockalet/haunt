package io.github.crockalet.haunt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.ui.HauntApp
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.HauntAppData
import io.github.crockalet.haunt.ui.state.SampleData

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // TODO: swap in the real controller from the location service.
            val scope = rememberCoroutineScope()
            val controller = remember {
                FakeHauntController(
                    initial = HauntState.Holding(
                        Fix(SampleData.ShibuyaCrossing, altitude = 38.0, timeMillis = System.currentTimeMillis()),
                        label = "Shibuya Crossing",
                    ),
                    scope = scope,
                )
            }
            HauntApp(controller = controller, data = HauntAppData.Sample)
        }
    }
}
