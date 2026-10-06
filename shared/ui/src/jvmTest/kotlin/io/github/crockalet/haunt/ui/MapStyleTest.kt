package io.github.crockalet.haunt.ui

import androidx.compose.ui.graphics.Color
import io.github.crockalet.haunt.ui.map.HauntMapStyle
import io.github.crockalet.haunt.ui.theme.DarkHauntColors
import io.github.crockalet.haunt.ui.theme.LightHauntColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapStyleTest {
    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun generatesValidStyleForBothThemes() {
        val outDir = File("build/map-style").apply { mkdirs() }
        for ((name, colors) in listOf("light" to LightHauntColors, "dark" to DarkHauntColors)) {
            val json = HauntMapStyle.json(colors)
            File(outDir, "haunt-$name.json").writeText(json)
            val style = parse(json)
            assertEquals("8", style["version"]!!.jsonPrimitive.content)
            assertEquals(
                HauntMapStyle.OPENFREEMAP_TILES,
                style["sources"]!!.jsonObject["openmaptiles"]!!.jsonObject["url"]!!.jsonPrimitive.content,
            )
            val layers = style["layers"]!!.jsonArray.map { it.jsonObject }
            val ids = layers.map { it["id"]!!.jsonPrimitive.content }
            assertEquals(ids.toSet().size, ids.size, "layer ids must be unique")
            assertEquals("background", ids.first())
            val bg = layers.first()["paint"]!!.jsonObject["background-color"]!!.jsonPrimitive.content
            assertEquals(HauntMapStyle.css(colors.map), bg)
            // Labels come last so roads never draw over them.
            val firstSymbol = layers.indexOfFirst { it["type"]!!.jsonPrimitive.content == "symbol" }
            assertTrue(layers.drop(firstSymbol).all { it["type"]!!.jsonPrimitive.content == "symbol" })
        }
    }

    @Test
    fun customTilesUrlIsUsed() {
        val style = parse(HauntMapStyle.json(LightHauntColors, tilesUrl = "https://tiles.example.org/planet"))
        assertEquals(
            "https://tiles.example.org/planet",
            style["sources"]!!.jsonObject["openmaptiles"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun cssColoursAndEscaping() {
        assertEquals("rgba(47,107,255,1)", HauntMapStyle.css(Color(0xFF2F6BFF)))
        assertEquals("rgba(0,0,0,0.502)", HauntMapStyle.css(Color(0x80000000)))
        assertEquals("""{"a":"q\"\\\n","b":[1,2.5,true,null]}""", HauntMapStyle.toJson(mapOf("a" to "q\"\\\n", "b" to listOf(1, 2.5, true, null))))
    }
}
