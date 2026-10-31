package com.pocketrocket.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.pocketrocket.ui.theme.PrTheme

/** "End this session?", warning first about background commands and agents still running: on yes, Claude Code stops and the session is closed; its conversation stays in the history. */
@Composable
fun EndSessionDialog(session: Session, onDone: () -> Unit) {
    val state = session.state.collectAsState().value
    val running = stillRunning(state.runningShells, state.runningAgents)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("End \"${state.title}\"?") },
        text = {
            Text(
                buildAnnotatedString {
                    if (running != null) {
                        withStyle(SpanStyle(color = PrTheme.colors.amber, fontWeight = FontWeight.SemiBold)) { append("$running still running. ") }
                        append("Ending the session stops ${if (state.runningShells + state.runningAgents == 1) "it" else "them"}. ")
                    }
                    append("Claude Code stops and the session leaves the grid. The conversation stays in Claude Code's history.")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                SessionManager.close(session.id)
                onDone()
            }) { Text("End session") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** "1 background command", "2 background commands and 1 agent"; null when nothing runs. */
internal fun stillRunning(shells: Int, agents: Int): String? = listOfNotNull(
    shells.takeIf { it > 0 }?.let { if (it == 1) "1 background command" else "$it background commands" },
    agents.takeIf { it > 0 }?.let { if (it == 1) "1 agent" else "$it agents" },
).joinToString(" and ").ifEmpty { null }
