package com.pocketrocket.ui.git

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.CheckState
import com.pocketrocket.git.MergeMethod
import com.pocketrocket.git.MergeMethods
import com.pocketrocket.git.PrCheck
import com.pocketrocket.git.PrDetail
import com.pocketrocket.ui.chat.MarkdownText
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

private enum class PrPage { Overview, Files, Commits }

/**
 * One pull request (BranchesPRs.html §4): Overview (state, description, checks, reviews), Files in the diff viewer,
 * Commits; at the bottom Check out, Review with Claude and Merge; ⋮ has Open on GitHub, Approve, Request changes,
 * Close and Mark ready.
 */
@Composable
fun PrScreen(model: GitPanelModel, number: Int, onBack: () -> Unit, onOpen: (GitRoute) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val prs = model.prs ?: return
    val loaded = loadOrError(number, model.prVersion) { prs.detail(number) }
    var tab by remember { mutableStateOf(PrPage.Overview) }
    var menu by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    // true: approving, false: requesting changes, null: neither.
    var reviewing by remember { mutableStateOf<Boolean?>(null) }
    val detail = loaded.value
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(model.message) { model.message?.let { snackbar.showSnackbar(it); model.message = null } }

    fun openOnGitHub() {
        detail?.url?.takeIf { it.isNotEmpty() }?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
    }

    GitScaffold(
        title = detail?.pr?.title ?: "#$number",
        titleStyle = mockText(16.sp, FontWeight.Bold),
        subtitle = { MonoSubtitle(detail?.pr?.let { "#$number · ${it.headRef} → ${it.baseRef}" } ?: "#$number") },
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            Box {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Text("⋮", style = mockText(22.sp, color = c.textSoft))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open on GitHub") }, onClick = { menu = false; openOnGitHub() })
                    // GitHub refuses a review of your own pull request.
                    if (detail?.pr?.let { it.isOpen && !it.byMe(model.prs?.me) } == true) {
                        DropdownMenuItem(text = { Text("Approve") }, onClick = { menu = false; reviewing = true })
                        DropdownMenuItem(text = { Text("Request changes") }, onClick = { menu = false; reviewing = false })
                    }
                    if (detail?.pr?.isOpen == true) DropdownMenuItem(text = { Text("Close") }, onClick = { menu = false; closing = true })
                    if (detail?.pr?.let { it.isOpen && it.isDraft } == true) DropdownMenuItem(text = { Text("Mark ready") }, onClick = { menu = false; model.markReady(number) })
                }
            }
        },
        bottomBar = {
            if (detail != null) PrFooter(model, detail, onMerge = { merging = true })
        },
    ) { padding ->
        if (detail == null) { LoadingOrError(loaded.error, Modifier.padding(padding)); return@GitScaffold }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Tabs(
                listOf(
                    TabItem("Overview", tab == PrPage.Overview) { tab = PrPage.Overview },
                    TabItem("Files · ${detail.changedFiles}", tab == PrPage.Files) { tab = PrPage.Files },
                    TabItem("Commits · ${detail.commits.size}", tab == PrPage.Commits) { tab = PrPage.Commits },
                ),
                Modifier.padding(top = 6.dp),
            )
            when (tab) {
                PrPage.Overview -> Overview(detail, model.prs?.me, onComments = { openOnGitHub() })
                PrPage.Files -> PrFiles(model, number, onOpen)
                PrPage.Commits -> CommitList(detail.commits, onOpen)
            }
        }
    }

    if (merging && detail != null) MergeDialog(model, number, onDismiss = { merging = false })
    if (closing) GitDialog(
        title = "Close #$number?",
        onDismiss = { closing = false },
        confirm = "Close",
        onConfirm = { closing = false; model.closePr(number) },
    ) { Text("The pull request is closed without merging. It can be reopened on GitHub.", style = mockText(13.5.sp, color = c.textSoft)) }
    reviewing?.let { approve -> ReviewDialog(number, approve, onDismiss = { reviewing = null }) { body -> reviewing = null; model.reviewPr(number, approve, body) } }
    SwitchDialog(model)
}

/** Approve with an optional comment, or request changes with the comment GitHub requires. */
@Composable
private fun ReviewDialog(number: Int, approve: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val c = PrTheme.colors
    var body by remember { mutableStateOf("") }
    GitDialog(
        title = if (approve) "Approve #$number?" else "Request changes on #$number?",
        onDismiss = onDismiss,
        confirm = if (approve) "Approve" else "Request changes",
        onConfirm = { onSubmit(body) },
        confirmEnabled = approve || body.isNotBlank(),
    ) {
        Text(
            if (approve) "Your approval is posted on GitHub as this repo's account." else "Say what needs to change; it is posted on GitHub as this repo's account.",
            style = mockText(13.5.sp, color = c.textSoft),
        )
        OutlinedTextField(body, { body = it }, placeholder = { Text(if (approve) "Comment (optional)" else "What needs to change") }, minLines = 3, modifier = Modifier.fillMaxWidth())
    }
}

/** State, author, age and size, the description, the checks and the reviews. */
@Composable
private fun Overview(detail: PrDetail, me: String?, onComments: () -> Unit) {
    val c = PrTheme.colors
    val pr = detail.pr
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                when {
                    pr.state == "MERGED" -> Pill("Merged", c.purple)
                    pr.state == "CLOSED" -> Pill("Closed", c.redSoft)
                    pr.isDraft -> Pill("Draft", c.textMuted)
                    else -> Pill("Open", c.green)
                }
                Text(
                    "${if (pr.byMe(me)) "you" else pr.author} · ${relativeTime(detail.created)} · +${detail.additions} −${detail.deletions}",
                    style = mockText(12.sp, color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            val body = forReading(detail.body)
            if (body.isEmpty()) Text("No description.", style = mockText(13.sp, color = c.textMuted))
            else MarkdownText(body, color = c.textSoft)
        }
        if (pr.checks.isNotEmpty()) {
            item { SectionLabel("Checks") }
            items(pr.checks) { CheckRow(it) }
        }
        item { SectionLabel("Reviews") }
        if (pr.requested(me)) item { InfoRow("○", c.amber, "You were asked to review") }
        items(pr.reviewRequests.filterNot { it.equals(me, ignoreCase = true) }) { InfoRow("○", c.textMuted, "Waiting on $it") }
        items(pr.reviews) { r ->
            when (r.state) {
                "APPROVED" -> InfoRow("✓", c.green, "${r.author} approved")
                "CHANGES_REQUESTED" -> InfoRow("✗", c.redSoft, "${r.author} asked for changes")
                else -> InfoRow("💬", c.textMuted, "${r.author} commented")
            }
        }
        item {
            InfoRow("💬", c.textMuted, if (detail.comments == 1) "1 comment" else "${detail.comments} comments", trailing = "›", onClick = onComments)
        }
    }
}

/** One check (`.check`): its state, name and how long it took (or "running"). */
@Composable
private fun CheckRow(check: PrCheck) {
    val c = PrTheme.colors
    val (sign, color) = when (check.state) {
        CheckState.Passing -> "✓" to c.green
        CheckState.Running -> "●" to c.amber
        CheckState.Failing -> "✗" to c.redSoft
        CheckState.Skipped -> "–" to c.textMuted
    }
    val time = when {
        check.state == CheckState.Running -> "running"
        check.state == CheckState.Skipped -> "skipped"
        check.seconds != null -> check.seconds.let { if (it >= 60) "${it / 60}m ${it % 60}s" else "${it}s" }
        else -> null
    }
    InfoRow(sign, color, check.name, trailing = time)
}

/** A row of the overview's lists: a coloured sign, the text, an optional muted note on the right. */
@Composable
private fun InfoRow(sign: String, color: Color, text: String, trailing: String? = null, onClick: (() -> Unit)? = null) {
    val c = PrTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 2.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(sign, style = mockText(13.sp, color = color), modifier = Modifier.width(18.dp))
            Text(text, style = mockText(13.sp, color = c.text), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (trailing != null) Text(trailing, style = mockText(13.sp, color = c.textMuted))
        }
        HorizontalDivider(color = c.raised)
    }
}

/** The PR's files from `gh pr diff`; each opens in the diff viewer. */
@Composable
private fun PrFiles(model: GitPanelModel, number: Int, onOpen: (GitRoute) -> Unit) {
    val files = loadOrError(number, model.prVersion) { model.prFiles(number) }
    val list = files.value
    if (list == null) { LoadingOrError(files.error); return }
    if (list.isEmpty()) { LoadingOrError("No file changes."); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.change.path }) { f ->
            ChangeRow(f.change, busy = false, onClick = { onOpen(GitRoute.PrDiff(number, f.change.path)) }, onToggle = null)
        }
    }
}

/** Check out, Review with Claude, Merge ▾ (`.foot`). */
@Composable
private fun PrFooter(model: GitPanelModel, detail: PrDetail, onMerge: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val pr = detail.pr
    val idle = model.busy == null
    val here = model.status?.branch?.head == pr.headRef
    Column(Modifier.background(c.background).navigationBarsPadding()) {
        HorizontalDivider(color = c.border)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FootButton(if (here) "Checked out" else "Check out", accent = false, enabled = idle && pr.isOpen && !here, modifier = Modifier.weight(1f)) {
                model.checkoutPr(pr.number)
            }
            FootButton(model.busy?.takeIf { it.startsWith("Preparing") } ?: "Review with Claude", accent = false, enabled = idle && pr.isOpen, modifier = Modifier.weight(1f)) {
                model.reviewInWorktree(pr.number) { dir ->
                    GitScreens.startSession(context, dir, "Review #${pr.number}", reviewPrompt(pr.number, pr.title))
                }
            }
            FootButton(model.busy?.takeIf { it.startsWith("Merging") } ?: "Merge ▾", accent = true, enabled = idle && pr.isOpen && !pr.isDraft, modifier = Modifier.weight(1f), onClick = onMerge)
        }
    }
}

/** The first message of a "Review with Claude" session. */
private fun reviewPrompt(number: Int, title: String) =
    "Please review pull request #$number (\"$title\"). Its branch is checked out in this folder. " +
        "Read it with `gh pr view $number` and `gh pr diff $number`, then tell me what you find: bugs, risks and " +
        "anything unclear, most important first, with file and line. Don't push, comment or approve on GitHub."

/** The footer's 46 dp buttons (`.foot button`), orange when [accent]. */
@Composable
private fun FootButton(text: String, accent: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val base = modifier.height(46.dp).clip(shape)
    val styled = if (accent) base.background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f)) else base.border(1.dp, c.border, shape)
    Box(styled.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = mockText(14.sp, FontWeight.SemiBold, if (accent) c.onAccent else if (enabled) c.text else c.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Merge with one of the repo's allowed methods, optionally deleting the branch after. */
@Composable
private fun MergeDialog(model: GitPanelModel, number: Int, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    val prs = model.prs ?: return
    val methods = loadOrError(number) { prs.mergeMethods() }
    val allowed = methods.value?.let { allowedMethods(it) }
    var picked by remember(allowed) { mutableStateOf(allowed?.firstOrNull()) }
    var deleteBranch by remember { mutableStateOf(true) }
    GitDialog(
        title = "Merge #$number?",
        onDismiss = onDismiss,
        confirm = "Merge",
        confirmEnabled = picked != null,
        onConfirm = { picked?.let { onDismiss(); model.mergePr(number, it, deleteBranch) } },
    ) {
        when {
            allowed == null -> Text(methods.error ?: "Checking the repo's merge methods…", style = mockText(13.5.sp, color = c.textSoft))
            allowed.isEmpty() -> Text("This repo allows no merge method.", style = mockText(13.5.sp, color = c.textSoft))
            else -> allowed.forEach { m ->
                val (title, note) = when (m) {
                    MergeMethod.Squash -> "Squash and merge" to "One commit on the base branch"
                    MergeMethod.Merge -> "Merge commit" to "Keeps every commit, plus a merge commit"
                    MergeMethod.Rebase -> "Rebase and merge" to "Every commit replayed on the base branch"
                }
                OptionBox(title, note, picked = picked == m) { picked = m }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Delete branch after", style = mockText(14.sp, color = c.text), modifier = Modifier.weight(1f))
            Switch(
                checked = deleteBranch,
                onCheckedChange = { deleteBranch = it },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
    }
}

private fun allowedMethods(m: MergeMethods) =
    listOfNotNull(MergeMethod.Squash.takeIf { m.squash }, MergeMethod.Merge.takeIf { m.merge }, MergeMethod.Rebase.takeIf { m.rebase })
