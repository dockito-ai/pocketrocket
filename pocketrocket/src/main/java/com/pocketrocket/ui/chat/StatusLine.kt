package com.pocketrocket.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.TextStyle
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.sessions.PermissionMode
import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.sessions.SessionState
import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH

/**
 * The one terminal-style row under the chat box (decision 89): `▸▸ auto mode │ Opus · medium │ 160k/1M │ 0 agents │ 0 shells`.
 * The branch is in the top bar. Tapping the mode switches it (auto, manual, plan); tapping the model opens the controls
 * at Model and Effort; tapping a count opens the Background sheet; tapping anywhere else, or long-pressing, opens the
 * controls. When the row is too wide for the screen it shortens step by step ([statusLevels]) until it fits on one line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusLine(state: SessionState, model: String?, onOpenModel: () -> Unit, onOpenControls: () -> Unit, onShowBackground: () -> Unit, onSwitchMode: () -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val dim = c.textMuted
    val bar = c.textFaint
    val modeTint = modeColor(state.mode)
    val tokens = state.context?.let { contextText(it.used, it.window) }
    val tokenTint = state.context?.let { contextColor(it.used, it.window, dim) } ?: dim
    // Session.dc.html: 11.5px JetBrains Mono, 4px in from the chat row.
    val style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.5.sp, lineHeight = 15.sp)
    val levels = statusLevels(
        state.mode, model?.substringBefore(" · "), model?.substringAfter(" · ", "")?.takeIf { it.isNotEmpty() },
        tokens, state.runningAgents, state.runningShells,
    )
    fun render(parts: List<StatusPart>): AnnotatedString = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            when (part.kind) {
                StatusPart.Kind.Effort -> withStyle(SpanStyle(color = dim)) { append(" · ") }
                else -> if (i > 0) withStyle(SpanStyle(color = bar)) { append(" │ ") }
            }
            val color = when (part.kind) {
                StatusPart.Kind.Mode -> modeTint
                StatusPart.Kind.Model -> c.textSoft
                StatusPart.Kind.Effort -> dim
                StatusPart.Kind.Tokens -> tokenTint
                StatusPart.Kind.Agents -> if (part.text.startsWith("0 ")) dim else c.lilac
                StatusPart.Kind.Shells -> if (part.text.startsWith("0 ")) dim else c.teal
            }
            val tap: (() -> Unit)? = when (part.kind) {
                StatusPart.Kind.Mode -> onSwitchMode
                StatusPart.Kind.Model, StatusPart.Kind.Effort -> onOpenModel
                StatusPart.Kind.Agents, StatusPart.Kind.Shells -> onShowBackground
                StatusPart.Kind.Tokens -> null
            }
            if (tap == null) withStyle(SpanStyle(color = color)) { append(part.text) }
            else withLink(LinkAnnotation.Clickable(part.kind.name, TextLinkStyles(SpanStyle(color = color))) { tap() }) { append(part.text) }
        }
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 4.dp).combinedClickable(onClick = onOpenControls, onLongClick = onOpenControls),
    ) {
        val width = constraints.maxWidth
        val line = fitStatus(levels) { measurer.measure(render(it), style, softWrap = false, maxLines = 1).size.width <= width }
        Text(render(line), style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** One piece of the status row; an [Kind.Effort] joins the model with " · ", the others are split by " │ ". */
internal data class StatusPart(val kind: Kind, val text: String) {
    enum class Kind { Mode, Model, Effort, Tokens, Agents, Shells }
}

/**
 * The status row from fullest to shortest. Each step keeps the previous ones: the counts become `0 A │ 0 S`, then
 * the mode drops "mode", then the effort goes, then the model name is cut to its first word.
 */
internal fun statusLevels(mode: PermissionMode, model: String?, effort: String?, tokens: String?, agents: Int, shells: Int): List<List<StatusPart>> {
    fun row(shortCounts: Boolean, shortMode: Boolean, withEffort: Boolean, shortModel: Boolean) = buildList {
        add(StatusPart(StatusPart.Kind.Mode, modeText(mode, short = shortMode)))
        model?.let { add(StatusPart(StatusPart.Kind.Model, if (shortModel) shortModelName(it) else it)) }
        if (withEffort && model != null) effort?.let { add(StatusPart(StatusPart.Kind.Effort, it)) }
        tokens?.let { add(StatusPart(StatusPart.Kind.Tokens, it)) }
        add(StatusPart(StatusPart.Kind.Agents, if (shortCounts) "$agents A" else if (agents == 1) "1 agent" else "$agents agents"))
        add(StatusPart(StatusPart.Kind.Shells, if (shortCounts) "$shells S" else if (shells == 1) "1 shell" else "$shells shells"))
    }
    return listOf(
        row(shortCounts = false, shortMode = false, withEffort = true, shortModel = false),
        row(shortCounts = true, shortMode = false, withEffort = true, shortModel = false),
        row(shortCounts = true, shortMode = true, withEffort = true, shortModel = false),
        row(shortCounts = true, shortMode = true, withEffort = false, shortModel = false),
        row(shortCounts = true, shortMode = true, withEffort = false, shortModel = true),
    ).distinct()
}

/** The first level that [fits], or the shortest one when none does. */
internal fun <T> fitStatus(levels: List<T>, fits: (T) -> Boolean): T = levels.firstOrNull(fits) ?: levels.last()

/** The row as plain text, as drawn. */
internal fun statusText(parts: List<StatusPart>): String = buildString {
    parts.forEachIndexed { i, part ->
        if (part.kind == StatusPart.Kind.Effort) append(" · ") else if (i > 0) append(" │ ")
        append(part.text)
    }
}

/** "Claude Opus 5.5 (1M context)" → "Opus": the family name alone. */
internal fun shortModelName(name: String): String =
    name.substringBefore('(').trim().removePrefix("Claude ").trim().substringBefore(' ').ifEmpty { name }

/** Claude Code's wording for each mode, with ‖ and ▸▸ for its ⏸ and ⏵⏵; [short] drops "mode". */
internal fun modeText(mode: PermissionMode, short: Boolean = false): String = when (mode) {
    PermissionMode.Manual -> if (short) "‖ manual" else "‖ manual mode"
    PermissionMode.AcceptEdits -> "▸▸ accept edits"
    // Not ⏸: Android draws it as an emoji.
    PermissionMode.Plan -> if (short) "‖ plan" else "‖ plan mode"
    PermissionMode.Auto -> if (short) "▸▸ auto" else "▸▸ auto mode"
}

/** Claude Code's own colours for each mode (its `inactive`, `autoAccept`, `planMode` and `warning`), dark and light. */
@Composable
fun modeColor(mode: PermissionMode): Color {
    val dark = PrTheme.colors.isDark
    return when (mode) {
        PermissionMode.Manual -> if (dark) Color(153, 153, 153) else Color(102, 102, 102)
        PermissionMode.AcceptEdits -> if (dark) Color(175, 135, 255) else Color(135, 0, 255)
        PermissionMode.Plan -> if (dark) Color(72, 150, 140) else Color(0, 102, 102)
        PermissionMode.Auto -> if (dark) Color(255, 193, 7) else Color(150, 108, 30)
    }
}

fun folderName(folder: String): String {
    val path = folder.trimEnd('/')
    return if (path == TERMUX_HOME_DIR_PATH) "~" else path.substringAfterLast('/')
}

fun contextText(used: Long, window: Long?): String =
    SessionReducer.tokens(used) + (window?.let { "/" + SessionReducer.tokens(it) } ?: "")

/** Amber past 70% of the window, red past 90%, like a fuel gauge. */
@Composable
fun contextColor(used: Long, window: Long?, normal: Color): Color {
    val share = if (window == null || window <= 0) 0f else used.toFloat() / window
    return when {
        share >= 0.9f -> PrTheme.colors.red
        share >= 0.7f -> PrTheme.colors.amber
        else -> normal
    }
}
