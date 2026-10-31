package com.pocketrocket.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager

/** "End this session?": on yes, Claude Code stops and the session is closed; its conversation stays in the history. */
@Composable
fun EndSessionDialog(session: Session, onDone: () -> Unit) {
    val title = session.state.collectAsState().value.title
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("End \"$title\"?") },
        text = { Text("Claude Code stops and the session leaves the grid. The conversation stays in Claude Code's history.") },
        confirmButton = {
            TextButton(onClick = {
                SessionManager.close(session.id)
                onDone()
            }) { Text("End session") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
