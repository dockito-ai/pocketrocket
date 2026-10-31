package com.pocketrocket.ui.git

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.JetBrainsMono
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the git screens for diff lines, status letters and syntax, all from the shared tokens. */
@Immutable
data class GitColors(
    val added: Color,
    val addedBackground: Color,
    val removed: Color,
    val removedBackground: Color,
    val addedText: Color,
    val removedText: Color,
    val hunk: Color,
    val hunkBackground: Color,
    val lineNumber: Color,
    val modified: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
)

/** The git colours for the current theme (mockup Git, Diff, Browse). */
val gitColors: GitColors
    @Composable @ReadOnlyComposable get() = PrTheme.colors.let { c ->
        GitColors(
            added = c.green, addedBackground = c.green.copy(alpha = 0.10f),
            removed = c.redSoft, removedBackground = c.red.copy(alpha = 0.10f),
            addedText = c.diffAddedText, removedText = c.diffRemovedText,
            hunk = c.blue, hunkBackground = c.hunkBackground,
            lineNumber = c.textFaint, modified = c.amber,
            keyword = c.lilac, string = c.codeString, comment = c.codeComment,
            number = c.accent, type = c.blue,
        )
    }

val CodeStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 20.4.sp)

/** Width of one [CodeStyle] character, for hanging indents that line wrapped code up under a gutter. */
@Composable
fun monoCharWidth(): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val px = measurer.measure("0000000000", CodeStyle).size.width / 10f
        with(density) { px.toSp() }
    }
}

/** Git's status letter in a small rounded square, coloured by kind. */
@Composable
fun KindBadge(kind: Char, modifier: Modifier = Modifier) {
    val c = gitColors
    val color = when (kind) {
        'A', '?' -> c.added
        'D' -> c.removed
        'U' -> PrTheme.colors.red
        else -> c.modified
    }
    Box(
        modifier.size(22.dp).background(PrTheme.colors.raised, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (kind == '?') "U" else kind.toString(), style = CodeStyle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** "+12 −3", either half left out when unknown or zero. */
@Composable
fun LineCounts(added: Int?, deleted: Int?) {
    val c = gitColors
    if (added != null && added > 0) Text("+$added", style = CodeStyle, color = c.added)
    if (deleted != null && deleted > 0) Text("−$deleted", style = CodeStyle, color = c.removed)
}

/** "20 m", "3 h", "2 d", "5 w", "4 mo", "1 y" from a unix time in seconds: the short ages of the graph and PR cards. */
fun shortAge(epochSeconds: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val minutes = ((now - epochSeconds) / 60).coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "$minutes m"
        minutes < 60 * 24 -> "${minutes / 60} h"
        minutes < 60 * 24 * 14 -> "${minutes / (60 * 24)} d"
        minutes < 60 * 24 * 60 -> "${minutes / (60 * 24 * 7)} w"
        minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)} mo"
        else -> "${minutes / (60 * 24 * 365)} y"
    }
}

/** "20 min ago", "yesterday"... from a unix time in seconds. */
fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(epochSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
