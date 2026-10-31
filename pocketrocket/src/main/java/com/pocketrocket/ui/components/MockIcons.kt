package com.pocketrocket.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The mockup's line icons (24-unit viewBox, 2-unit stroke, no fill), from its SVG paths; rects and
 * circles are written as paths. Tinted by Icon like any other vector.
 */
object MockIcons {
    val Back = icon("Back", "M15 18l-6-6 6-6")
    val Close = icon("Close", "M6 6l12 12M18 6L6 18")
    val ChevronDown = icon("ChevronDown", "M6 9l6 6 6-6")
    val Check = icon("Check", "M5 12.5l4.5 4.5L19 7.5")
    val ChevronRight = icon("ChevronRight", "M9 6l6 6-6 6")
    val Plus = icon("Plus", "M12 5v14M5 12h14", stroke = 2.5f)
    val Folder = icon("Folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Gear = icon("Gear", circle(12f, 12f, 3f) + circle(12f, 12f, 8f))
    val Branch = icon("Branch", circle(6f, 6f, 2.5f) + circle(6f, 18f, 2.5f) + circle(18f, 9f, 2.5f) + "M6 8.5v7M18 11.5c0 3-4 3-9.5 4.5")
    val Pulse = icon("Pulse", "M3 12h4l3-8 4 16 3-8h4")
    val Bars = icon("Bars", "M3 21h18M6 21v-7M12 21V5M18 21v-11")
    val Person = icon("Person", circle(12f, 8f, 4f) + "M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6")
    val Copy = icon("Copy", "M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")
    val Globe = icon("Globe", circle(12f, 12f, 9f) + "M3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z")
    val Wrench = icon("Wrench", "M14.5 6.5a4 4 0 0 1 5-3.9l-2.8 2.8 2.9 2.9 2.8-2.8a4 4 0 0 1-5.4 4.7L8 19.7a2 2 0 0 1-2.9-2.9l9-8.5a4 4 0 0 1 .4-1.8z")
    val Android = icon("Android", "M5 18a7 7 0 0 1 14 0zM8 11.5 6 8M16 11.5 18 8M8.2 15h2M13.8 15h2")
    val Cloud = icon("Cloud", "M7 19a4.5 4.5 0 0 1-.7-8.95A6 6 0 0 1 17.7 9.1 5 5 0 0 1 17 19z")
    val Grid = icon("Grid", rect(3f, 3f) + rect(14f, 3f) + rect(3f, 14f) + rect(14f, 14f))
    val Rocket = icon("Rocket", "M12 2c3 2.5 4.5 6 4.5 10v4h-9v-4C7.5 8 9 4.5 12 2zM7.5 13 5 16v3h2.5M16.5 13 19 16v3h-2.5M10 20h4" + circle(12f, 9f, 1.5f))
    val Doc = icon("Doc", "M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6")
    val Stop = icon("Stop", "M7 7h10v10H7z")
    val Sliders = icon("Sliders", "M4 7h9M17 7h3M4 17h3M11 17h9" + circle(15f, 7f, 2f) + circle(9f, 17f, 2f))
    val Mic = icon("Mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3", stroke = 2.2f)
    val Code = icon("Code", "M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    /** A 7×7 rect with 1.5 corners at [x],[y]. */
    private fun rect(x: Float, y: Float) =
        "M${x + 1.5f} ${y}h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z"

    private fun icon(name: String, path: String, stroke: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
            .build()
}
