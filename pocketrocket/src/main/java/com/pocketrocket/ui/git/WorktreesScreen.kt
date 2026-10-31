package com.pocketrocket.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.sessions.displayPath
import com.pocketrocket.ui.theme.PrTheme

/** "Worktrees · 3" under the branch card, when the repo has any besides the main checkout. Opens [WorktreesScreen]. */
@Composable
fun WorktreesCard(model: GitPanelModel, onOpen: (GitRoute) -> Unit) {
    val extra = model.worktrees.count { !it.tree.main }
    if (extra == 0) return
    val p = PrTheme.colors
    val tidy = model.worktrees.count { it.tidy }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp).fillMaxWidth().clip(shape)
            .background(p.card).border(1.dp, p.border, shape)
            .clickable { onOpen(GitRoute.Worktrees) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⑂", style = mockText(14.sp, color = p.teal))
        Text("Worktrees · $extra", style = mockText(14.sp, color = p.text), modifier = Modifier.weight(1f))
        if (tidy > 0) Text("$tidy to clean up", style = mockText(12.sp, color = p.amber))
        Text("›", style = mockText(14.sp, color = p.textMuted))
    }
}

/**
 * Every worktree of the repo, the main checkout first: folder, branch, uncommitted files, merged or not, last commit,
 * sessions open in it and an agent's lock. Remove asks first and says what would be lost; Clean up removes every
 * merged, clean, unused one with its branch; Prune forgets folders that were deleted by hand.
 */
@Composable
fun WorktreesScreen(model: GitPanelModel, onBack: () -> Unit) {
    val p = PrTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<WorktreeRow?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    LaunchedEffect(model.message) { model.message?.let { snackbar.showSnackbar(it); model.message = null } }
    val rows = model.worktrees
    val tidy = rows.filter { it.tidy }
    val gone = rows.any { it.tree.prunable != null }

    GitScaffold(
        title = "Worktrees",
        subtitle = { MonoSubtitle(model.repo?.name ?: "") },
        onBack = onBack,
        actions = { IconButton(onClick = model::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = p.textMuted) } },
        snackbar = snackbar,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (model.loading || model.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.background)
            LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tidy.isNotEmpty() || gone) {
                    item(key = "actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tidy.isNotEmpty()) ActionChip("Clean up ${tidy.size}", p.accent) { cleaning = true }
                            if (gone) ActionChip("Prune missing", p.textSoft) { model.pruneWorktrees() }
                        }
                    }
                }
                items(rows, key = { it.tree.path }) { row -> WorktreeCard(row, enabled = model.busy == null) { removing = row } }
            }
        }
    }

    removing?.let { row -> RemoveDialog(row, onDismiss = { removing = null }) { deleteBranch -> removing = null; model.removeWorktree(row, deleteBranch) } }
    if (cleaning) {
        GitDialog(
            "Clean up ${tidy.size} worktree${if (tidy.size == 1) "" else "s"}?",
            onDismiss = { cleaning = false },
            confirm = "Clean up",
            onConfirm = { cleaning = false; model.cleanUpWorktrees() },
        ) {
            Text("Each is merged into ${model.defaultBranch ?: "the default branch"}, has nothing uncommitted and no session. Their folders and branches go:", style = mockText(13.5.sp, color = p.textSoft))
            tidy.forEach { Text("${it.name}  ·  ${it.tree.branch}", style = mockText(12.5.sp, color = p.text, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun WorktreeCard(row: WorktreeRow, enabled: Boolean, onRemove: () -> Unit) {
    val p = PrTheme.colors
    val tree = row.tree
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().background(p.card, shape).border(1.dp, if (row.here) p.accentBorder else p.border, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.name, style = mockText(14.sp, FontWeight.SemiBold, p.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                tree.main -> Pill("main checkout", p.textMuted)
                row.here -> Pill("this one", p.accent)
            }
        }
        Text(tree.branch ?: "detached at ${tree.head.take(7)}", style = mockText(12.5.sp, color = p.green, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(displayPath(tree.path), style = mockText(11.5.sp, color = p.textMuted, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            facts(row).forEach { (text, color) -> Pill(text, color) }
        }
        if (row.sessions.isNotEmpty()) {
            Text(
                (if (row.working) "Claude is working here: " else "Session open here: ") + row.sessions.joinToString(),
                style = mockText(12.sp, color = if (row.working) p.amber else p.textSoft),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.removable) {
            Text(
                "Remove",
                style = mockText(13.sp, FontWeight.Medium, if (enabled) p.redSoft else p.textMuted),
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, p.redBorder, RoundedCornerShape(9.dp))
                    .clickable(enabled = enabled, onClick = onRemove).padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** The short facts under a worktree: "clean" or "3 changed", "merged", "locked by Claude", "folder gone", "2d". */
@Composable
private fun facts(row: WorktreeRow): List<Pair<String, androidx.compose.ui.graphics.Color>> {
    val p = PrTheme.colors
    val tree = row.tree
    return listOfNotNull(
        tree.prunable?.let { "folder gone" to p.red },
        row.changed?.let { if (it == 0) "clean" to p.textMuted else "$it changed" to p.amber },
        if (row.merged && !tree.main) "merged" to p.green else null,
        tree.locked?.let { (if (it.startsWith("claude agent")) "locked by Claude" else "locked") to p.lilac },
        row.lastCommit?.let { shortAge(it) to p.textMuted },
    )
}

/** Remove one worktree: what goes with it, warnings for uncommitted files, an open session or an agent's lock. */
@Composable
private fun RemoveDialog(row: WorktreeRow, onDismiss: () -> Unit, onConfirm: (deleteBranch: Boolean) -> Unit) {
    val p = PrTheme.colors
    val branch = row.tree.branch
    var deleteBranch by remember { mutableStateOf(row.merged && branch != null) }
    GitDialog("Remove ${row.name}?", onDismiss, confirm = "Remove", onConfirm = { onConfirm(deleteBranch && row.merged) }) {
        Text("The folder ${displayPath(row.tree.path)} is deleted.", style = mockText(13.5.sp, color = p.textSoft))
        row.changed?.takeIf { it > 0 }?.let { WarnBox("$it uncommitted file${if (it == 1) "" else "s"} will be lost.") }
        if (row.sessions.isNotEmpty()) WarnBox((if (row.working) "Claude is working here" else "A session is open here") + " (${row.sessions.joinToString()}). It loses its folder.")
        row.tree.locked?.let { WarnBox("Locked${if (it.isNotEmpty()) ": $it" else ""}. A Claude agent may still be using it.") }
        if (branch != null) {
            if (row.merged) OptionBox("Also delete $branch", "It's merged, so nothing is lost.", picked = deleteBranch) { deleteBranch = !deleteBranch }
            else Text("$branch isn't merged, so it stays.", style = mockText(12.5.sp, color = p.textMuted))
        }
    }
}

@Composable
private fun ActionChip(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val p = PrTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        style = mockText(13.sp, FontWeight.Medium, color),
        modifier = Modifier.clip(shape).border(1.dp, p.border, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
