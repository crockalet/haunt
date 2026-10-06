package io.github.crockalet.haunt.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.crockalet.haunt.ui.theme.HauntColors
import kotlin.math.roundToInt

/**
 * Haunt's own quiet MapLibre style ("Simple" in the mockups), generated from the theme's map palette
 * ([HauntColors.map], [HauntColors.park], [HauntColors.water], roads, [HauntColors.mapLabel]) so the
 * real map matches the drawn one and follows light / dark.
 *
 * Draws vector tiles in the OpenMapTiles schema (OpenFreeMap by default; any OpenMapTiles TileJSON
 * works). No sprite: no icons or shields, only land, water, roads, buildings and a few labels.
 */
object HauntMapStyle {
    /** OpenFreeMap's TileJSON (OpenMapTiles schema, free, no key). */
    const val OPENFREEMAP_TILES = "https://tiles.openfreemap.org/planet"
    const val OPENFREEMAP_GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    private const val SOURCE = "openmaptiles"
    private val Regular = listOf("Noto Sans Regular")
    private val Bold = listOf("Noto Sans Bold")
    private val Italic = listOf("Noto Sans Italic")

    private val MinorRoads = listOf("minor", "service")
    private val MidRoads = listOf("tertiary", "secondary")
    private val MajorRoads = listOf("primary", "trunk", "motorway")

    /** The style JSON for [colors] over [tilesUrl] (a TileJSON URL in the OpenMapTiles schema). */
    fun json(colors: HauntColors, tilesUrl: String = OPENFREEMAP_TILES, glyphsUrl: String = OPENFREEMAP_GLYPHS): String =
        toJson(style(colors, tilesUrl, glyphsUrl))

    internal fun style(c: HauntColors, tilesUrl: String, glyphsUrl: String): Map<String, Any?> {
        val building = lerp(c.map, if (c.isDark) Color.White else c.text, if (c.isDark) 0.045f else 0.035f)
        val buildingEdge = lerp(c.map, if (c.isDark) Color.White else c.text, if (c.isDark) 0.08f else 0.07f)
        val path = lerp(c.map, c.mapLabel, if (c.isDark) 0.35f else 0.4f)
        val rail = lerp(c.map, c.mapLabel, 0.3f)
        val waterLabel = lerp(c.water, c.mapLabel, 0.75f)
        val majorCasing = if (c.isDark) lerp(c.majorRoad, c.map, 0.4f) else lerp(c.casing, c.mapLabel, 0.12f)
        val minorCasing = if (c.isDark) c.map else c.casing

        val surfaceOnly = listOf("!=", listOf("get", "brunnel"), "tunnel")
        val layers = buildList {
            add(layer("background", "background", paint = mapOf("background-color" to css(c.map))))

            // Land.
            add(
                fill(
                    "landcover", "landcover",
                    filter = inClass("wood", "grass", "farmland"),
                    color = c.park, opacity = zoomed(8 to 0.5, 13 to 0.8),
                ),
            )
            add(fill("park", "park", color = c.park, opacity = zoomed(8 to 0.6, 13 to 1.0)))
            add(
                fill(
                    "landuse-green", "landuse",
                    filter = inClass("cemetery", "pitch", "playground", "garden", "stadium"),
                    color = c.park, opacity = 0.8,
                ),
            )

            // Water.
            add(fill("water", "water", color = c.water, filter = listOf("!=", listOf("get", "brunnel"), "tunnel")))
            add(
                layer(
                    "waterway", "line", "waterway", minZoom = 8,
                    filter = listOf("!=", listOf("get", "brunnel"), "tunnel"),
                    layout = mapOf("line-cap" to "round"),
                    paint = mapOf("line-color" to css(c.water), "line-width" to zoomed(8 to 0.5, 14 to 2.0, 18 to 6.0)),
                ),
            )

            add(
                layer(
                    "building", "fill", "building", minZoom = 13,
                    paint = mapOf(
                        "fill-color" to css(building),
                        "fill-outline-color" to css(buildingEdge),
                        "fill-opacity" to zoomed(13 to 0.0, 15 to 1.0),
                    ),
                ),
            )

            // Paths and rail under the roads.
            add(
                layer(
                    "path", "line", "transportation", minZoom = 15,
                    filter = listOf("all", inClass("path", "track"), surfaceOnly),
                    layout = mapOf("line-cap" to "round", "line-join" to "round"),
                    paint = mapOf(
                        "line-color" to css(path),
                        "line-opacity" to zoomed(15 to 0.0, 16 to 0.6),
                        "line-width" to zoomed(15 to 0.5, 18 to 1.5),
                    ),
                ),
            )
            add(
                layer(
                    "rail", "line", "transportation", minZoom = 11,
                    filter = listOf("all", inClass("rail", "transit"), surfaceOnly, listOf("!", listOf("has", "service"))),
                    paint = mapOf("line-color" to css(rail), "line-opacity" to 0.7, "line-width" to zoomed(11 to 0.5, 18 to 1.5)),
                ),
            )

            // Roads: tunnels faded, then casings, then fills (minor → major so major roads sit on top).
            add(
                layer(
                    "road-tunnel", "line", "transportation", minZoom = 12,
                    filter = listOf("all", inClass(*(MinorRoads + MidRoads + MajorRoads).toTypedArray()), listOf("==", listOf("get", "brunnel"), "tunnel")),
                    layout = mapOf("line-cap" to "butt", "line-join" to "round"),
                    paint = mapOf("line-color" to css(c.majorRoad), "line-opacity" to 0.5, "line-width" to roadWidth(major = false)),
                ),
            )
            add(roadLine("road-minor-casing", MinorRoads + MidRoads, surfaceOnly, minorCasing, roadWidth(major = false, casing = true), minZoom = 12))
            add(roadLine("road-major-casing", MajorRoads, surfaceOnly, majorCasing, roadWidth(major = true, casing = true), minZoom = 6))
            add(roadLine("road-minor", MinorRoads, surfaceOnly, c.minorRoad, roadWidth(major = false), minZoom = 12))
            add(roadLine("road-mid", MidRoads, surfaceOnly, c.minorRoad, roadWidth(major = false), minZoom = 9))
            add(roadLine("road-major", MajorRoads, surfaceOnly, c.majorRoad, roadWidth(major = true), minZoom = 6))

            add(
                layer(
                    "boundary", "line", "boundary", maxZoom = 12,
                    filter = listOf("all", listOf("<=", listOf("get", "admin_level"), 4), listOf("!=", listOf("get", "maritime"), 1)),
                    paint = mapOf(
                        "line-color" to css(c.mapLabel),
                        "line-opacity" to 0.35,
                        "line-width" to zoomed(3 to 0.5, 10 to 1.25),
                        "line-dasharray" to listOf(3, 2),
                    ),
                ),
            )

            // Labels.
            add(
                label(
                    "water-name", "water_name", Italic, waterLabel, c.map,
                    size = zoomed(10 to 11.0, 16 to 13.0), minZoom = 10,
                    filter = listOf("==", listOf("geometry-type"), "Point"),
                ),
            )
            add(
                label(
                    "road-name", "transportation_name", Regular, c.mapLabel, if (c.isDark) c.map else c.minorRoad,
                    size = zoomed(14 to 10.0, 18 to 12.5), minZoom = 14,
                    filter = listOf("all", inClass(*(MinorRoads + MidRoads + MajorRoads).toTypedArray()), surfaceOnly),
                    extraLayout = mapOf("symbol-placement" to "line", "text-rotation-alignment" to "map"),
                ),
            )
            add(
                label(
                    "station", "poi", Regular, c.mapLabel, c.map,
                    size = 11.0, minZoom = 14,
                    filter = listOf("all", listOf("==", listOf("get", "class"), "railway"), listOf("==", listOf("get", "subclass"), "station")),
                ),
            )
            add(
                label(
                    "place-local", "place", Regular, c.mapLabel, c.map,
                    size = zoomed(12 to 10.5, 16 to 12.5), minZoom = 12,
                    filter = inClass("suburb", "quarter", "neighbourhood"),
                    extraLayout = mapOf("text-transform" to "uppercase", "text-letter-spacing" to 0.08, "text-max-width" to 8),
                ),
            )
            add(
                label(
                    "place-town", "place", Regular, c.mapLabel, c.map,
                    size = zoomed(8 to 11.0, 14 to 14.0), minZoom = 8, maxZoom = 15,
                    filter = inClass("town", "village"),
                ),
            )
            add(
                label(
                    "place-city", "place", Bold, lerp(c.mapLabel, c.text, 0.35f), c.map,
                    size = zoomed(4 to 11.0, 12 to 16.0), minZoom = 4, maxZoom = 14,
                    filter = listOf("==", listOf("get", "class"), "city"),
                ),
            )
            add(
                label(
                    "place-region", "place", Bold, c.mapLabel, c.map,
                    size = zoomed(2 to 10.0, 6 to 13.0), maxZoom = 7,
                    filter = inClass("country", "state"),
                    extraLayout = mapOf("text-transform" to "uppercase", "text-letter-spacing" to 0.1, "text-max-width" to 8),
                ),
            )
        }

        return mapOf(
            "version" to 8,
            "name" to if (c.isDark) "Haunt dark" else "Haunt light",
            "sources" to mapOf(
                SOURCE to mapOf(
                    "type" to "vector",
                    "url" to tilesUrl,
                    "attribution" to "<a href=\"https://openfreemap.org\">OpenFreeMap</a> " +
                        "<a href=\"https://www.openmaptiles.org/\">© OpenMapTiles</a> " +
                        "Data from <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>",
                ),
            ),
            "glyphs" to glyphsUrl,
            "layers" to layers,
        )
    }

    // --- layer helpers -------------------------------------------------------------------------

    private fun layer(
        id: String,
        type: String,
        sourceLayer: String? = null,
        minZoom: Int? = null,
        maxZoom: Int? = null,
        filter: Any? = null,
        layout: Map<String, Any?>? = null,
        paint: Map<String, Any?>? = null,
    ): Map<String, Any?> = buildMap {
        put("id", id)
        put("type", type)
        if (sourceLayer != null) {
            put("source", SOURCE)
            put("source-layer", sourceLayer)
        }
        minZoom?.let { put("minzoom", it) }
        maxZoom?.let { put("maxzoom", it) }
        filter?.let { put("filter", it) }
        layout?.let { put("layout", it) }
        paint?.let { put("paint", it) }
    }

    private fun fill(id: String, sourceLayer: String, color: Color, opacity: Any = 1.0, filter: Any? = null) =
        layer(id, "fill", sourceLayer, filter = filter, paint = mapOf("fill-color" to css(color), "fill-opacity" to opacity))

    private fun roadLine(id: String, classes: List<String>, extra: Any, color: Color, width: Any, minZoom: Int) =
        layer(
            id, "line", "transportation", minZoom = minZoom,
            filter = listOf("all", inClass(*classes.toTypedArray()), extra),
            layout = mapOf("line-cap" to "round", "line-join" to "round"),
            paint = mapOf("line-color" to css(color), "line-width" to width),
        )

    private fun label(
        id: String,
        sourceLayer: String,
        font: List<String>,
        color: Color,
        halo: Color,
        size: Any,
        minZoom: Int? = null,
        maxZoom: Int? = null,
        filter: Any? = null,
        extraLayout: Map<String, Any?> = emptyMap(),
    ) = layer(
        id, "symbol", sourceLayer, minZoom = minZoom, maxZoom = maxZoom, filter = filter,
        layout = mapOf(
            "text-field" to listOf("coalesce", listOf("get", "name:en"), listOf("get", "name:latin"), listOf("get", "name")),
            "text-font" to font,
            "text-size" to size,
        ) + extraLayout,
        paint = mapOf("text-color" to css(color), "text-halo-color" to css(halo), "text-halo-width" to 1.5),
    )

    /** Road widths in px by zoom; major roads are wider, casings add a hairline on each side. */
    private fun roadWidth(major: Boolean, casing: Boolean = false): Any {
        val add = if (casing) 1.5 else 0.0
        return if (major) {
            listOf("interpolate", listOf("exponential", 1.4), listOf("zoom"), 6, 0.5 + add / 3, 12, 2.0 + add, 16, 9.0 + add, 20, 28.0 + add)
        } else {
            listOf("interpolate", listOf("exponential", 1.4), listOf("zoom"), 12, 0.5 + add / 3, 14, 2.5 + add, 16, 5.5 + add, 20, 20.0 + add)
        }
    }

    private fun zoomed(vararg stops: Pair<Int, Double>): Any =
        listOf("interpolate", listOf("linear"), listOf("zoom")) + stops.flatMap { listOf(it.first, it.second) }

    private fun inClass(vararg classes: String): Any =
        listOf("match", listOf("get", "class"), classes.toList(), true, false)

    /** `rgba(r, g, b, a)` for a Compose colour (sRGB). */
    internal fun css(color: Color): String {
        fun channel(v: Float) = (v * 255).roundToInt().coerceIn(0, 255)
        val a = (color.alpha * 1000).roundToInt() / 1000.0
        return "rgba(${channel(color.red)},${channel(color.green)},${channel(color.blue)},${if (a == 1.0) "1" else a.toString()})"
    }

    // --- tiny JSON writer (maps, lists, strings, numbers, booleans, null) ----------------------

    internal fun toJson(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Double -> sb.append(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
            is Float -> write(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    write(sb, v)
                }
                sb.append(']')
            }
            else -> error("Unsupported JSON value: $value")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}
