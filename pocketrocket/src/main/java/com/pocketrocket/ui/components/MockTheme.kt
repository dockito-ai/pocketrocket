package com.pocketrocket.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.theme.Inter
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrColors
import com.pocketrocket.ui.theme.PrTheme

/**
 * Old names for the shared tokens, kept only for the Settings screen until it moves to `PrTheme.colors`.
 * Every value comes from [PrColors]; there is no second palette. New code reads `PrTheme.colors` directly.
 */
class MockColors(private val p: PrColors) {
    val bg get() = p.background
    val text get() = p.text
    val muted get() = p.textMuted
    val soft get() = p.textSoft
    val card get() = p.card
    val field get() = p.surface
    val chip get() = p.raised
    val border get() = p.border
    val rowBorder get() = p.iconTile
    val divider get() = p.divider
    val bar get() = p.bar
    val accent get() = p.accent
    val onAccent get() = p.onAccent
    val faint get() = p.textFaint
    val amber get() = p.amber
    val blue get() = p.blue
    val green get() = p.green
    val red get() = p.red
    val redText get() = p.redSoft
    val redBorder get() = p.redBorder
    val amberBorder get() = p.amberEdge
}

/** The shared tokens under their old names (see [MockColors]). */
val mock: MockColors
    @Composable @ReadOnlyComposable get() = MockColors(PrTheme.colors)

/** Mockup text at a px size: Inter, or JetBrains Mono when [mono], with the mockup's 1.35 line height. */
fun mockText(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified, mono: Boolean = false, letterSpacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontSize = size, fontWeight = weight, color = color, letterSpacing = letterSpacing,
        fontFamily = if (mono) JetBrainsMono else Inter,
        lineHeight = (size.value * 1.35f).sp,
    )
