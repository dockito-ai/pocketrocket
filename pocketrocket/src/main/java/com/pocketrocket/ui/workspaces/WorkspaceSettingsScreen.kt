package com.pocketrocket.ui.workspaces

import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.workspaces.RepoStatus
import com.pocketrocket.workspaces.Workspaces

/**
 * Workspace settings (mockup WorkspaceSettings): its Claude and GitHub accounts (re-bound on every
 * change), rename or move, and remove from the list. Files are only deleted after typing the name.
 */
@Composable
fun WorkspaceSettingsScreen(path: String, onBack: () -> Unit, onOpenMemory: () -> Unit, onRemoved: () -> Unit, onMoved: (String) -> Unit) {
    val c = PrTheme.colors
    val workspaces by Workspaces.state.collectAsState()
    val ws = workspaces.firstOrNull { it.path == path }
    if (ws == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val claude by ClaudeAccounts.state.collectAsState()
    val github by GitHubAccounts.state.collectAsState()
    val bindings by AccountWiring.bindings.collectAsState()
    val repos by RepoStatus.all.collectAsState()
    val binding = remember(bindings) { bindings.firstOrNull { it.folder == ws.path } }
    val dockito by DockitoAccounts.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    /** Changes one kind's account; null is "Default", which follows the default. */
    fun bind(claude: String? = binding?.claude, github: String? = binding?.github, dockito: String? = binding?.dockito) =
        AccountWiring.bind(ws.path, claude, github, dockito)

    MockPage {
        MockTopBar("${ws.name} settings", MockIcons.Back, onBack, titleSize = 17.sp, subtitle = Workspaces.tilde(ws.path), subtitleSize = 11.sp)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("CLAUDE ACCOUNT")
                AccountDropdown(claudeOptions(claude.accounts), binding?.claude, claude.default?.id, { bind(claude = it) })
                Note("New sessions here use it. Running sessions keep their account until you restart them.")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("GITHUB ACCOUNT")
                if (github.accounts.isEmpty()) Note("No GitHub account connected. Connect one in Settings › GitHub.")
                else AccountDropdown(githubOptions(github.accounts), binding?.github, github.default?.id, { bind(github = it) })
                val count = repos[ws.path]?.size ?: 0
                Note("Applies to ${if (count == 1) "its repo" else "all $count repos"}: push and pull keys, gh, and commit name and email.")
            }
            if (dockito.accounts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caps("DOCKITO ACCOUNT")
                    AccountDropdown(dockitoOptions(dockito.accounts), binding?.dockito, dockito.default?.id, { bind(dockito = it) })
                    Note("dockito in sessions and terminals here runs as this account.")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("MEMORY")
                OutlineButton("Edit this folder's CLAUDE.md", onOpenMemory, Modifier.fillMaxWidth())
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caps("DEFAULTS FOR NEW SESSIONS", modifier = Modifier.padding(bottom = 6.dp))
                DefaultRow("Model", "Claude Code's default", divider = true)
                DefaultRow("Mode", "Ask", divider = true)
                DefaultRow("Always-allowed commands", "per session", divider = false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Rename or move", { editing = true }, Modifier.fillMaxWidth())
                OutlineButton("Remove workspace…", { removing = true }, Modifier.fillMaxWidth(), color = c.redSoft, border = c.redBorder)
            }
        }
    }

    if (editing) {
        RenameMoveDialog(
            title = "Rename or move",
            name = ws.name,
            path = ws.path,
            onDismiss = { editing = false },
            onConfirm = { name, to ->
                name?.let { Workspaces.rename(ws.path, it) }
                Workspaces.move(ws.path, to).also { if (it == null && to != ws.path) onMoved(to) }
            },
        )
    }
    if (removing) RemoveDialog(ws.name, Workspaces.tilde(ws.path), onDismiss = { removing = false }) { deleteFiles ->
        Workspaces.remove(ws.path)
        if (deleteFiles) ws.dir.deleteRecursively()
        onRemoved()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = mockText(11.sp, color = PrTheme.colors.textMuted).copy(lineHeight = 15.4.sp))
}

/** A row of "DEFAULTS FOR NEW SESSIONS". Read-only until per-workspace session defaults are built. */
@Composable
private fun DefaultRow(label: String, value: String, divider: Boolean) {
    val c = PrTheme.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = mockText(14.sp, color = c.text), modifier = Modifier.weight(1f))
            Text(value, style = mockText(14.sp, color = c.textMuted))
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = c.divider)
    }
}

/**
 * Remove from the list (files stay), or, only with the box ticked and the name typed, delete the folder
 * and everything in it.
 */
@Composable
private fun RemoveDialog(name: String, folder: String, onDismiss: () -> Unit, onConfirm: (deleteFiles: Boolean) -> Unit) {
    var delete by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val ready = !delete || typed.trim() == name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It leaves the Workspaces list and its account settings are dropped. The folder $folder and its repos stay on the phone.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(delete, { delete = it })
                    Text("Also delete the folder and everything in it", fontWeight = if (delete) FontWeight.SemiBold else FontWeight.Normal)
                }
                if (delete) OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Type $name to confirm") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(delete) }, enabled = ready) { Text(if (delete) "Delete folder" else "Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
