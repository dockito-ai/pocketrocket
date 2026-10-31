package com.pocketrocket.ui.workspaces

import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.workspaces.RepoStatus
import com.pocketrocket.workspaces.RepoSummary
import com.pocketrocket.workspaces.Workspace
import com.pocketrocket.workspaces.RepoScan
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A folder heading in the tree with the workspaces right under it, or a plain folder holding none. */
private sealed interface TreeNode {
    val key: String
    /**
     * A folder with workspaces in it ("work/"); [label] is empty for ~/projects itself. [repos] are repos in it
     * that aren't workspaces (decision 72), offered as "Make workspace".
     */
    data class Group(override val key: String, val label: String, val folder: String, val workspaces: List<Workspace>, val repos: List<File>) : TreeNode
    /** A folder under ~/projects without workspaces, collapsed with its subfolder count. */
    data class Folder(override val key: String, val label: String, val folder: File, val children: List<File>) : TreeNode
}

/** Reads folders (repos at any depth, decision 72): call it off the main thread. */
private fun tree(workspaces: List<Workspace>): List<TreeNode> {
    val root = Workspaces.projectsRoot
    val repos = RepoScan.notWorkspaces(root, workspaces.map { it.path })
    val byParent = workspaces.groupBy { File(it.path).parent.orEmpty() }
    val reposByParent = repos.groupBy { it.parent.orEmpty() }
    val groups = (byParent.keys + reposByParent.keys).map { parent ->
        val label = when {
            parent == root.path -> ""
            parent.startsWith(root.path + "/") -> parent.removePrefix(root.path + "/") + "/"
            else -> Workspaces.tilde(parent) + "/"
        }
        TreeNode.Group(
            "g:$parent", label, parent,
            byParent[parent].orEmpty().sortedBy { it.name.lowercase() },
            reposByParent[parent].orEmpty().sortedBy { it.name.lowercase() },
        )
    }.sortedBy { it.label }
    val taken = workspaces.map { it.path } + repos.map { it.path }
    val folders = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .filter { dir -> taken.none { it == dir.path || it.startsWith(dir.path + "/") } }
        .sortedBy { it.name.lowercase() }
        .map { dir -> TreeNode.Folder("f:${dir.path}", dir.name + "/", dir, dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }) }
    return groups + folders
}

/** Workspaces tab (mockup Workspaces): the ~/projects tree with each workspace's repos and sessions. */
@Composable
fun WorkspacesScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val workspaces by Workspaces.state.collectAsState()
    val repos by RepoStatus.all.collectAsState()
    val sessions by SessionManager.sessions.collectAsState()
    val states = sessions.map { it.state.collectAsState().value }
    var refresh by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    var closed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<Pair<String, Workspace?>?>(null) }
    var marking by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    LaunchedEffect(workspaces, refresh) { RepoStatus.refreshAll(context, workspaces) }
    // The walk for repos outside workspaces reads folders: off the main thread, keeping the last tree meanwhile.
    val nodes by produceState<List<TreeNode>?>(null, workspaces, refresh) { value = withContext(Dispatchers.IO) { tree(workspaces) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Workspaces", style = mockText(26.sp, FontWeight.Bold, c.text, letterSpacing = (-0.5).sp))
            Text("~/projects", style = mockText(12.sp, color = c.textMuted, mono = true))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("Clone repo", { navigate(Routes.clone(Workspaces.projectsRoot.path)) }, Modifier.weight(1f), height = 44.dp, radius = 12.dp, fontSize = 14.sp)
            OutlineButton("New workspace", { navigate(Routes.NEW_WORKSPACE) }, Modifier.weight(1f))
            OutlineButton("Folder", { newFolder = true }, Modifier.weight(0.7f))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (nodes?.isEmpty() == true) item {
                Text(
                    "No workspaces yet. A workspace is a folder under ~/projects that holds one or more repos. Clone a repo or make a new workspace to start.",
                    style = mockText(13.sp, color = c.textMuted).copy(lineHeight = 19.sp),
                    modifier = Modifier.padding(4.dp),
                )
            }
            nodes.orEmpty().forEachIndexed { index, node ->
                when (node) {
                    is TreeNode.Group -> {
                        val expanded = node.key !in closed
                        val indent = if (node.label.isEmpty()) 0.dp else 16.dp
                        if (node.label.isNotEmpty()) item(key = node.key) {
                            FolderHeading(node.label, expanded, first = index == 0, count = null,
                                onClick = { closed = if (expanded) closed + node.key else closed - node.key },
                                onLongClick = { editing = node.folder to null })
                        }
                        if (expanded) node.workspaces.forEach { ws ->
                            item(key = ws.path) {
                                WorkspaceCard(
                                    ws, repos[ws.path], states.filter { Workspaces.containing(it.folder)?.path == ws.path },
                                    Modifier.padding(start = indent),
                                    onClick = { navigate(Routes.workspace(ws.path)) },
                                    onLongClick = { editing = ws.path to ws },
                                )
                            }
                        }
                        if (expanded) node.repos.forEach { repo ->
                            item(key = "r:" + repo.path) {
                                NotWorkspaceRow(repo.name, Modifier.padding(start = indent)) { navigate(Routes.newWorkspace(repo.path)) }
                            }
                        }
                    }
                    is TreeNode.Folder -> {
                        val expanded = node.key in open
                        item(key = node.key) {
                            FolderHeading(node.label, expanded, first = index == 0, count = node.children.size,
                                onClick = { open = if (expanded) open - node.key else open + node.key },
                                onLongClick = { editing = node.folder.path to null })
                        }
                        if (expanded) {
                            item(key = node.key + ":mark") { SubfolderRow(node.folder.name + "/ (this folder)") { marking = node.folder } }
                            node.children.forEach { child -> item(key = child.path) { SubfolderRow(child.name + "/") { marking = child } } }
                        }
                    }
                }
            }
            item {
                Text(
                    "Long-press a workspace or folder to rename or move it.",
                    style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    editing?.let { (path, ws) ->
        RenameMoveDialog(
            title = if (ws != null) "Rename or move" else "Move folder",
            name = ws?.name,
            path = path,
            onDismiss = { editing = null },
            onConfirm = { name, to ->
                if (ws != null && name != null) Workspaces.rename(ws.path, name)
                Workspaces.move(path, to).also { if (it == null) refresh++ }
            },
        )
    }
    marking?.let { folder ->
        FolderPrompt(
            title = "Make it a workspace?",
            initial = Workspaces.tilde(folder.path),
            confirm = "Make workspace",
            body = "Its repos show up here, and sessions and clones in it use the default Claude and GitHub accounts. Change them in its settings.",
            onDismiss = { marking = null },
            onConfirm = { path ->
                if (!File(path).isDirectory) "That folder doesn't exist." else {
                    Workspaces.add(path, File(path).name, claude = null, github = null)
                    navigate(Routes.workspace(path))
                    null
                }
            },
        )
    }
    if (newFolder) {
        FolderPrompt(
            title = "New folder",
            initial = "~/projects/",
            confirm = "Create",
            body = "A folder to group workspaces in, like work/ or personal/.",
            onDismiss = { newFolder = false },
            onConfirm = { path ->
                val dir = File(path)
                when {
                    dir.path == Workspaces.projectsRoot.path -> "Give the folder a name."
                    dir.exists() -> "It already exists."
                    !dir.mkdirs() -> "Couldn't create it."
                    else -> { refresh++; null }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeading(label: String, expanded: Boolean, first: Boolean, count: Int?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 4.dp, end = 4.dp, top = if (first) 10.dp else 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (expanded) MockIcons.ChevronDown else MockIcons.ChevronRight, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
        Text(label, style = mockText(13.sp, color = c.textMuted, mono = true))
        if (count != null && !expanded) Text("· $count", style = mockText(13.sp, color = c.textMuted))
    }
}

@Composable
private fun SubfolderRow(label: String, onClick: () -> Unit) {
    val c = PrTheme.colors
    MockBox(Modifier.fillMaxWidth().padding(start = 16.dp), radius = 12.dp, background = c.background, border = BorderStroke(1.dp, c.iconTile), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = mockText(13.sp, color = c.textSoft, mono = true), modifier = Modifier.weight(1f))
            Text("Make workspace", style = mockText(12.sp, color = c.accent))
        }
    }
}

/** A repo that isn't a workspace (decision 72): "Not a workspace · Make workspace" opens New workspace on it. */
@Composable
private fun NotWorkspaceRow(name: String, modifier: Modifier, onClick: () -> Unit) {
    val c = PrTheme.colors
    MockBox(modifier.fillMaxWidth(), radius = 12.dp, background = c.background, border = BorderStroke(1.dp, c.iconTile), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = mockText(13.sp, color = c.textSoft, mono = true), modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                buildAnnotatedString {
                    append("Not a workspace · ")
                    withStyle(SpanStyle(color = c.accent)) { append("Make workspace") }
                },
                style = mockText(12.sp, color = c.textMuted),
            )
        }
    }
}

/** What a workspace's sessions add up to: the status line on its card. */
internal fun sessionsLine(states: List<SessionState>): Pair<String, SessionStatus?> {
    val waiting = states.count { it.status == SessionStatus.WaitingForPermission }
    val working = states.count { it.status == SessionStatus.Working || it.status == SessionStatus.Starting }
    return when {
        waiting > 0 -> "$waiting needs you" to SessionStatus.WaitingForPermission
        working > 0 -> "$working working" to SessionStatus.Working
        else -> "idle" to null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceCard(ws: Workspace, repos: List<RepoSummary>?, states: List<SessionState>, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = PrTheme.colors
    val (line, status) = sessionsLine(states)
    val needsYou = status == SessionStatus.WaitingForPermission
    MockBox(
        modifier.fillMaxWidth(),
        radius = 16.dp,
        background = c.card,
        border = if (needsYou) BorderStroke(1.5.dp, c.amber) else BorderStroke(1.dp, c.border),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(if (needsYou) 10.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ws.name, style = mockText(16.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f), maxLines = 1)
                Text(line, style = mockText(12.sp, color = when (status) { SessionStatus.WaitingForPermission -> c.amber; null -> c.textMuted; else -> c.blue }))
            }
            when {
                repos == null -> Text("Reading repos…", style = mockText(12.sp, color = c.textMuted))
                repos.isEmpty() -> Text("No repos yet", style = mockText(12.sp, color = c.textMuted))
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repos.forEach { RepoChip(it) }
                }
            }
            if ((repos?.size ?: 0) > 1) {
                Text("${repos!!.size} repos · ${states.size} session${if (states.size == 1) "" else "s"}", style = mockText(12.sp, color = c.textMuted))
            }
        }
    }
}

/** "app main ●3 ↑2" in a small mono chip. */
@Composable
private fun RepoChip(r: RepoSummary) {
    val c = PrTheme.colors
    val text = buildAnnotatedString {
        append(r.name.substringAfterLast('/'))
        append(" ")
        withStyle(SpanStyle(color = c.green)) { append(r.branch) }
        if (r.changed > 0) withStyle(SpanStyle(color = c.amber)) { append(" ●${r.changed}") }
        if (r.ahead > 0) append(" ↑${r.ahead}")
        if (r.behind > 0) append(" ↓${r.behind}")
    }
    Text(text, style = mockText(12.sp, color = c.text, mono = true), modifier = Modifier.background(c.raised, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}
