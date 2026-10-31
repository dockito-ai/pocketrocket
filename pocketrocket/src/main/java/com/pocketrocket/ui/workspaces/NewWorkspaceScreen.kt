package com.pocketrocket.ui.workspaces

import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.GitCli
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.ChoiceCard
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.RadioDot
import com.pocketrocket.ui.components.TwoColumns
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class StartWith { Clone, Init, Folder }

/**
 * New workspace (mockup NewWorkspace): a name, a folder under ~/projects that Shankar picks, the Claude
 * and GitHub accounts (defaults preselected) and how to start. Creating it binds the folder to the
 * accounts (AccountWiring.bind). [onCreated] gets the folder and whether to go on to Clone.
 * With [initialFolder] (a repo the Workspaces list found outside any workspace, decision 72) it makes that
 * folder a workspace: name and location are filled in and there is nothing to start with.
 */
@Composable
fun NewWorkspaceScreen(onClose: () -> Unit, onCreated: (path: String, clone: Boolean) -> Unit, initialFolder: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = PrTheme.colors
    val claude by ClaudeAccounts.state.collectAsState()
    val github by GitHubAccounts.state.collectAsState()
    val existing = initialFolder != null && File(initialFolder).isDirectory
    var name by rememberSaveable { mutableStateOf(initialFolder?.let { File(it).name }.orEmpty()) }
    /** A folder Shankar typed with Change; until then it follows the name. */
    var customPath by rememberSaveable { mutableStateOf(initialFolder) }
    val dockito by DockitoAccounts.state.collectAsState()
    /** Picked accounts; null is "Default", which follows the default. */
    var claudeId by rememberSaveable { mutableStateOf<String?>(null) }
    var githubId by rememberSaveable { mutableStateOf<String?>(null) }
    var dockitoId by rememberSaveable { mutableStateOf<String?>(null) }
    var start by rememberSaveable { mutableStateOf(if (GitHubAccounts.default != null && !existing) StartWith.Clone else StartWith.Folder) }
    var changing by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val path = customPath ?: File(Workspaces.projectsRoot, if (name.isBlank()) "new-workspace" else AccountPaths.slug(name)).path
    val ghAccount = githubId?.let(GitHubAccounts::get) ?: github.default

    fun create() {
        val folder = AccountPaths.normalize(path)
        error = when {
            name.isBlank() -> "Give the workspace a name."
            Workspaces.get(folder) != null -> "${Workspaces.tilde(folder)} is already a workspace."
            File(folder).isFile -> "${Workspaces.tilde(folder)} is a file."
            else -> null
        }
        if (error != null) return
        val ws = Workspaces.add(
            folder, name,
            claude = claudeId, github = githubId, dockito = dockitoId,
        )
        scope.launch {
            if (start == StartWith.Init) withContext(Dispatchers.IO) { GitCli(context.applicationContext, ws.dir).run("init", "-b", "main") }
            onCreated(ws.path, start == StartWith.Clone)
        }
    }

    MockPage {
        MockTopBar(if (existing) "Make workspace" else "New workspace", MockIcons.Close, onClose, titleSize = 17.sp)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caps("NAME")
                MockTextField(name, { name = it; error = null }, placeholder = "client-app")
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caps("LOCATION")
                MockBox(Modifier.fillMaxWidth(), background = c.surface, onClick = { changing = true }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Workspaces.tilde(path), style = mockText(12.5.sp, color = c.text, mono = true), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Change", style = mockText(12.sp, color = c.accent))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("CLAUDE ACCOUNT")
                AccountDropdown(claudeOptions(claude.accounts), claudeId, claude.default?.id, { claudeId = it })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("GITHUB ACCOUNT")
                if (github.accounts.isEmpty()) {
                    Text("No GitHub account connected yet. Connect one in Settings › GitHub to clone private repos and push; public repos clone by URL.", style = mockText(11.sp, color = c.textMuted).copy(lineHeight = 15.4.sp))
                } else {
                    AccountDropdown(githubOptions(github.accounts), githubId, github.default?.id, { githubId = it })
                    ghAccount?.let { a ->
                        Text(
                            buildAnnotatedString {
                                append("Clones, pushes and gh commands in this workspace use this account. Commits are signed as ")
                                withStyle(SpanStyle(color = c.textSoft)) { append(identityLine(a)) }
                                append(".")
                            },
                            style = mockText(11.sp, color = c.textMuted).copy(lineHeight = 15.4.sp),
                        )
                    }
                }
            }
            if (dockito.accounts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caps("DOCKITO ACCOUNT")
                    AccountDropdown(dockitoOptions(dockito.accounts), dockitoId, dockito.default?.id, { dockitoId = it })
                }
            }
            if (!existing) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("START WITH")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StartOption(if (ghAccount != null) "Clone repos from ${ghAccount.login}" else "Clone a repo by URL", start == StartWith.Clone) { start = StartWith.Clone }
                    StartOption("A new empty repo (git init)", start == StartWith.Init) { start = StartWith.Init }
                    StartOption("Just the folder, add repos later", start == StartWith.Folder) { start = StartWith.Folder }
                }
            }
            error?.let { Text(it, style = mockText(12.sp, color = c.redSoft)) }
        }
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 22.dp)) {
            AccentButton(
                if (existing) "Make workspace" else when (start) { StartWith.Clone -> "Create and pick repos"; StartWith.Init -> "Create and git init"; StartWith.Folder -> "Create workspace" },
                ::create, height = 50.dp, radius = 14.dp, fontSize = 14.sp,
            )
        }
    }

    if (changing) {
        FolderPrompt(
            title = "Workspace folder",
            initial = Workspaces.tilde(path),
            confirm = "Use this folder",
            body = "Anywhere under ~/projects, in your own structure: work/client-app, personal/notes…",
            onDismiss = { changing = false },
            onConfirm = { p -> if (p == Workspaces.projectsRoot.path) "Pick a folder inside ~/projects." else { customPath = p; null } },
        )
    }
}

@Composable
private fun StartOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    MockBox(
        Modifier.fillMaxWidth(),
        background = if (selected) c.accent.copy(alpha = 0.08f) else c.background,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.border),
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioDot(selected, size = 14.dp, onWidth = 4.dp)
            Text(text, style = mockText(13.sp, color = c.text))
        }
    }
}
