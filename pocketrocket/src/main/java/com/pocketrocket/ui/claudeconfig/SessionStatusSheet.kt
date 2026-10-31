package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.claudeconfig.McpGroups
import com.pocketrocket.claudeconfig.McpPanel
import com.pocketrocket.claudeconfig.McpPanelState
import com.pocketrocket.claudeconfig.SessionMcpHost
import com.pocketrocket.claudeconfig.TerminalFallback
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.ui.chat.ChatSheet
import com.pocketrocket.ui.chat.SessionActivity
import com.pocketrocket.ui.sessions.displayPath
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import java.io.File

/**
 * The session's status, from its controls sheet: the app's stand-in for `/status`, which Claude Code doesn't offer in
 * `-p` mode. Who it runs as, the version and model, then its MCP servers, refreshed while they load. Asking stops when
 * the sheet closes; everything goes over the session's own process, which this never starts.
 */
@Composable
fun SessionStatusSheet(session: Session, state: SessionState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panel = remember(session.id) { McpPanel(SessionMcpHost(context.applicationContext, session), scope) }
    val alive by session.alive.collectAsState()
    val mcp by panel.state.collectAsState()

    LaunchedEffect(alive) { if (alive) panel.refresh() }

    ChatSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("STATUS")
            val account = state.identity?.account
            StatusRow("Account", account?.email ?: "Not reported yet")
            account?.teamName?.let { StatusRow("Organization", it) }
            account?.planLabel?.let { StatusRow("Plan", it) }
            StatusRow("Claude Code", state.identity?.version ?: "Not reported yet", mono = true)
            StatusRow("Model", state.modelName ?: "Default")
            StatusRow("Account here", state.profile.name)
            StatusRow("Config", displayPath(state.profile.configDir ?: "~/.claude"), mono = true)
            StatusRow("Folder", displayPath(state.folder), mono = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Heading("MCP SERVERS", McpGroups.summary(mcp.listing).ifEmpty { null })
            McpStatusLine(mcp, alive, onRefresh = panel::refresh)
            if (mcp.loaded) {
                McpServerList(
                    panel, mcp,
                    terminal = TerminalFallback.hint(File(state.folder), state.profile.configDir, AccountWiring.claudeFor(state.folder).configDir?.path),
                    terminalName = state.title,
                    onNewSession = if (state.demo) null else ({
                        val started = SessionManager.startClaude(context, File(state.folder), state.profile)
                        SessionActivity.open(context, started.id)
                        onDismiss()
                    }),
                )
            }
        }
    }
}

/** What is going on with the list, and Refresh once nothing is. */
@Composable
private fun McpStatusLine(mcp: McpPanelState, alive: Boolean, onRefresh: () -> Unit) {
    val c = PrTheme.colors
    val text = when {
        !alive -> "Claude Code isn't running in this session. It lists its servers once it starts."
        mcp.statusUnsupported -> "This version of Claude Code doesn't report its servers. In a terminal, /mcp shows them."
        mcp.refreshing -> "Loading servers. Connectors can take up to 30 s to appear."
        mcp.statusProblem != null -> "Couldn't get the list: ${mcp.statusProblem}"
        mcp.loaded && mcp.servers.isEmpty() -> "No MCP servers in this session."
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mcp.refreshing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
        Text(text ?: "Checked just now.", style = PrType.caption, color = c.textMuted, modifier = Modifier.weight(1f))
        if (alive && !mcp.refreshing && !mcp.statusUnsupported) {
            TextButton(onClick = onRefresh) { Text("Refresh", style = PrType.small, color = c.accent) }
        }
    }
}

/** The sheet's capitals heading, as in the controls sheet. */
@Composable
internal fun Heading(text: String, trailing: String? = null) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = PrType.section, color = c.textMuted)
        trailing?.let {
            Text(it, style = PrType.caption, color = c.textMuted, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, mono: Boolean = false) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = PrType.small, color = c.textMuted)
        Text(
            value, style = if (mono) PrType.codeSmall else PrType.small, color = c.text,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
