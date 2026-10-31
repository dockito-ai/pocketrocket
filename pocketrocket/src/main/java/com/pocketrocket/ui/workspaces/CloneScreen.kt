package com.pocketrocket.ui.workspaces

import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.notifications.CloneNotifier
import com.pocketrocket.notifications.NotificationPermission
import com.pocketrocket.workspaces.CloneJob
import com.pocketrocket.workspaces.CloneManager
import com.pocketrocket.workspaces.CloneProgress
import com.pocketrocket.workspaces.CloneRequest
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.pocketrocket.workspaces.GhRepo
import com.pocketrocket.workspaces.GhRepos
import com.pocketrocket.workspaces.RemoteBranches
import com.pocketrocket.workspaces.RepoClone
import com.pocketrocket.workspaces.RepoStatus
import com.pocketrocket.workspaces.Workspaces
import java.io.File

/** A row in the picker: one of the account's repos, or the pasted URL. */
private data class Pick(val name: String, val note: String, val description: String, val url: String, val label: String)

private fun looksLikeUrl(text: String) = text.contains("://") || text.startsWith("git@") || text.endsWith(".git")

/**
 * Clone a repo (mockup Clone): pick from a GitHub account's repos (`/user/repos` with its own
 * GH_CONFIG_DIR) or paste a URL, choose the folder, and clone with that account's key. The account is the
 * folder's, and can be switched in the top bar when there are several; a repo cloned as another account
 * than its workspace's is bound to it, so push and pull use it too. A clone outside every workspace
 * becomes a workspace of its own. [onCloned] gets the workspace it landed in.
 *
 * The clone itself runs in [CloneManager], not here (decision 42): leaving the screen or the app leaves it
 * running, with its progress in a notification. While it runs this screen is locked to it (progress and
 * Cancel, no search, no second clone); a failure shows here with Retry until it is put aside.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloneScreen(into: String, onBack: () -> Unit, onCloned: (workspace: String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    var folder by rememberSaveable { mutableStateOf(into) }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFolder by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var askNotifications by remember { mutableStateOf<CloneRequest?>(null) }

    // The clone in the slot: the running one, else the latest result. Back works as on any screen.
    val jobs by CloneManager.jobs.collectAsState()
    val cloneJob = jobs.firstOrNull { it.running } ?: jobs.lastOrNull()
    val running = cloneJob != null
    // Clones this screen watched run land in their workspace as soon as they finish.
    var watched by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(cloneJob?.id, cloneJob?.running) {
        val j = cloneJob ?: return@LaunchedEffect
        if (j.running) { if (j.id !in watched) watched = watched + j.id; return@LaunchedEffect }
        val done = j.progress as? CloneProgress.Done
        if (done?.workspace != null && j.id in watched) {
            CloneManager.dismiss(j.id)
            CloneNotifier.clearResult(context, j.id)
            onCloned(done.workspace)
        }
    }

    // The folder's account unless another is picked from the top bar for this clone.
    val accounts by GitHubAccounts.state.collectAsState()
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountMenu by remember { mutableStateOf(false) }
    val folderAccount = remember(folder, accounts) { AccountWiring.githubFor(folder) }
    val account = chosenId?.let(GitHubAccounts::get) ?: folderAccount
    val repos by produceState<Result<List<GhRepo>>?>(null, account?.id) {
        value = null
        value = account?.let { GhRepos.list(context, it) } ?: Result.success(emptyList())
    }

    val picks: List<Pick> = buildList {
        val q = query.trim()
        if (looksLikeUrl(q)) add(Pick(RepoClone.nameFor(q), "URL", q, q, RepoClone.labelFor(q)))
        val useSsh = account?.sshKey?.exists() == true
        repos?.getOrNull()?.filter { q.isEmpty() || looksLikeUrl(q) || "${it.owner}/${it.name}".contains(q, ignoreCase = true) }?.forEach {
            val visibility = if (it.private) "private" else "public"
            val note = if (it.owner.equals(account?.login, ignoreCase = true)) visibility else "$visibility · ${it.owner}"
            add(Pick(it.name, note, it.description, if (useSsh) it.sshUrl else it.httpsUrl, "${it.owner}/${it.name}"))
        }
    }
    val selected = picks.firstOrNull { it.url == picked } ?: picks.firstOrNull { it.note == "URL" }
    val target = targetName ?: selected?.name ?: "repo"

    // The branch to check out: the remote's default unless one is picked for this repo. Branches are
    // listed (git ls-remote, as the account) the first time the menu opens for a repo.
    var chosenBranch by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) } // url to branch
    val branch = chosenBranch?.takeIf { it.first == selected?.url }?.second
    var branchMenu by remember { mutableStateOf(false) }
    var branchesOf by remember { mutableStateOf<String?>(null) }
    val branches by produceState<Result<RemoteBranches>?>(null, branchesOf, account?.id) {
        value = null
        value = branchesOf?.let { RepoClone.branches(context, it, File(folder), account) }
    }

    fun start(request: CloneRequest) { CloneManager.start(context, request) }

    /** Hands the clone to [CloneManager]; the first time, while notifications are off, asks about them. */
    fun clone() {
        val pick = selected ?: return
        val request = CloneRequest(pick.url, folder, target, account?.id, branch, pick.label)
        if (NotificationPermission.shouldAsk(context)) askNotifications = request else start(request)
    }

    MockPage {
        MockTopBar("Clone a repo", MockIcons.Back, onBack) {
            account?.takeUnless { running }?.let { a ->
                val switchable = accounts.accounts.size > 1 && !running
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = switchable) { accountMenu = true }.padding(start = 8.dp, end = if (switchable) 4.dp else 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("GitHub · ${a.login}", style = mockText(12.sp, color = if (switchable) c.textSoft else c.textMuted))
                        if (switchable) Icon(MockIcons.ChevronDown, contentDescription = "Switch GitHub account", tint = c.textMuted, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        accounts.accounts.forEach { other ->
                            val note = listOfNotNull(
                                "default".takeIf { other.id == accounts.default?.id },
                                "this folder's".takeIf { other.id == folderAccount?.id && folderAccount?.id != accounts.default?.id },
                            ).joinToString(" · ")
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(other.login, style = mockText(14.sp, if (other.id == a.id) FontWeight.SemiBold else FontWeight.Normal, c.text))
                                        if (note.isNotEmpty()) Text(note, style = mockText(11.sp, color = c.textMuted))
                                    }
                                },
                                trailingIcon = { if (other.id == a.id) Icon(MockIcons.Check, contentDescription = "In use", tint = c.accent, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    accountMenu = false
                                    if (other.id != a.id) { chosenId = other.id.takeUnless { it == folderAccount?.id }; picked = null; targetName = null }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (cloneJob != null) {
            CloneInSlot(cloneJob, onOpen = { ws -> CloneManager.dismiss(cloneJob.id); CloneNotifier.clearResult(context, cloneJob.id); onCloned(ws) })
            return@MockPage
        }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp)) {
            MockTextField(query, { query = it; picked = null; targetName = null }, placeholder = "Search your repos, or paste a URL", background = c.card)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(picks, key = { it.url }) { p ->
                val on = p == selected
                MockBox(
                    Modifier.fillMaxWidth(),
                    background = if (on) c.accent.copy(alpha = 0.10f) else c.card,
                    border = BorderStroke(1.5.dp, if (on) c.accent else c.border),
                    onClick = { picked = p.url; targetName = null },
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.name, style = mockText(14.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.note, style = mockText(11.sp, color = c.textMuted))
                        }
                        if (p.description.isNotBlank()) Text(p.description, style = mockText(12.sp, color = c.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                val hint = when {
                    account == null -> "Paste a repo URL above. Connect a GitHub account in Settings › GitHub to pick from your repos."
                    repos == null -> "Loading ${account.login}'s repos…"
                    repos!!.isFailure -> "Couldn't list ${account.login}'s repos: ${repos!!.exceptionOrNull()?.message}. A pasted URL still works."
                    picks.isEmpty() -> "No repos match."
                    else -> null
                }
                hint?.let { Text(it, style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp), modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)) }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Column(
            Modifier.fillMaxWidth().background(c.bar).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Caps("INTO FOLDER", size = 12.sp, spacing = 0.4.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                crumbs(folder).forEach { (label, path) ->
                    Text(
                        label, style = mockText(13.sp, color = c.text, mono = true),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.raised).clickable(enabled = !running) { editingFolder = path }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text("/", style = mockText(13.sp, color = c.textFaint, mono = true))
                }
                Text(
                    target, style = mockText(13.sp, color = c.accent, mono = true),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).drawBehind {
                        val r = 8.dp.toPx()
                        drawRoundRect(
                            c.accent, cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                        )
                    }.clickable(enabled = !running) { renaming = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Branch", style = mockText(12.sp, color = c.textMuted))
                Box {
                    val known = branches?.getOrNull()?.takeIf { branchesOf == selected?.url }
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(c.raised)
                            .clickable(enabled = !running && selected != null) { branchesOf = selected?.url; branchMenu = true }
                            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val label = branch ?: listOfNotNull("default", known?.default).joinToString(" · ")
                        Text(label, style = mockText(13.sp, color = if (branch != null) c.accent else c.text, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(MockIcons.ChevronDown, contentDescription = "Pick a branch", tint = c.textMuted, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                        val result = branches?.takeIf { branchesOf == selected?.url }
                        when {
                            result == null -> DropdownMenuItem(text = { Text("Loading branches…", style = mockText(13.sp, color = c.textMuted)) }, onClick = {}, enabled = false)
                            result.isFailure -> DropdownMenuItem(
                                text = { Text("Couldn't list branches: ${result.exceptionOrNull()?.message}", style = mockText(12.sp, color = c.redSoft)) },
                                onClick = {}, enabled = false,
                            )
                            else -> {
                                val list = result.getOrThrow()
                                if (list.names.isEmpty()) DropdownMenuItem(text = { Text("No branches yet", style = mockText(13.sp, color = c.textMuted)) }, onClick = {}, enabled = false)
                                list.names.forEach { name ->
                                    val current = name == (branch ?: list.default)
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(name, style = mockText(14.sp, if (current) FontWeight.SemiBold else FontWeight.Normal, c.text, mono = true))
                                                if (name == list.default) Text("default", style = mockText(11.sp, color = c.textMuted))
                                            }
                                        },
                                        trailingIcon = { if (current) Icon(MockIcons.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            branchMenu = false
                                            chosenBranch = selected?.url?.let { url -> (url to name).takeUnless { name == list.default } }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text("Tap a folder to change it, or create a new one. Same folder can hold several repos.", style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp))
            AccentButton("Clone $target", ::clone, enabled = selected != null)
        }
    }

    askNotifications?.let { request ->
        val answered = {
            NotificationPermission.markAsked(context)
            askNotifications = null
            start(request)
        }
        AlertDialog(
            onDismissRequest = answered,
            title = { Text("Turn on notifications?") },
            text = { Text("The clone keeps going if you leave PocketRocket, and a notification shows its progress and when it's done. Android has notifications off for PocketRocket right now.") },
            confirmButton = { TextButton(onClick = { answered(); NotificationPermission.openSettings(context) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = answered) { Text("Not now") } },
        )
    }

    editingFolder?.let { start ->
        FolderPrompt(
            title = "Clone into",
            initial = Workspaces.tilde(start) + "/",
            confirm = "Use this folder",
            body = "Any folder; a new one is created. Repos cloned outside a workspace become a workspace of their own.",
            onDismiss = { editingFolder = null },
            onConfirm = { p -> folder = p; null },
        )
    }
    if (renaming) {
        FolderPrompt(
            title = "Folder name for the repo",
            initial = target,
            confirm = "Use this name",
            onDismiss = { renaming = false },
            onConfirm = { p -> File(p).name.takeIf { it.isNotBlank() }?.let { targetName = it; null } ?: "Give it a name." },
        )
    }
}

/** "~/projects", "work", "pocketrocket" with the folder each one stands for. */
private fun crumbs(folder: String): List<Pair<String, String>> {
    val root = Workspaces.projectsRoot.path
    val home = AccountPaths.home.path
    val (base, label) = when {
        folder == root || folder.startsWith("$root/") -> root to "~/projects"
        folder == home || folder.startsWith("$home/") -> home to "~"
        else -> "" to ""
    }
    val rest = folder.removePrefix(base).trim('/').split('/').filter { it.isNotEmpty() }
    return listOf(label.ifEmpty { "/" } to base.ifEmpty { "/" }) + rest.runningReduce { a, b -> "$a/$b" }.mapIndexed { i, rel -> rest[i] to "$base/$rel" }
}

/** "1,234 of 2,742 objects · 12.3 MB", "40 of 120 files": what git counts in the current phase. */
internal fun cloneDetail(p: CloneProgress.Running): String? {
    val what = when {
        p.phase.endsWith("objects") -> "objects"
        p.phase.endsWith("deltas") -> "changes"
        p.phase.endsWith("files") -> "files"
        else -> "items"
    }
    val count = if (p.count != null && p.total != null) "%,d of %,d %s".format(p.count, p.total, what) else null
    return listOfNotNull(count, p.size).joinToString(" · ").ifEmpty { null }
}

/**
 * The clone in the slot, filling the screen instead of the picker: while it runs, its repo, folder and
 * progress with Cancel; when it failed, the error with Retry; when it landed while nobody was looking, Open.
 */
@Composable
private fun ColumnScope.CloneInSlot(job: CloneJob, onOpen: (workspace: String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val r = job.request
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caps(
            when (job.progress) { is CloneProgress.Running -> "CLONING"; is CloneProgress.Done -> "CLONED"; is CloneProgress.Failed -> "CLONE FAILED" },
            size = 12.sp, spacing = 0.4.sp,
        )
        Text(r.label, style = mockText(18.sp, FontWeight.SemiBold, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("into ${Workspaces.tilde(File(r.parent, r.name).path)}", style = mockText(13.sp, color = c.textSoft, mono = true))
        r.branch?.let { Text("branch $it", style = mockText(13.sp, color = c.textMuted, mono = true)) }
        if (job.running) Text(
            "One clone at a time. It keeps going if you leave this screen or the app; the notification shows its progress.",
            style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp), modifier = Modifier.padding(top = 10.dp),
        )
    }
    HorizontalDivider(thickness = 1.dp, color = c.border)
    Column(
        Modifier.fillMaxWidth().background(c.bar).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val p = job.progress) {
            is CloneProgress.Running -> {
                val overall = RepoClone.overallPercent(p)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.phase, style = mockText(12.sp, color = c.textSoft))
                    Text(overall?.let { "$it%" } ?: "", style = mockText(12.sp, FontWeight.SemiBold, c.text))
                }
                if (overall == null) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp), color = c.accent, trackColor = c.raised, strokeCap = StrokeCap.Round, gapSize = 0.dp,
                ) else LinearProgressIndicator(
                    progress = { overall / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp), color = c.accent, trackColor = c.raised, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                )
                listOfNotNull(cloneDetail(p), p.speed).joinToString(" · ").ifEmpty { null }?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
                OutlineButton("Cancel", { CloneManager.cancel(job.id) }, Modifier.fillMaxWidth())
            }
            is CloneProgress.Failed -> {
                Text("Clone failed: ${p.message}", style = mockText(12.sp, color = c.redSoft).copy(lineHeight = 17.sp))
                AccentButton("Retry", { CloneNotifier.clearResult(context, job.id); CloneManager.retry(context, job.id) })
                OutlineButton("Pick another repo", { CloneNotifier.clearResult(context, job.id); CloneManager.dismiss(job.id) }, Modifier.fillMaxWidth())
            }
            is CloneProgress.Done -> {
                Text("Cloned into ${Workspaces.tilde(p.dir.path)}.", style = mockText(12.sp, color = c.textSoft))
                p.workspace?.let { ws -> AccentButton("Open ${p.dir.name}", { onOpen(ws) }) }
                OutlineButton("Clone another", { CloneNotifier.clearResult(context, job.id); CloneManager.dismiss(job.id) }, Modifier.fillMaxWidth())
            }
        }
    }
}
