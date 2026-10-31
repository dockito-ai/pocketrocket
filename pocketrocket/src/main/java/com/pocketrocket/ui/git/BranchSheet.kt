package com.pocketrocket.ui.git

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.Branch
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.ui.chat.SessionActivity
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.sessions.displayPath
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import java.io.File

/**
 * The branch sheet (BranchesPRs.html §3): find a branch, local ones newest first, then the ones only on GitHub.
 * Tap to switch (or check out a remote one); long-press to copy, delete a merged one or start a session on it.
 * [prLabel] gives "#42 open" for a branch name when its PR is known.
 */
@Composable
fun BranchSheet(model: GitPanelModel, prLabel: (String) -> String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val root = model.repo?.root?.path ?: return
    var query by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var sessionNaming by remember { mutableStateOf(false) }
    var elsewhere by remember { mutableStateOf<Branch?>(null) }
    val list = model.branches
    val q = query.trim().lowercase()
    val local = list.local.filter { q.isEmpty() || q in it.name.lowercase() }
    val remote = list.remoteOnly.filter { q.isEmpty() || q in it.name.lowercase() }

    fun sessionOn(name: String) {
        onDismiss()
        model.worktreeFor(name) { dir -> GitScreens.startSession(context, dir, name) }
    }

    GitSheet(onDismiss) {
        MockTextField(query, { query = it }, placeholder = "Find a branch", background = c.background, height = 42.dp)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (local.isNotEmpty()) item(key = "h-local") { SectionLabel("On this phone") }
            items(local, key = { "l-${it.name}" }) { b ->
                val inOther = b.worktree != null && b.worktree != root
                val canDelete = !b.current && b.worktree == null && b.name in model.merged && b.name != model.defaultBranch
                BranchRow(
                    name = b.name,
                    sub = localSub(b, model, root, prLabel(b.name)),
                    current = b.current,
                    trailing = null,
                    onClick = {
                        when {
                            b.current -> onDismiss()
                            inOther -> elsewhere = b
                            else -> { onDismiss(); model.switchTo(b) }
                        }
                    },
                    menu = listOf(
                        "Copy name" to { copyText(context, b.name, "branch name") },
                        (if (canDelete) "Delete" else "Delete (not merged into ${model.defaultBranch ?: "main"})") to
                            (if (canDelete) ({ model.deleteBranch(b.name) }) else null),
                        "New session on this branch" to { sessionOn(b.name) },
                    ),
                )
            }
            if (remote.isNotEmpty()) item(key = "h-remote") { SectionLabel("On GitHub only") }
            items(remote, key = { "r-${it.name}" }) { b ->
                val sub = listOfNotNull(b.author.ifEmpty { null }, prLabel(b.shortName), relativeTime(b.time)).joinToString(" · ")
                BranchRow(
                    name = b.name,
                    sub = AnnotatedString(sub),
                    current = false,
                    trailing = "Check out",
                    onClick = { onDismiss(); model.switchTo(b) },
                    menu = listOf(
                        "Copy name" to { copyText(context, b.shortName, "branch name") },
                        "New session on this branch" to { sessionOn(b.name) },
                    ),
                )
            }
            if (local.isEmpty() && remote.isEmpty()) item(key = "none") {
                Text(if (q.isEmpty()) "No branches yet." else "No branch matches.", style = mockText(13.sp, color = c.textMuted), modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton("New branch", accent = false, modifier = Modifier.weight(1f)) { naming = true }
            SheetButton("New session on a branch", accent = true, modifier = Modifier.weight(1f)) { sessionNaming = true }
        }
    }

    if (naming) NameDialog(
        title = "New branch",
        note = model.status?.branch?.head?.let { "From $it, and switched to. Uncommitted changes come along." },
        placeholder = "feat/something",
        confirm = "Create",
        onDismiss = { naming = false },
        onConfirm = { naming = false; onDismiss(); model.newBranch(it) },
    )
    if (sessionNaming) NameDialog(
        title = "New session on a branch",
        note = "An existing branch, or a new one from ${model.status?.branch?.head ?: "HEAD"}. It opens in its own folder (a git worktree), so this one is left as it is.",
        placeholder = "feat/something",
        confirm = "Start",
        onDismiss = { sessionNaming = false },
        onConfirm = { sessionNaming = false; sessionOn(it) },
    )
    elsewhere?.let { b ->
        val folder = b.worktree.orEmpty()
        val session = SessionManager.sessions.value.firstOrNull { it.state.value.folder == folder }
        GitDialog(
            title = "In another folder",
            onDismiss = { elsewhere = null },
            confirm = if (session != null) "Open session" else "New session there",
            onConfirm = {
                elsewhere = null
                onDismiss()
                if (session != null) SessionActivity.open(context, session.id)
                else GitScreens.startSession(context, File(folder), b.name)
            },
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = JetBrainsMono)) { append(b.name) }
                    append(" is checked out in ")
                    withStyle(SpanStyle(fontFamily = JetBrainsMono)) { append(displayPath(folder)) }
                    session?.let { append(", used by “${it.state.value.title}”") }
                    append(". A branch can only be checked out in one folder at a time.")
                },
                style = mockText(13.5.sp, color = c.textSoft),
            )
        }
    }
}

/** "default · ↑2 · 20 min ago · #42 open", then where it is checked out when that is another folder (in blue). */
@Composable
private fun localSub(b: Branch, model: GitPanelModel, root: String, pr: String?): AnnotatedString {
    val c = PrTheme.colors
    val distance = when {
        b.upstream == null -> "not pushed"
        b.upstreamGone -> "gone from GitHub"
        b.ahead > 0 && b.behind > 0 -> "↑${b.ahead} ↓${b.behind}"
        b.ahead > 0 -> "↑${b.ahead}"
        b.behind > 0 -> "↓${b.behind} behind"
        else -> "up to date"
    }
    val parts = listOfNotNull("default".takeIf { b.name == model.defaultBranch }, distance, relativeTime(b.time), pr)
    val tree = b.worktree?.takeIf { it != root }
    return buildAnnotatedString {
        append(parts.joinToString(" · "))
        if (tree != null) {
            append(" · ")
            val used = model.sessionIn(tree)
            withStyle(SpanStyle(color = c.blue)) {
                append(if (used != null) "used by “$used” (worktree)" else "in ${File(tree).name} (worktree)")
            }
        }
    }
}

/** One branch (`.br`): a tick for the current one, mono name, a muted line under it; long-press for [menu]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BranchRow(
    name: String,
    sub: AnnotatedString,
    current: Boolean,
    trailing: String?,
    onClick: () -> Unit,
    menu: List<Pair<String, (() -> Unit)?>>,
) {
    val c = PrTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { open = true })) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (current) Text("✓", style = mockText(13.sp, color = c.green), modifier = Modifier.width(12.dp))
                else Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = mockText(13.5.sp, color = if (current) c.green else c.text, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = mockText(11.5.sp, color = c.textMuted), modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (trailing != null) Text(trailing, style = mockText(12.sp, color = c.textMuted))
            }
            HorizontalDivider(color = c.raised)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (action != null) c.text else c.textMuted) },
                    enabled = action != null,
                    onClick = { open = false; action?.invoke() },
                )
            }
        }
    }
}

/** The sheet's 44 dp buttons (`.acts button`): outlined, or orange when [accent]. */
@Composable
fun SheetButton(text: String, accent: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val base = modifier.height(44.dp).clip(shape)
    val styled = if (accent) base.background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f)) else base.border(1.dp, c.border, shape)
    Box(styled.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = mockText(13.5.sp, FontWeight.SemiBold, if (accent) c.onAccent else if (enabled) c.text else c.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "You have 3 changed files on feat/x." for [SwitchDialog]. */
private fun changedLine(changed: Int, from: String?): AnnotatedString = buildAnnotatedString {
    append(if (changed == 1) "You have 1 changed file" else "You have $changed changed files")
    if (from != null) {
        append(" on ")
        withStyle(SpanStyle(fontFamily = JetBrainsMono)) { append(from) }
    }
    append(".")
}

/**
 * Switching with uncommitted changes, or while a session works in the folder (BranchesPRs.html §3): keep the changes
 * on this branch (stashed, put back when switching back) or bring them along.
 */
@Composable
fun SwitchDialog(model: GitPanelModel) {
    val request = model.switchRequest ?: return
    val c = PrTheme.colors
    var keep by remember(request) { mutableStateOf(request.from != null) }
    GitDialog(
        title = "Switch to ${request.target}?",
        onDismiss = { model.switchRequest = null },
        confirm = "Switch",
        onConfirm = { model.confirmSwitch(keep) },
    ) {
        if (request.changed > 0) Text(changedLine(request.changed, request.from), style = mockText(13.5.sp, color = c.textSoft))
        request.workingSession?.let {
            WarnBox("“$it” is working in this folder right now. Switching changes the files under it. Stop it first, or start a new session on a branch instead.")
        }
        if (request.changed > 0) {
            OptionBox("Keep them on this branch", "Stashed now, put back when you switch back here", picked = keep, enabled = request.from != null) { keep = true }
            OptionBox("Bring them to ${request.target}", "Only if they don't clash", picked = !keep) { keep = false }
        }
    }
}
