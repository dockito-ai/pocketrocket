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
 * The terminal-style lines under the chat box: `folder │ branch │ model │ 160k/1M`, then the permission mode with
 * `│ 2 agents │ 1 shell` beside it while subagents or background shells run. Tapping the branch opens the repo's
 * branches; tapping a count opens the Background sheet; tapping anywhere else, or long-pressing, opens the controls,
 * where the mode is changed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusLine(state: SessionState, onOpenControls: () -> Unit, onShowBackground: () -> Unit, onOpenBranches: () -> Unit, modifier: Modifier = Modifier) {
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
        val parts = listOfNotNull(
            folderName(state.folder) to dim,
            branch?.let { it to green },
            state.modelName?.let { it to dim },
            state.context?.let { contextText(it.used, it.window) to contextColor(it.used, it.window, dim) },
        )
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) withStyle(SpanStyle(color = bar)) { append(" │ ") }
            if (text == branch && i == 1) {
                // The branch takes its own taps (R17); the rest of the line opens the controls.
                withLink(LinkAnnotation.Clickable("branch") { onOpenBranches() }) {
                    withStyle(SpanStyle(color = color)) { append(text) }
                }
            } else {
                withStyle(SpanStyle(color = color)) { append(text) }
            }
        }
    }
    // Session.dc.html: two lines of 11.5px JetBrains Mono, 3px apart, 4px in from the chat row.
    val style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.5.sp, lineHeight = 15.sp)
    val counts = backgroundCounts(state.runningAgents, state.runningShells)
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // As before: the branch link takes its own taps, the row around it opens the controls.
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpenControls, onLongClick = onOpenControls)) {
            Text(line, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The counts take their own taps; the mode opens the controls.
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpenControls, onLongClick = onOpenControls)) {
            Text(modeLine(state.mode), style = style, color = modeColor(state.mode), maxLines = 1, softWrap = false)
            if (counts.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        counts.forEach { (text, shell) ->
                            withStyle(SpanStyle(color = bar)) { append(" │ ") }
                            withStyle(SpanStyle(color = if (shell) c.teal else c.lilac)) { append(text) }
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

/** "1 agent", "2 shells": what runs beside the conversation, each paired with whether it is a shell. */
internal fun backgroundCounts(agents: Int, shells: Int): List<Pair<String, Boolean>> = listOfNotNull(
    agents.takeIf { it > 0 }?.let { (if (it == 1) "1 agent" else "$it agents") to false },
    shells.takeIf { it > 0 }?.let { (if (it == 1) "1 shell" else "$it shells") to true },
)

/** How the terminal shows each mode under its prompt. */
fun modeLine(mode: PermissionMode): String = when (mode) {
    PermissionMode.Manual -> "▸ manual"
    PermissionMode.AcceptEdits -> "▸▸ accept edits"
    // Not ⏸: Android draws it as an emoji.
    PermissionMode.Plan -> "‖ plan mode"
    PermissionMode.Auto -> "▸▸ auto mode"
}

@Composable
fun modeColor(mode: PermissionMode): Color = when (mode) {
    PermissionMode.Manual -> PrTheme.colors.textMuted
    PermissionMode.AcceptEdits -> PrTheme.colors.accent
    PermissionMode.Plan -> PrTheme.colors.teal
    PermissionMode.Auto -> PrTheme.colors.lilac
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
