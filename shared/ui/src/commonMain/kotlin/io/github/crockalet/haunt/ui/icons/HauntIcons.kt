package io.github.crockalet.haunt.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Stroke icons from the mockups (24×24 viewBox, round caps and joins). Paths are the SVG path data
 * copied from the HTML; circles and rects are converted to arcs. Tint them with
 * [io.github.crockalet.haunt.ui.components.Icon].
 */
object HauntIcons {
    val Search = stroke("Search", 2.2f, circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val Plus = stroke("Plus", 2.2f, "M12 5v14M5 12h14")
    val Library = stroke("Library", 2f, "M6 3h12v18l-6-4-6 4Z")
    val Settings = stroke(
        "Settings", 2f,
        "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Pin = stroke("Pin", 2f, "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11Z", circle(12f, 10f, 2.5f))
    val Route = stroke(
        "Route", 2f,
        circle(6f, 18f, 2.5f), circle(18f, 6f, 2.5f), "M8.5 18H15a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h6.5",
    )
    val Joystick = stroke("Joystick", 2f, circle(12f, 12f, 9f), circle(12f, 12f, 3.5f))
    val ChevronUp = stroke("ChevronUp", 2f, "m6 15 6-6 6 6")
    val ChevronDown = stroke("ChevronDown", 2f, "m6 9 6 6 6-6")
    val ChevronRight = stroke("ChevronRight", 2f, "m9 6 6 6-6 6")
    val Back = stroke("Back", 2.2f, "M15 5l-7 7 7 7")
    val Close = stroke("Close", 2.2f, "M6 6l12 12M18 6 6 18")
    val Copy = stroke("Copy", 2f, rect(9f, 9f, 11f, 11f, 2f), "M5 15V5a1 1 0 0 1 1-1h9")
    val Star = stroke("Star", 2f, "m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9Z")
    val Pause = stroke("Pause", 2.4f, "M8 5v14M16 5v14")
    val Clock = stroke("Clock", 2f, circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Upload = stroke("Upload", 2.2f, "M12 16V4M7 9l5-5 5 5M5 20h14")
    val Terminal = stroke("Terminal", 2.2f, "m5 8 4 4-4 4M12 16h7")
    val Check = stroke("Check", 2.6f, "m5 12 5 5 9-10")
    val Spinner = stroke("Spinner", 3f, "M21 12a9 9 0 1 1-9-9")
    val Move = stroke(
        "Move", 2f,
        "M12 3v18M3 12h18", "m9 6 3-3 3 3", "m9 18 3 3 3-3", "m6 9-3 3 3 3", "m18 9 3 3-3 3",
    )
    val Open = stroke("Open", 2.2f, "M7 17 17 7M9 7h8v8")
    val Play = fill("Play", "M7 5v14l11-7Z")
    val Stop = fill("Stop", rect(6f, 6f, 12f, 12f, 3f))

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}Z"

    private fun stroke(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun fill(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
            }
        }.build()
}
