package com.pocketrocket.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Brand colours from the launcher icon: deep navy and rocket-flame orange. */
object Brand {
    val Navy = Color(0xFF0B1E3A)
    val Flame = Color(0xFFFF7A1A)
}

/**
 * The mockup's palette (docs/ux-mockup, decision 23), as named tokens. Read them in a composable with
 * `PrTheme.colors.<name>`; [PrDark] holds the mockup's exact hex values, [PrLight] the same roles for light mode.
 *
 * Surfaces, darkest to lightest: [scrim] behind sheets, [background] screens, [bar] the composer strip,
 * [surface] list rows and setting cards, [card] chat cards and sheets, [raised] selected chips, command pills.
 * Lines: [divider] between rows, [border] around cards, chips and fields, [borderStrong] dashed "add" chips and grips.
 * Text: [text] main, [textSoft] secondary labels and icons, [textMuted] captions and hints, [textFaint] separators,
 * [textPreview] the last line on a Home card, [onTextCount] the count on a selected (text-filled) tab pill.
 * [codeBackground] the read-only file view in Browse.
 * Accent: [accent] orange for primary actions and the selected choice, [onAccent] text on it.
 * Status: [amber] needs you, [blue] working, [green] done / added lines, [red] error / removed lines,
 * [lilac] subagents and Auto mode, [teal] Plan mode. Each tint has a matching surface where the mockup uses one;
 * [amberEdge] is the border of a Home card that needs you.
 * Code (Diff, Browse): [diffAddedText] / [diffRemovedText] on added and removed lines, [hunkBackground] behind @@ lines,
 * [codeString] and [codeComment] for syntax; keywords are [lilac], numbers [accent], types [blue]. Status → colour lives in StatusColors.kt.
 * Branches and PRs (BranchesPRs.html): [purple] PR numbers, with [purpleSurface] behind a PR pill; [greenSurface],
 * [blueSurface] and [accentPill] behind local, origin/ and default-branch labels in the graph; [accentSurface] and
 * [accentBorder] a selected filter chip or the recommended choice in a dialog; [sheet] bottom sheets, [dialog] dialogs.
 */
@Immutable
data class PrColors(
    val scrim: Color,
    val background: Color,
    val bar: Color,
    val surface: Color,
    val card: Color,
    val raised: Color,
    val iconTile: Color,
    val userBubble: Color,
    val divider: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textReply: Color,
    val textSoft: Color,
    val textMuted: Color,
    val textFaint: Color,
    val textPreview: Color,
    val onTextCount: Color,
    val codeBackground: Color,
    val accent: Color,
    val onAccent: Color,
    val accentTint: Color,
    val amber: Color,
    val onAmber: Color,
    val amberSurface: Color,
    val amberBorder: Color,
    val amberText: Color,
    val amberEdge: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val redSoft: Color,
    val redBorder: Color,
    val lilac: Color,
    val teal: Color,
    val diffAddedText: Color,
    val diffRemovedText: Color,
    val hunkBackground: Color,
    val codeString: Color,
    val codeComment: Color,
    val purple: Color,
    val purpleSurface: Color,
    val greenSurface: Color,
    val blueSurface: Color,
    val accentSurface: Color,
    val accentBorder: Color,
    val accentPill: Color,
    val sheet: Color,
    val dialog: Color,
    val isDark: Boolean,
)

/** Exactly the mockup's colours. */
val PrDark = PrColors(
    scrim = Color(0xFF07090D),
    background = Color(0xFF0F1218),
    bar = Color(0xFF141821),
    surface = Color(0xFF161A22),
    card = Color(0xFF181C24),
    raised = Color(0xFF20252F),
    iconTile = Color(0xFF232833),
    userBubble = Color(0xFF263048),
    divider = Color(0xFF1E232D),
    border = Color(0xFF2A303C),
    borderStrong = Color(0xFF3A4150),
    text = Color(0xFFE8EAF0),
    textReply = Color(0xFFDCE0E8),
    textSoft = Color(0xFFC9CFDA),
    textMuted = Color(0xFF9AA3B2),
    textFaint = Color(0xFF4A5262),
    textPreview = Color(0xFFB4BBC8),
    onTextCount = Color(0xFF4A5160),
    codeBackground = Color(0xFF0B0E13),
    accent = Color(0xFFFF8A3D),
    onAccent = Color(0xFF1A1206),
    accentTint = Color(0x1FFF8A3D),
    amber = Color(0xFFF5B83D),
    onAmber = Color(0xFF1A1206),
    amberSurface = Color(0xFF2A2416),
    amberBorder = Color(0xFF5A5030),
    amberText = Color(0xFFF5D48A),
    amberEdge = Color(0xFF5A4520),
    blue = Color(0xFF7AB4FF),
    green = Color(0xFF5BD69A),
    red = Color(0xFFFF6B6B),
    redSoft = Color(0xFFFF8F8F),
    redBorder = Color(0xFF5A2A30),
    lilac = Color(0xFFC4A8FF),
    teal = Color(0xFF5BC8D6),
    diffAddedText = Color(0xFFB8F0D2),
    diffRemovedText = Color(0xFFFFC2C2),
    hunkBackground = Color(0xFF141B28),
    codeString = Color(0xFF9FD9A8),
    codeComment = Color(0xFF6B7385),
    purple = Color(0xFFB79BFF),
    purpleSurface = Color(0xFF2A2340),
    greenSurface = Color(0xFF173326),
    blueSurface = Color(0xFF16243A),
    accentSurface = Color(0xFF2A2016),
    accentBorder = Color(0xFF5A3A1E),
    accentPill = Color(0xFF3A2614),
    sheet = Color(0xFF1A1F29),
    dialog = Color(0xFF1E2430),
    isDark = true,
)

/** The same roles on a light page: cool greys, the same orange, status hues darkened for contrast on white. */
val PrLight = PrColors(
    scrim = Color(0xFF0F1218),
    background = Color(0xFFF7F8FA),
    bar = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    raised = Color(0xFFEEF0F4),
    iconTile = Color(0xFFEEF0F4),
    userBubble = Color(0xFFDDE6F7),
    divider = Color(0xFFE8EBF0),
    border = Color(0xFFDCE0E7),
    borderStrong = Color(0xFFB9C0CC),
    text = Color(0xFF151821),
    textReply = Color(0xFF232733),
    textSoft = Color(0xFF3C4350),
    textMuted = Color(0xFF636B7A),
    textFaint = Color(0xFFB0B6C2),
    textPreview = Color(0xFF4A5262),
    onTextCount = Color(0xFFB0B6C2),
    codeBackground = Color(0xFFF1F3F6),
    accent = Color(0xFFE8711F),
    onAccent = Color(0xFFFFFFFF),
    accentTint = Color(0x1FE8711F),
    amber = Color(0xFFB87A00),
    onAmber = Color(0xFFFFFFFF),
    amberSurface = Color(0xFFFFF6E0),
    amberBorder = Color(0xFFE8CF8F),
    amberText = Color(0xFF8A5B00),
    amberEdge = Color(0xFFE8C98A),
    blue = Color(0xFF2F6FD0),
    green = Color(0xFF1E8A55),
    red = Color(0xFFD03A3A),
    redSoft = Color(0xFFC62828),
    redBorder = Color(0xFFF0C4C4),
    lilac = Color(0xFF7A55C8),
    teal = Color(0xFF16808E),
    diffAddedText = Color(0xFF14532D),
    diffRemovedText = Color(0xFF7F1D1D),
    hunkBackground = Color(0xFFEEF2FB),
    codeString = Color(0xFF15803D),
    codeComment = Color(0xFF8A8F98),
    purple = Color(0xFF6E4FC2),
    purpleSurface = Color(0xFFEFEAFB),
    greenSurface = Color(0xFFE3F4EA),
    blueSurface = Color(0xFFE5EEFB),
    accentSurface = Color(0xFFFFF1E6),
    accentBorder = Color(0xFFF2C29E),
    accentPill = Color(0xFFFCE6D4),
    sheet = Color(0xFFFFFFFF),
    dialog = Color(0xFFFFFFFF),
    isDark = false,
)

val LocalPrColors = staticCompositionLocalOf { PrDark }

/** Material colours derived from the tokens, so stock components (menus, dialogs, sheets) match the mockup too. */
fun PrColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentTint,
        onPrimaryContainer = text,
        secondary = blue,
        onSecondary = onAccent,
        secondaryContainer = userBubble,
        onSecondaryContainer = text,
        tertiary = lilac,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = textMuted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = card,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = iconTile,
        surfaceBright = raised,
        surfaceDim = background,
        outline = textMuted,
        outlineVariant = border,
        error = red,
        scrim = scrim,
    )
}
