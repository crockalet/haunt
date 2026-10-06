package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.ChipGroup
import io.github.crockalet.haunt.ui.components.GlassSurface
import io.github.crockalet.haunt.ui.components.PrimaryButton
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.components.TonalButton
import io.github.crockalet.haunt.ui.state.ServiceEndpoint
import io.github.crockalet.haunt.ui.state.ServiceKind
import io.github.crockalet.haunt.ui.state.ServiceValidation
import io.github.crockalet.haunt.ui.theme.HauntShapes
import io.github.crockalet.haunt.ui.theme.HauntTheme

@Immutable
data class ServiceUiState(
    val endpoint: ServiceEndpoint,
    val url: String,
    val profile: String,
    val urlError: String? = null,
    val profileError: String? = null,
)

@Immutable
data class ServiceActions(
    val onBack: () -> Unit = {},
    val onUrlChange: (String) -> Unit = {},
    val onProfileChange: (String) -> Unit = {},
    val onReset: () -> Unit = {},
    val onSave: () -> Unit = {},
)

/** Settings → Map & services → one service: edit its URL (and routing profile). */
@Composable
fun ServiceScreen(
    state: ServiceUiState,
    actions: ServiceActions,
    modifier: Modifier = Modifier,
) {
    val c = HauntTheme.colors
    val hasProfile = state.endpoint.profile != null
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(state.endpoint.title, actions.onBack, backDescription = "Back to settings")

        GlassSurface(Modifier.fillMaxWidth(), shape = HauntShapes.list) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(hint(state.endpoint.kind), style = HauntTheme.type.paragraphSmall, color = c.muted)
                Field(
                    label = "URL",
                    value = state.url,
                    onValueChange = actions.onUrlChange,
                    error = state.urlError,
                    keyboardType = KeyboardType.Uri,
                    imeAction = if (hasProfile) ImeAction.Next else ImeAction.Done,
                    onDone = actions.onSave,
                )
                if (hasProfile) {
                    Field(
                        label = "Profile",
                        value = state.profile,
                        onValueChange = actions.onProfileChange,
                        error = state.profileError,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                        onDone = actions.onSave,
                    )
                    ChipGroup(
                        options = ServiceValidation.Profiles,
                        selected = state.profile.trim().takeIf { it in ServiceValidation.Profiles },
                        onSelect = actions.onProfileChange,
                        label = { it },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalButton("Reset to default", actions.onReset, Modifier.weight(1f))
            PrimaryButton("Save", actions.onSave, Modifier.weight(1f))
        }
    }
}

private fun hint(kind: ServiceKind?): String = when (kind) {
    ServiceKind.MapStyle -> "By default Haunt draws its own light / dark style over OpenFreeMap's free tiles. Paste a MapLibre style JSON URL to use a different map (for both themes)."
    ServiceKind.Search -> "A Photon geocoder for place search and nearby places — the public photon.komoot.io or your own."
    ServiceKind.Routing -> "An OSRM server for road-following routes. The public demo only serves driving; for walking or cycling routes, point this at a server with the foot or bike profile."
    null -> ""
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onDone: () -> Unit,
) {
    val c = HauntTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = HauntTheme.type.label, color = c.muted)
        Box(Modifier.fillMaxWidth().clip(HauntShapes.inset).background(c.tile).padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = HauntTheme.type.monoCode.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, style = HauntTheme.type.smallRegular, color = c.danger) }
    }
}
