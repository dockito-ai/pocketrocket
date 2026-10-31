package com.pocketrocket.ui.git

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.pocketrocket.ui.theme.JetBrainsMono
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.pocketrocket.ui.components.mockText
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.ChangeArea
import com.pocketrocket.git.ChecksSummary
import com.pocketrocket.git.Commit
import com.pocketrocket.git.DiffSource
import com.pocketrocket.git.FileChange
import com.pocketrocket.ui.sessions.displayPath

/** The Git panel: branch card with push and pull, then Changes / Graph / PRs / Files, and Commit at the bottom. */
@Composable
fun GitPanelScreen(model: GitPanelModel, onBack: () -> Unit, onOpen: (GitRoute) -> Unit) {
    val repo = model.repo
    val status = model.status
    val snackbar = remember { SnackbarHostState() }
    var composing by remember { mutableStateOf(false) }
    val staged = status?.inArea(ChangeArea.Staged).orEmpty()

    LaunchedEffect(model.message) {
        model.message?.let { snackbar.showSnackbar(it); model.message = null }
    }

    GitScaffold(
        title = repo?.name ?: "Git",
        subtitle = repo?.let { { Text(displayPath(it.root.path), style = mockText(12.sp, color = PrTheme.colors.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        onBack = onBack,
        actions = { IconButton(onClick = { model.refresh(); model.refreshPrs() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = PrTheme.colors.textMuted) } },
        snackbar = snackbar,
        bottomBar = {
            if (status != null) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = PrTheme.colors.border)
                    OutlinedButton(
                        onClick = { composing = true },
                        enabled = staged.isNotEmpty() && model.busy == null,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(48.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrTheme.colors.border),
                    ) { Text(if (staged.isEmpty()) "Stage files to commit" else "Commit ${staged.size} staged…", style = mockText(14.sp, color = if (staged.isNotEmpty() && model.busy == null) PrTheme.colors.text else PrTheme.colors.textMuted)) }
                }
            }
        },
    ) { padding ->
        if (status == null || repo == null) {
            LoadingOrError(model.error, Modifier.padding(padding))
            return@GitScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (model.loading || model.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = PrTheme.colors.accent, trackColor = PrTheme.colors.background)
            else Spacer(Modifier.height(2.dp))
            BranchCard(model, onOpen)
            Tabs(
                listOf(
                    TabItem("Changes · ${status.changes.distinctBy { it.path }.size}", model.tab == PanelTab.Changes) { model.tab = PanelTab.Changes },
                    TabItem("Graph", model.tab == PanelTab.Graph) { model.tab = PanelTab.Graph },
                    TabItem(model.openPrs?.let { "PRs · ${it.size}" } ?: "PRs", model.tab == PanelTab.Prs) { model.tab = PanelTab.Prs },
                    TabItem("Files", false) { onOpen(GitRoute.Browse("")) },
                ),
            )
            when (model.tab) {
                PanelTab.Changes -> ChangeList(model, onOpen)
                PanelTab.Graph -> GraphTab(model, prFor = { model.prFor(it)?.number }, onOpen = onOpen, onOpenPr = { onOpen(GitRoute.Pr(it)) })
                PanelTab.Prs -> PrTab(model, onOpen)
            }
        }
    }

    if (composing) CommitDialog(onDismiss = { composing = false }, onCommit = { composing = false; model.commit(it) })
    if (model.showBranches && model.repo != null) BranchSheet(model, prLabel = model::prLabel, onDismiss = { model.showBranches = false })
    SwitchDialog(model)
}

/**
 * The branch card (BranchesPRs.html §2): branch name with ▾ (opens the branches), distance to upstream, Pull and
 * Push, and under them the branch's open PR when it has one (tap opens it).
 */
@Composable
private fun BranchCard(model: GitPanelModel, onOpen: (GitRoute) -> Unit) {
    val branch = model.status?.branch ?: return
    val c = gitColors
    val p = PrTheme.colors
    Column(
        Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp).fillMaxWidth()
            .background(p.card, RoundedCornerShape(12.dp))
            .border(1.dp, p.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BranchIcon(c.added)
            Text(
                (branch.head ?: "detached at ${branch.oid?.take(7)}") + " ▾",
                style = mockText(14.sp, color = p.text, mono = true),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { model.showBranches = true }.padding(vertical = 6.dp),
            )
            Text(
                if (branch.upstream != null) "↑${branch.ahead} ↓${branch.behind}" else "no upstream",
                style = mockText(12.sp, color = if (branch.upstream != null) p.textSoft else p.textMuted),
                maxLines = 1,
            )
            val idle = model.busy == null
            SmallButton("Pull", enabled = idle && branch.upstream != null, fill = null, onClick = model::pull)
            SmallButton(model.busy?.takeIf { it.isNotEmpty() } ?: "Push", enabled = idle && branch.head != null, fill = p.accent, onClick = model::push)
        }
        model.branchPr?.takeIf { it.headRef == branch.head }?.let { pr ->
            Column {
                HorizontalDivider(color = p.raised, modifier = Modifier.padding(bottom = 10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(GitRoute.Pr(pr.number)) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("⇄", style = mockText(12.5.sp, color = p.purple))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("#${pr.number}") }
                            append(" ")
                            append(pr.title)
                        },
                        style = mockText(12.5.sp, color = p.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val s = ChecksSummary.of(pr.checks)
                    when {
                        s.total == 0 -> Unit
                        s.failing > 0 -> Text("✗ checks", style = mockText(12.5.sp, color = p.redSoft))
                        s.running > 0 -> Text("● checks", style = mockText(12.5.sp, color = p.amber))
                        else -> Text("✓ checks", style = mockText(12.5.sp, color = p.green))
                    }
                    Text("›", style = mockText(12.5.sp, color = p.textMuted))
                }
            }
        }
    }
}

/** The branch card's 34 dp buttons, radius 9: orange Push, outlined Pull. */
@Composable
private fun SmallButton(text: String, enabled: Boolean, fill: androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
    val p = PrTheme.colors
    val shape = RoundedCornerShape(9.dp)
    val base = Modifier.height(34.dp).clip(shape)
    val m = if (fill != null) base.background(if (enabled) fill else fill.copy(alpha = 0.4f)) else base.border(1.dp, p.border, shape)
    Box(m.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = mockText(13.sp, FontWeight.SemiBold, if (fill != null) p.onAccent else if (enabled) p.text else p.textMuted),
            maxLines = 1,
        )
    }
}

/** One tab of [Tabs]. */
class TabItem(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** The segmented tabs of the git screens (`.tabs`): the selected one filled. */
@Composable
fun Tabs(tabs: List<TabItem>, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 18.dp).fillMaxWidth()
            .background(PrTheme.colors.card, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { Tab(it.label, it.selected, it.onClick) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = PrTheme.colors
    Text(
        label,
        fontSize = 12.5.sp,
        maxLines = 1,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) scheme.text else scheme.textMuted,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
            .background(if (selected) scheme.border else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun ChangeList(model: GitPanelModel, onOpen: (GitRoute) -> Unit) {
    val status = model.status ?: return
    val groups = listOf(
        ChangeArea.Conflicted to "Conflicts",
        ChangeArea.Staged to "Staged",
        ChangeArea.Unstaged to "Changes",
        ChangeArea.Untracked to "Untracked",
    ).map { (area, label) -> Triple(area, label, status.inArea(area)) }.filter { it.third.isNotEmpty() }

    if (groups.isEmpty()) {
        Text(
            "Nothing to commit, working tree clean.",
            style = mockText(13.sp),
            color = PrTheme.colors.textMuted,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (area, label, changes) ->
            item(key = "h-$area") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$label · ${changes.size}".uppercase(), style = mockText(11.sp, FontWeight.SemiBold, PrTheme.colors.textMuted, letterSpacing = 0.5.sp), modifier = Modifier.weight(1f))
                    if (area == ChangeArea.Unstaged || area == ChangeArea.Untracked) {
                        TextButton(onClick = model::stageAll, enabled = model.busy == null) { Text("Stage all", style = mockText(13.sp, FontWeight.SemiBold, PrTheme.colors.accent)) }
                    }
                }
            }
            items(changes, key = { "$area-${it.path}" }) { change ->
                ChangeRow(
                    change,
                    busy = model.busy != null,
                    onClick = { onOpen(GitRoute.Diff(diffSource(change), change.path)) },
                    onToggle = { model.toggleStage(change) },
                )
            }
        }
    }
}

private fun diffSource(change: FileChange): DiffSource = when (change.area) {
    ChangeArea.Staged -> DiffSource.Index(change.path)
    ChangeArea.Untracked -> DiffSource.Untracked(change.path)
    else -> DiffSource.WorkingTree(change.path)
}

@Composable
internal fun ChangeRow(change: FileChange, busy: Boolean, onClick: () -> Unit, onToggle: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(PrTheme.colors.card)
            .border(1.dp, PrTheme.colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onToggle != null) 2.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KindBadge(change.kind)
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(change.path.substringAfterLast('/'), style = CodeStyle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val dir = change.path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) Text(dir, style = CodeStyle, fontSize = 11.sp, color = PrTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LineCounts(change.added, change.deleted)
        if (onToggle != null && change.area != ChangeArea.Conflicted) {
            val staged = change.area == ChangeArea.Staged
            IconButton(onClick = onToggle, enabled = !busy) {
                if (staged) Text("−", fontSize = 22.sp, color = PrTheme.colors.textMuted)
                else Icon(Icons.Filled.Add, contentDescription = "Stage", tint = PrTheme.colors.accent)
            }
        }
    }
}

@Composable
fun CommitList(commits: List<Commit>, onOpen: (GitRoute) -> Unit) {
    if (commits.isEmpty()) {
        Text("No commits yet.", style = mockText(13.sp), color = PrTheme.colors.textMuted, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        items(commits, key = { it.sha }) { commit ->
            CommitRow(commit, Modifier.clickable { onOpen(GitRoute.CommitDetail(commit)) })
            HorizontalDivider(color = PrTheme.colors.raised)
        }
    }
}

@Composable
fun CommitRow(commit: Commit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(commit.shortSha, style = CodeStyle, color = PrTheme.colors.accent, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(commit.subject, style = mockText(14.sp, color = PrTheme.colors.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(commit.author, relativeTime(commit.time), "not pushed".takeIf { !commit.pushed }).joinToString(" · ")
            Text(meta, style = mockText(12.sp, color = PrTheme.colors.textMuted))
        }
    }
}

/** Files of one commit; each opens its diff. */
@Composable
fun CommitScreen(model: GitPanelModel, commit: Commit, onBack: () -> Unit, onOpen: (GitRoute) -> Unit) {
    val repo = model.repo ?: return
    val files = loadOrError(commit.sha) { repo.commitFiles(commit.sha) to repo.commitMessage(commit.sha) }
    GitScaffold(title = commit.subject, titleStyle = mockText(16.sp, FontWeight.SemiBold), subtitle = { MonoSubtitle("${commit.shortSha} · ${commit.author} · ${relativeTime(commit.time)}") }, onBack = onBack) { padding ->
        val loaded = files.value
        if (loaded == null) { LoadingOrError(files.error, Modifier.padding(padding)); return@GitScaffold }
        val (changes, message) = loaded
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(commit.subject, style = PrType.body.copy(fontWeight = FontWeight.SemiBold)) }
            val body = message.substringAfter('\n', "").trim()
            if (body.isNotEmpty()) item { Text(body, style = PrType.body, color = PrTheme.colors.textMuted, modifier = Modifier.padding(bottom = 8.dp)) }
            item {
                Text(if (changes.size == 1) "1 file" else "${changes.size} files", style = PrType.small, color = PrTheme.colors.textMuted, modifier = Modifier.padding(vertical = 4.dp))
            }
            items(changes, key = { it.path }) { change ->
                ChangeRow(change, busy = false, onClick = { onOpen(GitRoute.Diff(DiffSource.InCommit(commit.sha, change.path), change.path)) }, onToggle = null)
            }
        }
    }
}

@Composable
private fun CommitDialog(onDismiss: () -> Unit, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit") },
        text = {
            OutlinedTextField(text, { text = it }, placeholder = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onCommit(text.trim()) }, enabled = text.isNotBlank()) { Text("Commit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
