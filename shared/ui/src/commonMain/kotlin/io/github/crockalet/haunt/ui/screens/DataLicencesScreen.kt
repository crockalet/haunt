package io.github.crockalet.haunt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.Icon
import io.github.crockalet.haunt.ui.components.ListGroup
import io.github.crockalet.haunt.ui.components.ListRow
import io.github.crockalet.haunt.ui.components.RowDivider
import io.github.crockalet.haunt.ui.components.SectionHeader
import io.github.crockalet.haunt.ui.components.Text
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.Credit
import io.github.crockalet.haunt.ui.state.CreditSection
import io.github.crockalet.haunt.ui.state.DataCredits
import io.github.crockalet.haunt.ui.state.LicenceDoc
import io.github.crockalet.haunt.ui.state.LicenceGroup
import io.github.crockalet.haunt.ui.state.OpenSourceInfo
import io.github.crockalet.haunt.ui.state.OssNotice
import io.github.crockalet.haunt.ui.theme.HauntTheme

@Immutable
data class DataLicencesUiState(
    val openSource: OpenSourceInfo = OpenSourceInfo(),
    val appVersion: String? = null,
    /** Operator contact (FOSSGIS's routing terms ask apps to publish one); no Contact row when null. */
    val contactEmail: String? = null,
    /** Reads a bundled [OssNotice] by its path. */
    val loadNotice: suspend (path: String) -> String = { "" },
)

@Immutable
data class DataLicencesActions(
    val onBack: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenDoc: (LicenceDoc) -> Unit = {},
)

/** Settings → About → Data & licences: map / routing / search credits, Haunt's licence, third-party licences. */
@Composable
fun DataLicencesScreen(state: DataLicencesUiState, actions: DataLicencesActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Data & licences", actions.onBack, backDescription = "Back to settings")

        DataCredits.all.forEach { CreditGroup(it, actions.onOpenUrl) }

        CreditGroup(
            CreditSection(
                "Haunt",
                listOfNotNull(
                    Credit(
                        state.appVersion?.let { "Haunt $it" } ?: "Haunt",
                        "Free software · GNU GPL v3 or later",
                        DataCredits.GPL_URL,
                    ),
                    Credit("Source code", DataCredits.SOURCE_URL.removePrefix("https://"), DataCredits.SOURCE_URL),
                    state.contactEmail?.let { Credit("Contact", it, "mailto:$it") },
                ),
            ),
            actions.onOpenUrl,
        )

        val groups = state.openSource.groups
        if (groups.isNotEmpty()) {
            Column {
                SectionHeader("Open-source libraries")
                ListGroup {
                    groups.forEachIndexed { i, g ->
                        if (i > 0) RowDivider()
                        DocRow(g.licence.name, librarySummary(g)) { actions.onOpenDoc(g.toDoc()) }
                    }
                }
            }
        }

        val notices = state.openSource.notices
        if (notices.isNotEmpty()) {
            Column {
                SectionHeader("Bundled native code")
                ListGroup {
                    notices.forEachIndexed { i, n ->
                        if (i > 0) RowDivider()
                        DocRow(n.title, n.subtitle ?: n.path) {
                            actions.onOpenDoc(LicenceDoc(n.title, n.subtitle) { state.loadNotice(n.path) })
                        }
                    }
                }
            }
        }
    }
}

/** "88 libraries · Activity, Annotation, Collections…" */
internal fun librarySummary(group: LicenceGroup): String {
    val n = group.libraries.size
    val names = group.libraries.map { it.name }.distinct()
    val shown = names.take(3).joinToString(", ") + if (names.size > 3) "…" else ""
    return "${if (n == 1) "1 library" else "$n libraries"} · $shown"
}

private fun LicenceGroup.toDoc(): LicenceDoc {
    val text = licence.text
    return LicenceDoc(
        title = licence.name,
        subtitle = if (libraries.size == 1) "Used by 1 library" else "Used by ${libraries.size} libraries",
        libraries = libraries.map { lib -> listOfNotNull(lib.name, lib.version).joinToString(" ") },
        url = licence.url,
    ) { text ?: licence.url?.let { "The full text of this licence is at $it" }.orEmpty() }
}

@Composable
private fun CreditGroup(section: CreditSection, onOpenUrl: (String) -> Unit) {
    Column {
        SectionHeader(section.title)
        ListGroup {
            section.credits.forEachIndexed { i, credit ->
                if (i > 0) RowDivider()
                ListRow(
                    title = credit.title,
                    subtitle = credit.subtitle,
                    minHeight = 58.dp,
                    padding = RowPadding,
                    onClick = { onOpenUrl(credit.url) },
                    trailing = { Icon(HauntIcons.Open, "Opens in browser", size = 16.dp, tint = HauntTheme.colors.muted) },
                )
            }
        }
        section.note?.let {
            Text(it, Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp), style = HauntTheme.type.caption, color = HauntTheme.colors.muted)
        }
    }
}

@Composable
private fun DocRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        minHeight = 58.dp,
        padding = RowPadding,
        onClick = onClick,
        trailing = { Icon(HauntIcons.ChevronRight, null, size = 18.dp, tint = HauntTheme.colors.muted) },
    )
}

private val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/** A licence or notice in full: who uses it, then its text (loaded off the main path, split into paragraphs). */
@Composable
fun LicenceTextScreen(doc: LicenceDoc, onBack: () -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = HauntTheme.colors
    val paragraphs by produceState<List<String>?>(null, doc) {
        value = runCatching { doc.text() }.getOrElse { "Couldn't read this text: ${it.message}" }.paragraphs()
    }
    LazyColumn(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScreenHeader(doc.title, onBack, backDescription = "Back to data and licences")
                doc.subtitle?.let { Text(it, Modifier.padding(start = 6.dp), style = HauntTheme.type.caption, color = c.muted) }
            }
        }
        if (doc.libraries.isNotEmpty() || doc.url != null) {
            item(key = "libraries") {
                ListGroup {
                    doc.url?.let { url ->
                        ListRow(
                            title = "Licence page",
                            subtitle = url.removePrefix("https://"),
                            minHeight = 52.dp,
                            padding = RowPadding,
                            onClick = { onOpenUrl(url) },
                            trailing = { Icon(HauntIcons.Open, "Opens in browser", size = 16.dp, tint = c.muted) },
                        )
                        if (doc.libraries.isNotEmpty()) RowDivider()
                    }
                    if (doc.libraries.isNotEmpty()) {
                        Column(Modifier.padding(RowPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            doc.libraries.forEach { lib ->
                                Text(lib, Modifier.fillMaxWidth(), style = HauntTheme.type.caption, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
        val ps = paragraphs
        if (ps == null) {
            item(key = "loading") { Text("Loading…", Modifier.padding(start = 6.dp), style = HauntTheme.type.caption, color = c.muted) }
        } else {
            items(ps) { p -> Text(p, Modifier.padding(horizontal = 6.dp), style = HauntTheme.type.monoSmall, color = c.text) }
        }
    }
}

/** Splits a licence text into paragraphs for a lazy list (some notices run to half a megabyte). */
internal fun String.paragraphs(): List<String> =
    replace("\r\n", "\n")
        .lineSequence()
        .filterNot { it.trimStart().startsWith("```") }
        .joinToString("\n")
        .split(Regex("\n[ \t]*\n"))
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
