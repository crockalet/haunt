package io.github.crockalet.haunt.android

import io.github.crockalet.haunt.android.ui.OpenSourceLicences
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenSourceLicencesTest {
    private val json = """
        {
          "libraries": [
            {"uniqueId": "org.bytedeco:javacpp", "artifactVersion": "1.5.14", "name": "JavaCPP",
             "licenses": ["db199403a2b13a9b762a9bf2bca5b8c6", "Apache-2.0"]},
            {"uniqueId": "org.maplibre.nativeffi:maplibre-native-ffi", "artifactVersion": "0.202609.5",
             "name": "MapLibre Native FFI Kotlin binding", "website": "https://github.com/maplibre/maplibre-native-ffi",
             "licenses": ["BSD-2-Clause"]},
            {"uniqueId": "x:nameless", "licenses": []}
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0", "content": "Apache text"},
            "BSD-2-Clause": {"name": "BSD 2-Clause License", "content": "Copyright (c) 2026, MapLibre contributors"},
            "db199403a2b13a9b762a9bf2bca5b8c6": {"name": "GNU General Public License (GPL) version 2, or any later version"}
          }
        }
    """.trimIndent()

    @Test
    fun parsesAboutLibrariesExport() {
        val info = OpenSourceLicences.parse(json, listOf("maplibre-native-c/rust.md", "", "android-ndk/NOTICE"))
        assertEquals(3, info.libraries.size)
        val ffi = info.libraries[1]
        assertEquals("0.202609.5", ffi.version)
        assertEquals(listOf("BSD-2-Clause"), ffi.licenceIds)
        assertEquals("x:nameless", info.libraries[2].name)
        assertNull(info.licences.getValue("db199403a2b13a9b762a9bf2bca5b8c6").text)
        // JavaCPP is shipped under Apache-2.0, the alternative whose text is bundled.
        val javacpp = info.groups.single { g -> g.libraries.any { it.name == "JavaCPP" } }
        assertEquals("Apache-2.0", javacpp.licence.id)
        assertEquals(listOf("Rust crates", "Android NDK C++ runtime"), info.notices.map { it.title })
    }

    @Test
    fun noticeTitles() {
        val generic = OpenSourceLicences.notice("rustls-platform-verifier/LICENSE-MIT")
        assertEquals("rustls-platform-verifier", generic.title)
        assertEquals("LICENSE-MIT", generic.subtitle)
        assertEquals("pmtiles", OpenSourceLicences.notice("other/pmtiles.txt").title)
    }

    @Test
    fun bundledLicenceTextsCoverEveryDeclaredLicence() {
        // The build's strict mode allows these licences; each needs its text in config/licenses.
        val dir = File("config/licenses")
        val ids = dir.listFiles()!!.map { Regex("\"hash\"\\s*:\\s*\"([^\"]+)\"").find(it.readText())!!.groupValues[1] }
        assertEquals(setOf("Apache-2.0", "ASDKL", "BSD-2-Clause", "BSD-3-Clause", "MIT"), ids.toSet())
        dir.listFiles()!!.forEach { assertTrue("\"content\"" in it.readText(), it.name) }
    }
}
