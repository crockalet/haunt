package io.github.crockalet.haunt.ui

import io.github.crockalet.haunt.ui.map.AttributionLink
import io.github.crockalet.haunt.ui.map.AttributionState
import io.github.crockalet.haunt.ui.map.MapAttributions
import io.github.crockalet.haunt.ui.state.MapStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionTest {
    @Test
    fun startsExpandedAndFoldsOnFirstGesture() {
        val s = AttributionState()
        assertTrue(s.expanded, "credits must be visible at startup without interaction")
        s.onMapGesture()
        assertFalse(s.expanded)
        s.onMapGesture()
        assertFalse(s.expanded)
    }

    @Test
    fun infoButtonBringsThemBack() {
        val s = AttributionState()
        s.onMapGesture()
        s.toggle()
        assertTrue(s.expanded)
        s.toggle()
        assertFalse(s.expanded)
        s.toggle()
        s.onMapGesture()
        assertFalse(s.expanded, "a later gesture folds them again")
    }

    @Test
    fun hauntStyleCreditsOpenFreeMapOpenMapTilesAndOsm() {
        val links = MapAttributions.forStyle(MapStyle.Default, emptyList())
        assertEquals(
            listOf(
                "OpenFreeMap" to "https://openfreemap.org",
                "© OpenMapTiles" to "https://www.openmaptiles.org/",
                "© OpenStreetMap" to "https://www.openstreetmap.org/copyright",
            ),
            links.map { it.text to it.url },
        )
    }

    @Test
    fun customStyleUsesItsSourceAttributions() {
        val html = """<a href="https://example.org/tiles" target="_blank">Example&nbsp;Tiles</a> &copy; <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a> contributors"""
        val links = MapAttributions.forStyle(MapStyle.Custom("https://example.org/style.json"), listOf(html, html))
        assertEquals(
            listOf(
                AttributionLink("Example Tiles", "https://example.org/tiles"),
                AttributionLink("©"),
                AttributionLink("OpenStreetMap", "https://www.openstreetmap.org/copyright"),
                AttributionLink("contributors"),
            ),
            links,
        )
    }

    @Test
    fun customStyleWithoutAttributionsStillCreditsOsm() {
        val links = MapAttributions.forStyle(MapStyle.Custom("https://example.org/style.json"), listOf(""))
        assertEquals(listOf(AttributionLink("© OpenStreetMap", "https://www.openstreetmap.org/copyright")), links)
    }

    @Test
    fun parsesOpenFreeMapTileJson() {
        // Verbatim from https://tiles.openfreemap.org/planet (2026-10-07).
        val html = """<a href="https://openfreemap.org" target="_blank">OpenFreeMap</a> <a href="https://www.openmaptiles.org/" target="_blank">&copy; OpenMapTiles</a> Data from <a href="https://www.openstreetmap.org/copyright" target="_blank">OpenStreetMap</a>"""
        assertEquals(
            listOf(
                AttributionLink("OpenFreeMap", "https://openfreemap.org"),
                AttributionLink("© OpenMapTiles", "https://www.openmaptiles.org/"),
                AttributionLink("Data from"),
                AttributionLink("OpenStreetMap", "https://www.openstreetmap.org/copyright"),
            ),
            MapAttributions.parseHtml(html),
        )
    }

    @Test
    fun dropsNonHttpLinksAndStrayTags() {
        assertEquals(
            listOf(AttributionLink("Tiles"), AttributionLink("by me")),
            MapAttributions.parseHtml("""<a href="javascript:alert(1)">Tiles</a> <b>by</b> <span>me</span>"""),
        )
    }
}
