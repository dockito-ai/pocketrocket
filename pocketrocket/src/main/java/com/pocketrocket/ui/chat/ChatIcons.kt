package com.pocketrocket.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The mockup's line icons (24-unit SVG paths, stroked), so the session screen draws exactly what Session.dc.html does. */
internal object ChatIcons {
    val Back = line("Back", 2f, "M15,18l-6,-6 6,-6")
    val Branch = line(
        "Branch", 2f,
        circle(6f, 6f, 2.5f) + circle(6f, 18f, 2.5f) + circle(18f, 9f, 2.5f) + "M6,8.5v7M18,11.5c0,3 -4,3 -9.5,4.5",
    )
    val Terminal = line("Terminal", 2f, "M4,17l6,-5 -6,-5M12,19h8")
    val Plus = line("Plus", 2.5f, "M12,5v14M5,12h14")
    val Mic = line("Mic", 2.2f, "M12,3a3,3 0 0 1 3,3v5a3,3 0 0 1 -6,0v-5a3,3 0 0 1 3,-3zM5,11a7,7 0 0 0 14,0M12,18v3")
    val Send = line("Send", 2.2f, "M12,19V5M5,12l7,-7 7,7")
    val Photo = line("Photo", 1.8f, "M6,4h12a3,3 0 0 1 3,3v10a3,3 0 0 1 -3,3h-12a3,3 0 0 1 -3,-3v-10a3,3 0 0 1 3,-3z" + circle(9f, 10f, 2f) + "M21,16l-5,-5 -9,9")
    val File = line("File", 1.8f, "M14,3h-7a2,2 0 0 0 -2,2v14a2,2 0 0 0 2,2h10a2,2 0 0 0 2,-2v-11zM14,3v5h5")
    val Camera = line("Camera", 1.8f, "M4,8h3l2,-3h6l2,3h3v11h-16z" + circle(12f, 13f, 3.5f))
    val Close = line("Close", 2.2f, "M7,7l10,10M17,7l-10,10")

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun line(name: String, width: Float, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width)
        .build()
}
