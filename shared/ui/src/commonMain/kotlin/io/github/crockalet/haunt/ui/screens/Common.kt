package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.GlassIconButton
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.theme.HauntTheme

/** Back button + large title (Library, Settings). */
@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(HauntIcons.Back, "Back to map", onBack)
        Text(title, style = HauntTheme.type.display)
    }
}
