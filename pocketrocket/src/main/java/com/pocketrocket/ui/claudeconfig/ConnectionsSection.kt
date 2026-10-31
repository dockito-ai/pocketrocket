package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.auth.ClaudeCli
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.claudeconfig.ClaudeConfigProbe
import com.pocketrocket.claudeconfig.ClaudeConnections
import com.pocketrocket.claudeconfig.ConnectionsSnapshot
import com.pocketrocket.claudeconfig.McpGroups
import com.pocketrocket.claudeconfig.McpPanel
import com.pocketrocket.claudeconfig.McpPanelState
import com.pocketrocket.claudeconfig.ProbeMcpHost
import com.pocketrocket.claudeconfig.TerminalFallback
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.ui.accounts.QuietCard
import com.pocketrocket.ui.accounts.SectionLabel
import com.pocketrocket.ui.accounts.ago
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The account's connectors and MCP servers, with no session open. Shows the last check; a new one runs only on the
 * button or after a local server changes, as a short-lived process that sends control requests and exits, and leaving
 * the page ends it.
 */
@Composable
internal fun ConnectionsSection(entry: ClaudeAccountEntry, status: SignInStatus) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panel = remember(entry.id) {
        val host = ProbeMcpHost(context.applicationContext, ClaudeConfigProbe.neutralFolder, ClaudeCli.environment(entry.configDir))
        McpPanel(host, scope, ClaudeConnections.load(entry.id)?.toPanelState() ?: McpPanelState())
    }
    val mcp by panel.state.collectAsState()

    LaunchedEffect(panel) {
        panel.state
            .filter { it.loaded && it.settled && !it.refreshing && it.busy.isEmpty() }
            .distinctUntilChangedBy { it.checkedAt }
            .collect { done ->
                val at = done.checkedAt ?: return@collect
                withContext(Dispatchers.IO) { ClaudeConnections.save(entry.id, ConnectionsSnapshot(done.servers, done.identity, at)) }
            }
    }

    val c = PrTheme.colors
    val signedIn = status as? SignInStatus.SignedIn
    // A check from before this login says nothing about it.
    val seenEmail = mcp.identity?.account?.email
    val current = !(seenEmail != null && signedIn?.account?.email != null && !seenEmail.equals(signedIn.account.email, ignoreCase = true))
    val show = mcp.loaded && current

    QuietCard {
        SectionLabel("Connections")
        when {
            status is SignInStatus.SignedOut -> Text("Sign in to see this account's connectors and MCP servers.", style = mockText(13.sp, color = c.textMuted))
            mcp.statusUnsupported -> Text("This version of Claude Code doesn't report its servers. In a terminal, /mcp shows them.", style = mockText(13.sp, color = c.textMuted))
            else -> {
                if (!show) {
                    Text(
                        "See which connectors and MCP servers this account has. Starts Claude Code for up to a minute; no message is sent and no usage is counted.",
                        style = mockText(13.sp, color = c.textMuted),
                    )
                } else {
                    McpGroups.summary(mcp.listing).takeIf { it.isNotEmpty() }?.let { Text(it, style = mockText(13.sp, color = c.text)) }
                    // Not "none on this account": a process can come up without the account's connectors (docs/claude-config.md).
                    if (mcp.servers.isEmpty()) Text("Claude Code listed no connectors or MCP servers. If you expected some, check again.", style = mockText(13.sp, color = c.textMuted))
                    McpServerList(panel, mcp, terminalHint(entry), terminalName = entry.name)
                    Text("A server added to one folder only shows in that folder's sessions.", style = mockText(11.sp, color = c.textMuted))
                }
                mcp.statusProblem?.let { Text("Couldn't check: $it", style = mockText(12.sp, color = c.redSoft)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mcp.refreshing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                        Text("Asking Claude Code. Connectors can take up to 30 s.", style = mockText(12.sp, color = c.textMuted), modifier = Modifier.weight(1f))
                    } else {
                        val checked = mcp.checkedAt?.takeIf { show }
                        Text(checked?.let { "Checked " + ago(it) + "." } ?: "", style = mockText(11.sp, color = c.textMuted), modifier = Modifier.weight(1f))
                        OutlineButton(
                            if (checked != null) "Check again" else "Check connections",
                            onClick = panel::refresh,
                            enabled = signedIn != null && mcp.busy.isEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    LocalMcpSection(entry, mcp, onChanged = { if (status is SignInStatus.SignedIn) panel.refresh() })
}

/** A folder already on this account if there is one, since the terminal's account follows its folder. */
private fun terminalHint(entry: ClaudeAccountEntry) = (AccountWiring.foldersUsing(claude = entry.id).firstOrNull()?.let { File(it) } ?: AccountPaths.home).let { folder ->
    TerminalFallback.hint(folder, entry.configDir?.path, AccountWiring.claudeFor(folder.path).configDir?.path)
}
