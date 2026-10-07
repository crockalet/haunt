package io.github.crockalet.haunt.android.ui

import android.content.Context
import io.github.crockalet.haunt.android.R
import io.github.crockalet.haunt.ui.state.OpenSourceInfo
import io.github.crockalet.haunt.ui.state.OssLibrary
import io.github.crockalet.haunt.ui.state.OssLicence
import io.github.crockalet.haunt.ui.state.OssNotice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Reads what the build generated for Settings → Data & licences: the AboutLibraries library list
 * (`res/raw/aboutlibraries.json`) and the notices copied into `assets/notices/` (see androidApp/build.gradle.kts).
 */
object OpenSourceLicences {
    const val NOTICES_DIR = "notices"

    fun load(context: Context): OpenSourceInfo {
        val json = context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
        val index = runCatching {
            context.assets.open("$NOTICES_DIR/index.txt").use { it.readBytes().decodeToString() }
        }.getOrDefault("")
        return parse(json, index.lines())
    }

    fun readNotice(context: Context, path: String): String {
        require(".." !in path) { "Bad notice path" }
        return context.assets.open("$NOTICES_DIR/$path").use { it.readBytes().decodeToString() }
    }

    /** Parses AboutLibraries' export plus the notice index (one path per line). */
    fun parse(json: String, noticePaths: List<String> = emptyList()): OpenSourceInfo {
        val root = Json.parseToJsonElement(json).jsonObject
        val licences = (root["licenses"] as? JsonObject).orEmpty().map { (id, value) ->
            val o = value.jsonObject
            OssLicence(
                id = id,
                name = o.string("name") ?: id,
                url = o.string("url"),
                text = o.string("content")?.takeIf { it.isNotBlank() },
            )
        }.associateBy { it.id }
        val libraries = (root["libraries"] as? JsonArray).orEmpty().map { value ->
            val o = value.jsonObject
            val id = o.string("uniqueId").orEmpty()
            OssLibrary(
                id = id,
                name = o.string("name")?.takeIf { it.isNotBlank() } ?: id,
                version = o.string("artifactVersion"),
                website = o.string("website"),
                licenceIds = o["licenses"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            )
        }
        val notices = noticePaths.map { it.trim() }.filter { it.isNotEmpty() }.map(::notice)
        return OpenSourceInfo(libraries, licences, notices)
    }

    /** A readable title for a bundled notice file. */
    fun notice(path: String): OssNotice {
        KnownNotices[path]?.let { (title, subtitle) -> return OssNotice(title, path, subtitle) }
        val dir = path.substringBeforeLast('/', "")
        val file = path.substringAfterLast('/')
        val generic = Generic.any { file.uppercase().startsWith(it) }
        return if (generic && dir.isNotEmpty()) {
            OssNotice(dir, path, file)
        } else {
            OssNotice(file.substringBeforeLast('.'), path, dir.ifEmpty { null })
        }
    }

    private val Generic = listOf("LICENSE", "LICENCE", "NOTICE", "COPYING")

    private val KnownNotices = mapOf(
        "maplibre-native-c/maplibre-native.md" to ("MapLibre Native" to "The map renderer and the components it bundles"),
        "maplibre-native-c/rust.md" to ("Rust crates" to "Used by MapLibre Native FFI"),
        "maplibre-native-c/icu.txt" to ("ICU" to "Unicode support in MapLibre Native"),
        "maplibre-native-c/fastpfor.txt" to ("FastPFor" to "Used by MapLibre Native"),
        "maplibre-native-c/nunicode.txt" to ("nunicode" to "Used by MapLibre Native"),
        "maplibre-native-c/pmtiles.txt" to ("PMTiles" to "Used by MapLibre Native"),
        "google-play-services/third_party_licenses.txt" to ("Google Play services" to "Third-party notices"),
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
