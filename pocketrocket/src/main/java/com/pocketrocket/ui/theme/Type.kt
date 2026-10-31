package com.pocketrocket.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pocketrocket.R

/** The mockup's UI face (Inter, OFL, bundled in res/font). Weights 400, 500, 600, 700. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** The mockup's code face (JetBrains Mono, OFL, bundled in res/font): paths, commands, the status line. 400 and 500. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private val Default = Typography()

private fun TextStyle.inter(weight: FontWeight? = fontWeight) = copy(fontFamily = Inter, fontWeight = weight)

/** Material 3 type scale in Inter, with firmer headings. */
val AppTypography = Typography(
    displayLarge = Default.displayLarge.inter(),
    displayMedium = Default.displayMedium.inter(),
    displaySmall = Default.displaySmall.inter(FontWeight.SemiBold),
    headlineLarge = Default.headlineLarge.inter(FontWeight.SemiBold),
    headlineMedium = Default.headlineMedium.inter(FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.inter(FontWeight.SemiBold),
    titleLarge = Default.titleLarge.inter(FontWeight.SemiBold),
    titleMedium = Default.titleMedium.inter(FontWeight.SemiBold),
    titleSmall = Default.titleSmall.inter(),
    bodyLarge = Default.bodyLarge.inter(),
    bodyMedium = Default.bodyMedium.inter(),
    bodySmall = Default.bodySmall.inter(),
    labelLarge = Default.labelLarge.inter(),
    labelMedium = Default.labelMedium.inter(),
    labelSmall = Default.labelSmall.inter(),
)

/**
 * The mockup's text sizes as px → sp styles, for screens copied from docs/ux-mockup. Line heights follow the
 * mockup's `line-height` where it sets one.
 */
object PrType {
    /** Screen titles in top bars: 16px / 600. */
    val title = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** Body copy, chat text, buttons: 14px. */
    val body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp)
    /** Smaller row text, card headers, sheet buttons: 13px. */
    val small = TextStyle(fontFamily = Inter, fontSize = 13.sp)
    /** Subtitles and descriptions: 12px. */
    val caption = TextStyle(fontFamily = Inter, fontSize = 12.sp)
    /** Hints under bubbles, tags: 11px. */
    val micro = TextStyle(fontFamily = Inter, fontSize = 11.sp)
    /** Section headings like MODE: 12px / 600, 0.4px tracking. */
    val section = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
    /** Code in cards and commands: 13px JetBrains Mono. */
    val code = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp)
    /** Smaller code (steps, pills): 12px JetBrains Mono. */
    val codeSmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp)
}

/** For script output in the setup log. */
val LogTextStyle = Default.bodySmall.copy(fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 15.sp)
