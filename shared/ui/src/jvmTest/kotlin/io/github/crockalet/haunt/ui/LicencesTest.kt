package io.github.crockalet.haunt.ui

import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.ui.screens.librarySummary
import io.github.crockalet.haunt.ui.screens.paragraphs
import io.github.crockalet.haunt.ui.state.DataCredits
import io.github.crockalet.haunt.ui.state.FakeHauntController
import io.github.crockalet.haunt.ui.state.LicenceDoc
import io.github.crockalet.haunt.ui.state.OpenSourceInfo
import io.github.crockalet.haunt.ui.state.OssLibrary
import io.github.crockalet.haunt.ui.state.OssLicence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataCreditsTest {
    private val urls = DataCredits.all.flatMap { it.credits }.map { it.url }

    @Test
    fun requiredCreditsAreLinked() {
        // ODbL attribution, FOSSGIS's "report a map error" requirement, tile schema and tile host, routing, search.
        listOf(
            "https://www.openstreetmap.org/copyright",
            "https://www.openstreetmap.org/fixthemap",
            "https://openmaptiles.org",
            "https://openfreemap.org",
            "https://project-osrm.org",
            "https://routing.openstreetmap.de",
            "https://www.fossgis.de",
            "https://photon.komoot.io",
        ).forEach { assertTrue(it in urls, "missing $it") }
    }

    @Test
    fun linksAreHttps() {
        urls.forEach { assertTrue(it.startsWith("https://"), it) }
        assertTrue(DataCredits.SOURCE_URL.startsWith("https://github.com/crockalet/haunt"))
    }

    @Test
    fun mapCreditsNameTheLicences() {
        val subtitles = DataCredits.Map.credits.joinToString { it.subtitle }
        assertTrue("ODbL" in subtitles || "Open Database" in subtitles)
        assertTrue("CC-BY 4.0" in subtitles)
    }
}

class LicenceGroupsTest {
    private val apache = OssLicence("Apache-2.0", "Apache License 2.0", text = "Apache text")
    private val gpl = OssLicence("gpl2", "GPL v2 or later")
    private val mit = OssLicence("MIT", "MIT License", text = "MIT text")

    private fun lib(name: String, vararg licences: String) = OssLibrary("x:$name", name, "1.0", licenceIds = licences.toList())

    @Test
    fun groupsByLicenceLargestFirst() {
        val info = OpenSourceInfo(
            libraries = listOf(lib("b", "Apache-2.0"), lib("geo", "MIT"), lib("A", "Apache-2.0")),
            licences = listOf(apache, mit).associateBy { it.id },
        )
        assertEquals(listOf("Apache License 2.0", "MIT License"), info.groups.map { it.licence.name })
        assertEquals(listOf("A", "b"), info.groups.first().libraries.map { it.name })
    }

    @Test
    fun multiLicensedLibraryCountsUnderTheLicenceWithText() {
        // JavaCPP lists GPL-2.0+, GPL-2.0 with Classpath exception and Apache-2.0; only Apache's text is bundled.
        val info = OpenSourceInfo(
            libraries = listOf(lib("JavaCPP", "gpl2", "Apache-2.0")),
            licences = listOf(apache, gpl).associateBy { it.id },
        )
        assertEquals("Apache-2.0", info.groups.single().licence.id)
    }

    @Test
    fun unknownLicenceIdsStillShow() {
        val info = OpenSourceInfo(libraries = listOf(lib("x", "Weird-1.0"), lib("y")))
        assertEquals(setOf("Weird-1.0", "Licence not declared"), info.groups.map { it.licence.name }.toSet())
    }

    @Test
    fun summary() {
        val info = OpenSourceInfo(
            libraries = listOf("Activity", "Collections", "Annotation", "Haze").map { lib(it, "Apache-2.0") },
            licences = mapOf(apache.id to apache),
        )
        assertEquals("4 libraries · Activity, Annotation, Collections…", librarySummary(info.groups.single()))
    }

    @Test
    fun paragraphsDropCodeFencesAndBlankRuns() {
        val text = "### MapLibre Native\r\n\r\n```\r\nBSD 2-Clause License\r\n\r\nCopyright (c) 2021\r\n```\n\n\n  \nEnd"
        assertEquals(listOf("### MapLibre Native", "BSD 2-Clause License", "Copyright (c) 2021", "End"), text.paragraphs())
    }
}

class DataLicencesNavigationTest {
    @Test
    fun settingsToLicencesToTextAndBack() {
        val s = HauntAppState(FakeHauntController(HauntState.Idle), initialScreen = Screen.Settings)
        s.navigate(Screen.DataLicences)
        val doc = LicenceDoc("Apache License 2.0") { "text" }
        s.openLicenceDoc(doc)
        assertEquals(Screen.LicenceText, s.screen)
        assertEquals(doc, s.licenceDoc)
        assertTrue(s.back())
        assertEquals(Screen.DataLicences, s.screen)
        assertTrue(s.back())
        assertEquals(Screen.Settings, s.screen)
        assertTrue(s.back())
        assertEquals(Screen.Map, s.screen)
    }
}
