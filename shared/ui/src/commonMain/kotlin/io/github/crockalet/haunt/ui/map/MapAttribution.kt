package io.github.crockalet.haunt.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.crockalet.haunt.ui.components.GlassMode
import io.github.crockalet.haunt.ui.components.GlassSurface
import io.github.crockalet.haunt.ui.components.IconButton
import io.github.crockalet.haunt.ui.components.LocalGlassMode
import io.github.crockalet.haunt.ui.icons.HauntIcons
import io.github.crockalet.haunt.ui.state.MapStyle
import io.github.crockalet.haunt.ui.theme.HauntMotion
import io.github.crockalet.haunt.ui.theme.HauntTheme

/** One piece of a map credit line: [text], linking to [url] when there is one. */
@Immutable
data class AttributionLink(val text: String, val url: String? = null)

/** Map credits: what the map corner shows for a style, and a parser for MapLibre's attribution HTML. */
object MapAttributions {
    const val OPENSTREETMAP_COPYRIGHT = "https://www.openstreetmap.org/copyright"

    /** Haunt's own style: OpenFreeMap tiles in the OpenMapTiles schema, built from OpenStreetMap data. */
    val OpenFreeMap: List<AttributionLink> = listOf(
        AttributionLink("OpenFreeMap", "https://openfreemap.org"),
        AttributionLink("© OpenMapTiles", "https://www.openmaptiles.org/"),
        AttributionLink("© OpenStreetMap", OPENSTREETMAP_COPYRIGHT),
    )

    /**
     * The credits to show for [style]. Haunt's style over OpenFreeMap uses [OpenFreeMap]; anything else
     * uses the attributions its sources declare ([sourceAttributions], HTML), and falls back to the
     * OpenStreetMap credit while those haven't loaded.
     */
    fun forStyle(style: MapStyle, sourceAttributions: List<String>): List<AttributionLink> {
        if (style is MapStyle.Haunt && style.tilesUrl == HauntMapStyle.OPENFREEMAP_TILES) return OpenFreeMap
        val parsed = sourceAttributions.flatMap(::parseHtml).distinct()
        return parsed.ifEmpty { listOf(AttributionLink("© OpenStreetMap", OPENSTREETMAP_COPYRIGHT)) }
    }

    private val Anchor = Regex("""<a\b[^>]*?href\s*=\s*["']([^"']*)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val Tag = Regex("<[^>]*>")
    private val Space = Regex("\\s+")

    /** Splits an attribution HTML string into plain and linked pieces; other markup is dropped. */
    fun parseHtml(html: String): List<AttributionLink> {
        val out = mutableListOf<AttributionLink>()
        fun plain(raw: String) {
            val text = clean(raw)
            if (text.isNotEmpty()) out += AttributionLink(text)
        }
        var at = 0
        for (m in Anchor.findAll(html)) {
            plain(html.substring(at, m.range.first))
            val text = clean(m.groupValues[2])
            val url = decode(m.groupValues[1]).trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            if (text.isNotEmpty()) out += AttributionLink(text, url)
            at = m.range.last + 1
        }
        plain(html.substring(at))
        return out
    }

    private fun clean(raw: String): String = decode(raw.replace(Tag, " ")).replace(Space, " ").trim()

    private fun decode(s: String): String = s
        .replace("&copy;", "©").replace("&#169;", "©").replace("&#xa9;", "©", ignoreCase = true)
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/**
 * Whether the map credits are spelled out or folded into the (i) button. They start expanded, as the
 * OSMF attribution guideline asks; the first map gesture folds them and (i) brings them back.
 */
@Stable
class AttributionState(expanded: Boolean = true) {
    var expanded by mutableStateOf(expanded)
        private set

    /** The user panned, zoomed or long-pressed the map. */
    fun onMapGesture() {
        expanded = false
    }

    fun toggle() {
        expanded = !expanded
    }
}

/**
 * The map's credit line in the bottom-left corner, [bottomInset] up from the window bottom (clear of
 * the toolbar). Drawn on solid glass: the map behind it may be a SurfaceView.
 */
@Composable
internal fun MapAttributionOverlay(links: List<AttributionLink>, state: AttributionState, bottomInset: Dp) {
    val bottom = if (bottomInset > 0.dp) {
        Modifier.padding(bottom = bottomInset + 8.dp)
    } else {
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(bottom = 8.dp)
    }
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .then(bottom)
            // Wraps short of the locate button, which sits bottom-right at the same height.
            .padding(start = 12.dp, end = 12.dp + LocateButtonColumn),
        contentAlignment = Alignment.BottomStart,
    ) {
        CompositionLocalProvider(LocalGlassMode provides GlassMode.Solid) {
            MapAttribution(links, state)
        }
    }
}

/** Locate button (52 dp) plus its 12 dp end margin and an 8 dp gap. */
private val LocateButtonColumn = 72.dp

/** Glass pill with the linked credits and an (i) button that folds / unfolds them. */
@Composable
fun MapAttribution(links: List<AttributionLink>, state: AttributionState, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    val c = HauntTheme.colors
    val linkStyles = remember(c) { TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)) }
    val text = remember(links, linkStyles) {
        buildAnnotatedString {
            links.forEachIndexed { i, link ->
                if (i > 0) append(" ")
                if (link.url != null) withLink(LinkAnnotation.Url(link.url, linkStyles)) { append(link.text) } else append(link.text)
            }
        }
    }
    // Swallows drags so they don't pan the map underneath.
    GlassSurface(modifier.pointerInput(Unit) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                HauntIcons.Info,
                if (state.expanded) "Hide map credits" else "Map credits",
                state::toggle,
                size = 36.dp,
                iconSize = 18.dp,
                tint = c.text,
            )
            AnimatedVisibility(
                state.expanded,
                enter = fadeIn(HauntMotion.snappy()) + expandHorizontally(HauntMotion.smooth(), expandFrom = Alignment.Start),
                exit = fadeOut(HauntMotion.snappy()) + shrinkHorizontally(HauntMotion.smooth(), shrinkTowards = Alignment.Start),
            ) {
                BasicText(
                    text,
                    Modifier.widthIn(max = 280.dp).padding(end = 14.dp, top = 8.dp, bottom = 8.dp),
                    style = HauntTheme.type.small.copy(color = c.text),
                )
            }
        }
    }
}
