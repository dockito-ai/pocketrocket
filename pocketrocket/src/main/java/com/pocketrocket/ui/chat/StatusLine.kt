package com.pocketrocket.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.TextStyle
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.sessions.GitHead
import com.pocketrocket.sessions.PermissionMode
import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The terminal-style lines under the chat box: `Opus · medium │ branch │ 160k/1M` (decision 85: the model and effort
 * lead the first line, the folder is in the top bar), then the permission mode with `│ 0 agents │ 0 shells` beside it.
 * Tapping the model opens the controls at Model and Effort; tapping the branch opens the repo's branches; tapping a
 * count opens the Background sheet; tapping the mode switches it (auto, manual, plan); tapping anywhere else, or
 * long-pressing, opens the controls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusLine(state: SessionState, model: String?, onOpenModel: () -> Unit, onOpenControls: () -> Unit, onShowBackground: () -> Unit, onOpenBranches: () -> Unit, onSwitchMode: () -> Unit, modifier: Modifier = Modifier) {
    var branch by remember(state.folder) { mutableStateOf<String?>(null) }
    // Re-read after each turn: Claude may have switched branches.
    LaunchedEffect(state.folder, state.status == SessionStatus.Done) {
        branch = withContext(Dispatchers.IO) { GitHead.branch(state.folder) }
    }
    val c = PrTheme.colors
    val dim = c.textMuted
    val bar = c.textFaint
    val green = c.green
    val line = buildAnnotatedString {
        var first = true
        fun gap() { if (!first) withStyle(SpanStyle(color = bar)) { append(" │ ") }; first = false }
        // The model and the branch take their own taps (R17); the rest of the line opens the controls.
        model?.let<String, Unit> { text ->
            gap()
            withLink(LinkAnnotation.Clickable("model") { onOpenModel() }) {
                withStyle(SpanStyle(color = c.textSoft)) { append(text.substringBefore(" · ")) }
                text.substringAfter(" · ", "").takeIf { it.isNotEmpty() }?.let<String, Unit> {
                    withStyle(SpanStyle(color = dim)) { append(" · $it") }
                }
            }
        }
        branch?.let { text ->
            gap()
            withLink(LinkAnnotation.Clickable("branch") { onOpenBranches() }) {
                withStyle(SpanStyle(color = green)) { append(text) }
            }
        }
        state.context?.let {
            gap()
            withStyle(SpanStyle(color = contextColor(it.used, it.window, dim))) { append(contextText(it.used, it.window)) }
        }
    }
    // Session.dc.html: two lines of 11.5px JetBrains Mono, 3px apart, 4px in from the chat row.
    val style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.5.sp, lineHeight = 15.sp)
    val counts = backgroundCounts(state.runningAgents, state.runningShells)
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // As before: the branch link takes its own taps, the row around it opens the controls.
        if (line.isNotEmpty()) Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpenControls, onLongClick = onOpenControls)) {
            Text(line, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The mode and the counts take their own taps; the rest of the row opens the controls.
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpenControls, onLongClick = onOpenControls)) {
            Text(
                modeLine(state.mode), style = style, color = modeColor(state.mode), maxLines = 1, softWrap = false,
                modifier = Modifier.combinedClickable(onClick = onSwitchMode, onLongClick = onOpenControls),
            )
            if (counts.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        counts.forEach { (text, shell) ->
                            withStyle(SpanStyle(color = bar)) { append(" │ ") }
                            withStyle(SpanStyle(color = if (text.startsWith("0 ")) dim else if (shell) c.teal else c.lilac)) { append(text) }
                        }
                    },
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(onClick = onShowBackground, onLongClick = onOpenControls),
                )
            }
        }
    }
}

/** "0 agents", "2 shells": what runs beside the conversation, always shown, each paired with whether it is a shell. */
internal fun backgroundCounts(agents: Int, shells: Int): List<Pair<String, Boolean>> = listOf(
    (if (agents == 1) "1 agent" else "$agents agents") to false,
    (if (shells == 1) "1 shell" else "$shells shells") to true,
)

/** How Claude Code shows each mode under its prompt, with ‖ and ▸▸ for its ⏸ and ⏵⏵. */
fun modeLine(mode: PermissionMode): String = when (mode) {
    PermissionMode.Manual -> "‖ manual mode on"
    PermissionMode.AcceptEdits -> "▸▸ accept edits on"
    // Not ⏸: Android draws it as an emoji.
    PermissionMode.Plan -> "‖ plan mode on"
    PermissionMode.Auto -> "▸▸ auto mode on"
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
