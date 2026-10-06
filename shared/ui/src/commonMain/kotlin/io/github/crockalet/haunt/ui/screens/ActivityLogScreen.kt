package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.GlassButton
import io.github.crockalet.haunt.ui.components.ListGroup
import io.github.crockalet.haunt.ui.components.RowDivider
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.state.LogEntry
import io.github.crockalet.haunt.ui.theme.HauntTheme

/** Settings → Activity log → See all: every logged ADB call, newest first, with error details. */
@Composable
fun ActivityLogScreen(
    log: List<LogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Activity log", onBack, backDescription = "Back to settings") {
            if (log.isNotEmpty()) GlassButton("Clear", onClear)
        }
        if (log.isEmpty()) {
            Empty("No agent calls yet. Calls made over ADB show up here.")
        } else {
            ListGroup {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(log) { i, entry ->
                        if (i > 0) RowDivider()
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val c = HauntTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(entry.time, style = HauntTheme.type.monoSmall, color = c.muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.text, style = HauntTheme.type.monoSmall, maxLines = 3)
            entry.detail?.let { Text(it, style = HauntTheme.type.smallRegular, color = c.muted, maxLines = 3) }
        }
        Text(if (entry.ok) "ok" else "err", style = HauntTheme.type.monoSmall, color = if (entry.ok) c.selectedContent else c.danger)
    }
}
