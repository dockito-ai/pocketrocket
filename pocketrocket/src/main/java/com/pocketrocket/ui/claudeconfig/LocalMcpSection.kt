package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.AccountCli
import com.pocketrocket.claudeconfig.AccountLocks
import com.pocketrocket.claudeconfig.LocalMcpFiles
import com.pocketrocket.claudeconfig.LocalMcpOps
import com.pocketrocket.claudeconfig.LocalMcpPanel
import com.pocketrocket.claudeconfig.LocalMcpRow
import com.pocketrocket.claudeconfig.LocalMcpRows
import com.pocketrocket.claudeconfig.LocalMcpServer
import com.pocketrocket.claudeconfig.McpDraft
import com.pocketrocket.claudeconfig.McpPanelState
import com.pocketrocket.claudeconfig.McpPreview
import com.pocketrocket.claudeconfig.McpState
import com.pocketrocket.claudeconfig.McpTransport
import com.pocketrocket.claudeconfig.ServerNotice
import com.pocketrocket.claudeconfig.StdioAdvice
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.ui.accounts.Confirm
import com.pocketrocket.ui.accounts.KeyValue
import com.pocketrocket.ui.accounts.QuietCard
import com.pocketrocket.ui.accounts.SectionLabel
import com.pocketrocket.ui.accounts.StatusDot
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account's own MCP servers (user scope): list, add, edit, remove. The list comes from that account's
 * `.claude.json` with no values; whether a server works comes only from the check in Connections ([mcp]).
 * [onChanged] starts that check. Nothing typed here is saved by the app, so no state in the form is `rememberSaveable`.
 */
@Composable
internal fun LocalMcpSection(entry: ClaudeAccountEntry, mcp: McpPanelState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnChanged by rememberUpdatedState(onChanged)
    val panel = remember(entry.id) {
        val ops = LocalMcpOps(AccountCli(context, entry.configDir), { LocalMcpFiles.read(entry.configDir) }, LocalMcpFiles.execHelper)
        LocalMcpPanel(ops, scope, AccountLocks.of(entry.id), onChanged = { latestOnChanged() })
    }
    val state by panel.state.collectAsState()
    var adding by remember(entry.id) { mutableStateOf(false) }
    var editing by remember(entry.id) { mutableStateOf<LocalMcpServer?>(null) }
    var removing by remember(entry.id) { mutableStateOf<LocalMcpServer?>(null) }
    var review by remember(entry.id) { mutableStateOf<McpDraft?>(null) }

    LaunchedEffect(panel) { panel.load() }

    val c = PrTheme.colors
    QuietCard {
        SectionLabel("Local MCP servers")
        Text(
            "Servers this account starts in every folder. Each one runs on this phone with the app's access. Changes apply to new sessions, not running ones.",
            style = mockText(13.sp, color = c.textMuted),
        )
        state.problem?.let { Text(it, style = mockText(12.sp, color = c.redSoft)) }
        if (state.loaded && state.servers.isEmpty() && state.problem == null) {
            Text("None yet.", style = mockText(13.sp, color = c.textMuted))
        }
        val rows = LocalMcpRows.of(state.servers, mcp.servers, mcp.checkedAt, state.changedAt, mcp.refreshing, LocalMcpFiles.execHelper)
        rows.forEach { row ->
            key(row.server.name) {
                ServerRow(row, enabled = state.busy == null, onEdit = { editing = row.server }, onRemove = { removing = row.server })
            }
        }
        ConfigStatus(state.busy, state.notice)
        OutlineButton("Add a server", onClick = { adding = true }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth())
    }

    if (adding || editing != null) {
        McpServerForm(
            server = editing,
            state = state,
            onDismiss = {
                adding = false
                editing = null
            },
            onReview = { review = it },
        )
    }
    review?.let { draft ->
        val isEdit = editing != null
        McpReviewDialog(
            draft, isEdit, onDismiss = { review = null },
        ) {
            review = null
            val close = {
                adding = false
                editing = null
            }
            if (isEdit) panel.edit(draft, close) else panel.add(draft, close)
        }
    }
    removing?.let { server ->
        Confirm(
            "Remove ${server.name}?",
            "New sessions on this account won't start it; running ones keep it until they end. Anything saved with it is deleted and can't be shown again, so you'd have to type it again to add it back.",
            "Remove",
            onDismiss = { removing = null },
        ) { panel.remove(server.name) }
    }
}

@Composable
private fun ServerRow(row: LocalMcpRow, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val server = row.server
    var expanded by remember { mutableStateOf(false) }
    val failed = row.status?.state == McpState.Failed
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.iconTile, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(stateColor(row.status?.state ?: McpState.Unknown))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(server.name, style = mockText(14.sp, color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    row.line, style = mockText(11.sp, color = if (failed) c.redSoft else c.textMuted),
                    maxLines = if (expanded) 8 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("Type", server.type, mono = true)
                server.command?.let { KeyValue("Runs", it, mono = true) }
                if (server.argCount > 0) KeyValue("Arguments", "${server.argCount}, hidden")
                server.urlHost?.let { KeyValue("Host", it, mono = true) }
                if (server.envKeys.isNotEmpty()) KeyValue("Variables", server.envKeys.joinToString(", ") + ", values hidden")
                if (server.headerKeys.isNotEmpty()) KeyValue("Headers", server.headerKeys.joinToString(", ") + ", values hidden")
                row.help?.let { Text(it, style = mockText(12.sp, color = c.textSoft)) }
                when {
                    server.managedByTools -> Text("Installed and removed in Settings, Tools.", style = mockText(12.sp, color = c.textMuted))
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (server.canEdit) ActionPill("Edit", accent = false, enabled = enabled, onClick = onEdit)
                        if (server.canRemove) ActionPill("Remove", accent = false, enabled = enabled, onClick = onRemove)
                    }
                }
                if (!server.managedByTools && !server.canEdit) {
                    Text("This type of server can't be edited here. Remove it and add it again, or use a terminal.", style = mockText(12.sp, color = c.textMuted))
                }
            }
        }
    }
}

/** The confirmation before a server goes in: what will run, exactly, and what that means. */
@Composable
private fun McpReviewDialog(draft: McpDraft, isEdit: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = PrTheme.colors
    val preview = remember(draft) { McpPreview.of(draft, LocalMcpFiles.execHelper) }
    val warnings by produceState(emptyList<String>(), draft) {
        if (draft.transport == McpTransport.Stdio) {
            val first = withContext(Dispatchers.IO) { LocalMcpFiles.firstLine(StdioAdvice.scriptPath(draft.command, LocalMcpFiles.binDir)) }
            value = StdioAdvice.warnings(draft.command, first)
        }
    }
    ReviewDialog(if (isEdit) "Save ${draft.name}?" else "Add ${draft.name}?", if (isEdit) "Save" else "Add", onDismiss, onConfirm) {
        Text(
            if (draft.transport == McpTransport.Stdio) "This command will run on this phone with the app's access to your files and accounts, every time a session starts on this account:"
            else "Sessions on this account will connect to this address and send it what Claude asks of its tools:",
            style = mockText(14.sp, color = c.textMuted),
        )
        Text(preview.what, style = mockText(12.sp, color = c.text, mono = true))
        preview.notes.forEach { Text(it, style = mockText(12.sp, color = c.textMuted)) }
        warnings.forEach { Text(it, style = mockText(12.sp, color = c.amber)) }
        Text(
            if (isEdit) "Claude Code has no edit, so this removes it and adds it again. If the new one is refused, the old one is put back. A server you signed in to may ask again. Applies to new sessions."
            else "Applies to new sessions. Whether it starts shows here after a check; adding it doesn't prove it works.",
            style = mockText(12.sp, color = c.textMuted),
        )
    }
}

/** A confirmation with room for a long command, shown in full. */
@Composable
internal fun ReviewDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What is under way, and how the last thing went. */
@Composable
internal fun ConfigStatus(busy: String?, notice: ServerNotice?) {
    val c = PrTheme.colors
    if (busy != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
            Text(busy, style = mockText(12.sp, color = c.textMuted), modifier = Modifier.weight(1f))
        }
    }
    notice?.let {
        val color = when (it.kind) {
            ServerNotice.Kind.Success -> c.green
            ServerNotice.Kind.Problem -> c.redSoft
            ServerNotice.Kind.Info -> c.textSoft
        }
        Text(it.text, style = mockText(12.sp, color = color))
    }
}
