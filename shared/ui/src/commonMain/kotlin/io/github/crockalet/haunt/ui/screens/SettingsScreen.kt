package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.GlassSurface
import io.github.crockalet.haunt.ui.components.Icon
import io.github.crockalet.haunt.ui.components.IconBadge
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.ListGroup
import io.github.crockalet.haunt.ui.components.ListRow
import io.github.crockalet.haunt.ui.components.RowDivider
import io.github.crockalet.haunt.ui.components.SectionHeader
import io.github.crockalet.haunt.ui.components.Switch
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.AgentConnection
import io.github.crockalet.haunt.ui.state.HauntDefaults
import io.github.crockalet.haunt.ui.state.LogEntry
import io.github.crockalet.haunt.ui.state.ServiceEndpoint
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme
import io.github.crockalet.haunt.ui.theme.ThemeMode

/** The command shown in "Connect an AI agent". */
const val ConnectAgentCommand = "claude mcp add haunt -- haunt mcp"

@Immutable
data class SettingsUiState(
    val adbEnabled: Boolean = false,
    val connection: AgentConnection? = null,
    val log: List<LogEntry> = emptyList(),
    val services: List<ServiceEndpoint> = emptyList(),
    val theme: ThemeMode = ThemeMode.System,
    val defaults: HauntDefaults = HauntDefaults(),
)

@Immutable
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onAdbChange: (Boolean) -> Unit = {},
    val onSeeAllLog: () -> Unit = {},
    val onCopyCommand: () -> Unit = {},
    val onService: (ServiceEndpoint) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onUpdateRate: () -> Unit = {},
    val onAccuracy: () -> Unit = {},
    val onUnits: () -> Unit = {},
    val onJoystickSize: () -> Unit = {},
    val onFloatingJoystick: (Boolean) -> Unit = {},
)

/** Settings: ADB control + activity log, connect an agent, services, defaults. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Settings", actions.onBack)

        Column {
            SectionHeader("Agent control")
            ListGroup {
                ListRow(
                    title = "Allow ADB control",
                    subtitle = "Only the ADB shell can connect. Other apps can't.",
                    minHeight = 58.dp,
                    padding = ItemPadding,
                    trailing = { Switch(state.adbEnabled, actions.onAdbChange, contentDescription = "Allow ADB control") },
                )
                if (state.adbEnabled) {
                    RowDivider()
                    val conn = state.connection
                    ListRow(
                        title = conn?.let { "${it.name} · connected" } ?: "No agent connected",
                        subtitle = conn?.via ?: "Waiting for haunt on your computer",
                        minHeight = 58.dp,
                        padding = ItemPadding,
                        leading = { IconBadge(HauntIcons.Terminal, selected = conn != null) },
                    )
                    if (state.log.isNotEmpty()) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                Text("Activity log", style = HauntTheme.type.bodyStrong.copy(fontSize = HauntTheme.type.tab.fontSize))
                                Text(
                                    "See all",
                                    Modifier.clickable(role = Role.Button, onClick = actions.onSeeAllLog),
                                    style = HauntTheme.type.label,
                                    color = c.selectedContent,
                                )
                            }
                            Column(
                                Modifier.fillMaxWidth().clip(HauntShapes.inset).background(c.tile).padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.log.take(4).forEach { l ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(l.time, style = HauntTheme.type.monoSmall, color = c.muted)
                                        Text(l.text, Modifier.weight(1f), style = HauntTheme.type.monoSmall, maxLines = 1)
                                        Text(if (l.ok) "ok" else "err", style = HauntTheme.type.monoSmall, color = if (l.ok) c.selectedContent else c.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        GlassSurface(Modifier.fillMaxWidth(), shape = HauntShapes.list) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connect an AI agent", style = HauntTheme.type.titleSmall)
                BasicText(
                    buildAnnotatedString {
                        append("Install the ")
                        withStyle(SpanStyle(fontFamily = HauntTheme.type.mono, color = c.text)) { append("haunt") }
                        append(" CLI on your computer, then add it to your agent as an MCP server.")
                    },
                    style = HauntTheme.type.paragraphSmall.copy(color = c.muted),
                )
                Row(
                    Modifier.fillMaxWidth().clip(HauntShapes.inset).background(c.tile).padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(ConnectAgentCommand, Modifier.weight(1f), style = HauntTheme.type.monoCode, maxLines = 1)
                    IconButton(HauntIcons.Copy, "Copy command", actions.onCopyCommand, size = 38.dp, iconSize = 16.dp, background = c.glass)
                }
            }
        }

        if (state.services.isNotEmpty()) {
            Column {
                SectionHeader("Map & services")
                ListGroup {
                    state.services.forEachIndexed { i, s ->
                        if (i > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button) { actions.onService(s) }.padding(ItemPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.title, style = HauntTheme.type.bodyStrong)
                                BasicText(
                                    buildAnnotatedString {
                                        append("${s.provider} · ")
                                        withStyle(SpanStyle(fontFamily = HauntTheme.type.mono, fontSize = HauntTheme.type.monoSmall.fontSize)) { append(s.url) }
                                    },
                                    style = HauntTheme.type.caption.copy(color = c.muted),
                                    maxLines = 1,
                                )
                            }
                            Icon(HauntIcons.ChevronRight, null, size = 18.dp, tint = c.muted)
                        }
                    }
                }
            }
        }

        Column {
            SectionHeader("Defaults")
            ListGroup {
                val themeLabel = when (state.theme) {
                    ThemeMode.System -> "System"
                    ThemeMode.Light -> "Light"
                    ThemeMode.Dark -> "Dark"
                }
                DefaultRow("Theme", themeLabel) {
                    actions.onTheme(ThemeMode.entries[(state.theme.ordinal + 1) % ThemeMode.entries.size])
                }
                RowDivider()
                DefaultRow("Update rate", "${state.defaults.updateRateHz} Hz", actions.onUpdateRate)
                RowDivider()
                DefaultRow("Accuracy", "±${state.defaults.accuracyMeters.toInt()} m", actions.onAccuracy)
                RowDivider()
                DefaultRow("Units", if (state.defaults.metric) "Metric" else "Imperial", actions.onUnits)
            }
        }

        Column {
            SectionHeader("Joystick")
            ListGroup {
                DefaultRow("Pad size", "${state.defaults.joystickSize.label} · ${state.defaults.joystickSize.dp.toInt()} dp", actions.onJoystickSize)
                RowDivider()
                ListRow(
                    title = "Float over other apps",
                    subtitle = "Shows the pad on top of other apps while joystick mode runs",
                    minHeight = 58.dp,
                    padding = ItemPadding,
                    trailing = {
                        Switch(state.defaults.floatingJoystick, actions.onFloatingJoystick, contentDescription = "Float over other apps")
                    },
                )
            }
        }
    }
}

private val ItemPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
private fun DefaultRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(ItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = HauntTheme.type.bodyStrong)
        Text(value, style = HauntTheme.type.monoValueSmall, color = HauntTheme.colors.muted)
    }
}
