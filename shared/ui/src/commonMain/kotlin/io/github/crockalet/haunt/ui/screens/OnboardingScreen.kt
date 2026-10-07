package io.github.crockalet.haunt.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.HauntAppIcon
import io.github.crockalet.haunt.ui.components.HauntWordmark
import io.github.crockalet.haunt.ui.components.Icon
import io.github.crockalet.haunt.ui.components.ListGroup
import io.github.crockalet.haunt.ui.components.PrimaryButton
import io.github.crockalet.haunt.ui.components.RowDivider
import io.github.crockalet.haunt.ui.components.StatusChip
import io.github.crockalet.haunt.ui.components.StepProgress
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme

enum class OnboardingStep { DeveloperOptions, SelectMockApp, Permissions, Done }

enum class ItemStatus { Done, Current, Todo }

@Immutable
data class OnboardingItem(val text: String, val status: ItemStatus)

@Immutable
data class OnboardingUiState(
    val step: OnboardingStep,
    val title: String,
    val body: String,
    val items: List<OnboardingItem>,
    /** Live check status ("Waiting for Haunt to be selected…"), shown with a spinner. */
    val waiting: String?,
    val primary: String,
    val help: String?,
) {
    val stepNumber: Int get() = step.ordinal + 1
    val totalSteps: Int get() = OnboardingStep.entries.size

    companion object {
        /** Default copy for each step. [waiting] shows the live-check chip. */
        fun forStep(step: OnboardingStep, waiting: Boolean = true): OnboardingUiState = when (step) {
            OnboardingStep.DeveloperOptions -> OnboardingUiState(
                step = step,
                title = "Turn on\nDeveloper options",
                body = "Haunt fakes your location through Android's mock location setting, which lives in Developer options.",
                items = listOf(
                    OnboardingItem("Open Settings → About phone", ItemStatus.Current),
                    OnboardingItem("Tap “Build number” seven times", ItemStatus.Todo),
                    OnboardingItem("Come back to Haunt", ItemStatus.Todo),
                ),
                waiting = if (waiting) "Waiting for Developer options…" else null,
                primary = "Open About phone",
                help = "Where's Build number?",
            )
            OnboardingStep.SelectMockApp -> OnboardingUiState(
                step = step,
                title = "Make Haunt your\nmock location app",
                body = "Android lets one app fake your location. You pick it in Developer options. No root needed.",
                items = listOf(
                    OnboardingItem("Turn on Developer options", ItemStatus.Done),
                    OnboardingItem("Tap “Select mock location app”", ItemStatus.Current),
                    OnboardingItem("Choose Haunt, then come back", ItemStatus.Todo),
                ),
                waiting = if (waiting) "Waiting for Haunt to be selected…" else null,
                primary = "Open Developer options",
                help = "Can't find Developer options?",
            )
            OnboardingStep.Permissions -> OnboardingUiState(
                step = step,
                title = "Allow location\nand notifications",
                body = "Location shows where you really are on the map. The notification keeps Haunt running while it fakes your location.",
                items = listOf(
                    OnboardingItem("Location while using the app", ItemStatus.Current),
                    OnboardingItem("Notifications", ItemStatus.Todo),
                ),
                waiting = null,
                primary = "Allow",
                help = "Why does Haunt need this?",
            )
            OnboardingStep.Done -> OnboardingUiState(
                step = step,
                title = "You're ready\nto haunt",
                body = "Long-press the map to drop a pin, draw a route, or grab the joystick. Agents can drive Haunt over ADB.",
                items = listOf(
                    OnboardingItem("Developer options", ItemStatus.Done),
                    OnboardingItem("Mock location app", ItemStatus.Done),
                    OnboardingItem("Permissions", ItemStatus.Done),
                ),
                waiting = null,
                primary = "Start haunting",
                help = null,
            )
        }
    }
}

@Immutable
data class OnboardingActions(
    val onPrimary: () -> Unit = {},
    val onHelp: () -> Unit = {},
)

/** Onboarding: developer options → mock app → permissions → done. */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepProgress(state.totalSteps, state.stepNumber, Modifier.weight(1f))
            Text("Step ${state.stepNumber} of ${state.totalSteps}", style = HauntTheme.type.label, color = c.muted)
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HauntAppIcon(size = 112.dp)
            HauntWordmark()
        }

        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title, Modifier.fillMaxWidth(), style = HauntTheme.type.headline, textAlign = TextAlign.Center)
            Text(state.body, Modifier.fillMaxWidth(), style = HauntTheme.type.paragraph, color = c.muted, textAlign = TextAlign.Center)
        }

        if (state.items.isNotEmpty()) {
            ListGroup(shape = HauntShapes.listSmall) {
                state.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    StepRow(i + 1, item)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            state.waiting?.let { text ->
                StatusChip {
                    Spinner()
                    Text(text, maxLines = 1)
                }
            }
            PrimaryButton(
                state.primary, actions.onPrimary, Modifier.fillMaxWidth(),
                height = 56.dp, style = HauntTheme.type.bodyLarge, shadow = true,
            )
            state.help?.let {
                Text(
                    it,
                    Modifier.clip(HauntShapes.pill).clickable(role = Role.Button, onClick = actions.onHelp).padding(8.dp),
                    style = HauntTheme.type.tab,
                    color = c.selectedContent,
                )
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, item: OnboardingItem) {
    val c = HauntTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val (bg, fg) = when (item.status) {
            ItemStatus.Done -> c.selected to c.selectedContent
            ItemStatus.Current -> c.accent to c.onAccent
            ItemStatus.Todo -> c.tile to c.muted
        }
        Box(Modifier.size(30.dp).clip(HauntShapes.pill).background(bg), contentAlignment = Alignment.Center) {
            if (item.status == ItemStatus.Done) {
                Icon(HauntIcons.Check, "Done", size = 16.dp, tint = fg)
            } else {
                Text("$number", style = HauntTheme.type.section, color = fg)
            }
        }
        Text(
            item.text,
            style = when (item.status) {
                ItemStatus.Current -> HauntTheme.type.bodyStrong
                else -> HauntTheme.type.body
            },
            color = if (item.status == ItemStatus.Done) c.muted else c.text,
        )
    }
}

@Composable
private fun Spinner() {
    val angle by rememberInfiniteTransition().animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
    )
    Icon(HauntIcons.Spinner, null, Modifier.rotate(angle), size = 14.dp, tint = HauntTheme.colors.accent)
}
