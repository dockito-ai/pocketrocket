package com.pocketrocket.ui.sessions

import com.pocketrocket.ui.theme.PrTheme
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.pocketrocket.notifications.NotificationPermission
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.sessions.ModelCatalog
import com.pocketrocket.sessions.PermissionMode
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.Attachments
import com.pocketrocket.sessions.history.EarlierConversation
import com.pocketrocket.sessions.history.EarlierConversations
import com.pocketrocket.ui.chat.AttachmentPicker
import com.pocketrocket.ui.chat.AttachmentTray
import com.pocketrocket.ui.chat.PlusButton
import com.pocketrocket.ui.chat.SessionActivity
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.ChoiceCard
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.TwoColumns
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.workspaces.FolderPrompt
import com.pocketrocket.ui.workspaces.AccountDropdown
import com.pocketrocket.ui.workspaces.claudeOptions
import com.pocketrocket.workspaces.WorkspaceRepos
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * New session (mockup NewSession): start fresh in a workspace, its root or one of its repos, with the
 * workspace's Claude account (changeable for this session) and an optional first message with attachments; or resume
 * an earlier Claude Code conversation from that workspace through SessionManager's `--resume` path.
 * [folder] preselects a workspace or a folder in one; blank starts from the first workspace.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(folder: String, resume: Boolean, onClose: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val workspaces by Workspaces.state.collectAsState()
    val claude by ClaudeAccounts.state.collectAsState()
    val sessions by SessionManager.sessions.collectAsState()

    val initialRoot = Workspaces.containing(folder)?.path ?: folder.ifBlank { workspaces.firstOrNull()?.path ?: AccountPaths.home.path }
    var root by rememberSaveable { mutableStateOf(initialRoot) }
    var where by rememberSaveable { mutableStateOf(folder.ifBlank { initialRoot }) }
    var tabResume by rememberSaveable { mutableStateOf(resume) }
    var pickedAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val models = remember { ModelCatalog.options(context) }
    var model by rememberSaveable { mutableStateOf("default") }
    var mode by rememberSaveable { mutableStateOf(PermissionMode.Auto) }
    // Files attached here wait in the folder of the session they will start, which is gone if it never does.
    val sessionId = rememberSaveable { Attachments.newSessionId() }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var attaching by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var attachHint by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!started) Attachments.dir(sessionId).deleteRecursively() }
    }
    var choosingWorkspace by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val ws = workspaces.firstOrNull { it.path == root }
    val repoDirs by produceState(emptyList<File>(), root) { value = withContext(Dispatchers.IO) { WorkspaceRepos.find(File(root)).filter { it.path != root } } }
    val history by produceState<List<EarlierConversation>?>(null, root, sessions.size) {
        value = withContext(Dispatchers.IO) {
            EarlierConversations.list(root, ClaudeAccounts.current.accounts, sessions.map { it.state.value.conversationId }.toSet())
        }
    }
    val folderAccount = remember(where, claude) { AccountWiring.claudeFor(where) }
    val account = pickedAccount?.let(ClaudeAccounts::get) ?: folderAccount

    fun rel(path: String) = if (path == root) "workspace root" else path.removePrefix("$root/")

    /** Asks about notifications once, the first time a session starts while they are off. */
    fun go(action: () -> Unit) { if (NotificationPermission.shouldAsk(context)) pending = action else action() }

    fun startFresh() = go {
        val profile = if (account.id == folderAccount.id) null else account.profile
        val session = SessionManager.startClaude(context, File(where), profile, model, mode, sessionId)
        started = true
        session.rename(name)
        session.send(message, attachments.toList())
        SessionActivity.open(context, session.id)
        onStarted()
    }

    fun resumeIt(h: EarlierConversation) = go { resumeConversation(context, h); onStarted() }

    MockPage {
        MockTopBar("New session", MockIcons.Close, onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(12.dp)).padding(4.dp)) {
                SegTab("Start fresh", !tabResume, Modifier.weight(1f)) { tabResume = false }
                SegTab("Resume earlier", tabResume, Modifier.weight(1f)) { tabResume = true }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("WHERE")
                MockBox(Modifier.fillMaxWidth(), onClick = { choosingWorkspace = true }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Workspaces.display(root), style = mockText(13.sp, color = c.text, mono = true), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Change", style = mockText(12.sp, color = c.accent))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val chips = listOf(root) + repoDirs.map { it.path } + listOfNotNull(where.takeIf { w -> w != root && repoDirs.none { it.path == w } })
                    chips.forEach { path -> FolderChip(rel(path), where == path) { where = path; pickedAccount = null } }
                    FolderChip("Browse…", false) { browsing = true }
                }
            }

            if (!tabResume) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("CLAUDE ACCOUNT", extra = "· ${if (ws != null) "workspace" else "folder"} default, change for this session")
                    AccountDropdown(claudeOptions(claude.accounts), account.id, claude.default?.id, { id -> id?.let { pickedAccount = it } }, allowDefault = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Both can still be changed in the session (status line, controls sheet).
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("MODEL", spacing = 0.sp)
                        Select(models.firstOrNull { it.value == model }?.displayName ?: model, models.map { it.displayName to it.description }) { model = models[it].value }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("MODE", spacing = 0.sp)
                        Select(mode.label, PermissionMode.entries.map { it.label to it.description }) { mode = PermissionMode.entries[it] }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("NAME (OPTIONAL)", spacing = 0.sp)
                    MockTextField(name, { name = it }, placeholder = File(where).name, background = c.card)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("FIRST MESSAGE (OPTIONAL)", spacing = 0.sp)
                    if (attachments.isNotEmpty()) AttachmentTray(attachments, onRemove = { attachments.remove(it) })
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlusButton(onClick = { attaching = true })
                        MockTextField(message, { message = it }, Modifier.weight(1f), placeholder = "What should Claude work on?", background = c.card, singleLine = false, height = 90.dp)
                    }
                    attachHint?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("EARLIER CONVERSATIONS HERE")
                    when {
                        history == null -> Text("Reading Claude Code's history…", style = mockText(13.sp, color = c.textMuted))
                        history!!.isEmpty() -> Text("No earlier conversations in this workspace.", style = mockText(13.sp, color = c.textMuted))
                        else -> history!!.forEach { h ->
                            MockBox(Modifier.fillMaxWidth(), onClick = { resumeIt(h) }) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(h.title, style = mockText(14.sp, FontWeight.Medium, c.text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(whenText(h.lastActive), style = mockText(12.sp, color = c.textMuted), maxLines = 1)
                                    }
                                    Text("${rel(h.folder)} · ${h.messages} messages · ${h.account.name}", style = mockText(12.sp, color = c.textMuted, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Text("Includes conversations started from the terminal. Same history Claude Code keeps on a laptop.", style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 16.8.sp))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 22.dp)) {
            if (tabResume) AccentButton("Resume", { history?.firstOrNull()?.let(::resumeIt) }, enabled = !history.isNullOrEmpty())
            else AccentButton("Start session", ::startFresh, enabled = !importing)
        }
    }

    AttachmentPicker(
        sessionId = sessionId,
        open = attaching,
        onDismiss = { attaching = false },
        onAdded = { attachments += it },
        onHint = { attachHint = it },
        onImporting = { importing = it },
    )
    if (choosingWorkspace) {
        AlertDialog(
            onDismissRequest = { choosingWorkspace = false },
            title = { Text("Start in") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    workspaces.forEach { w ->
                        Text(
                            Workspaces.display(w.path),
                            style = mockText(14.sp, color = if (w.path == root) c.accent else c.text, mono = true),
                            modifier = Modifier.fillMaxWidth().clickable { root = w.path; where = w.path; pickedAccount = null; choosingWorkspace = false }.padding(vertical = 12.dp),
                        )
                    }
                    if (workspaces.isEmpty()) Text("No workspaces yet. Make one in the Workspaces tab, or browse to any folder.")
                }
            },
            confirmButton = { TextButton(onClick = { choosingWorkspace = false; browsing = true }) { Text("Other folder…") } },
            dismissButton = { TextButton(onClick = { choosingWorkspace = false }) { Text("Cancel") } },
        )
    }
    if (browsing) {
        FolderPrompt(
            title = "Folder",
            initial = Workspaces.tilde(where),
            confirm = "Use this folder",
            onDismiss = { browsing = false },
            onConfirm = { p ->
                if (!File(p).isDirectory) "That folder doesn't exist." else {
                    val inWs = Workspaces.containing(p)
                    root = inWs?.path ?: p
                    where = p
                    pickedAccount = null
                    null
                }
            },
        )
    }
    pending?.let { action ->
        val answered = {
            NotificationPermission.markAsked(context)
            pending = null
            action()
        }
        AlertDialog(
            onDismissRequest = answered,
            title = { Text("Turn on notifications?") },
            text = { Text("Sessions keep working while you're in another app. PocketRocket tells you when one needs your OK or finishes, and Android has notifications off for it right now.") },
            confirmButton = { TextButton(onClick = { answered(); NotificationPermission.openSettings(context) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = answered) { Text("Not now") } },
        )
    }
}

/** Opens [h] as a session with its history; opening it resumes Claude Code with `--resume`. */
private fun resumeConversation(context: Context, h: EarlierConversation) {
    val session = SessionManager.resumeClaude(context, File(h.folder), h.account.profile, h.conversationId, h.title)
    SessionActivity.open(context, session.id)
}

@Composable
private fun Label(text: String, extra: String? = null, spacing: androidx.compose.ui.unit.TextUnit = 0.4.sp) =
    com.pocketrocket.ui.components.Caps(text, size = 12.sp, spacing = spacing, extra = extra)

@Composable
private fun SegTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = PrTheme.colors
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) c.border else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(13.sp, FontWeight.SemiBold, if (selected) c.text else c.textMuted))
    }
}

@Composable
private fun FolderChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.height(36.dp).clip(shape).background(if (selected) c.accent else c.card).border(1.dp, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(12.sp, color = if (selected) c.onAccent else c.textSoft, mono = true), maxLines = 1)
    }
}

@Composable
private fun Select(text: String, options: List<Pair<String, String>>, onPick: (Int) -> Unit) {
    val c = PrTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        MockBox(Modifier.fillMaxWidth(), background = c.background, border = BorderStroke(1.dp, c.border), onClick = { open = true }) {
            Text("$text ▾", style = mockText(14.sp, color = c.text), modifier = Modifier.padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, (label, description) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label, style = mockText(14.sp, color = c.text))
                            if (description.isNotBlank()) Text(description, style = mockText(12.sp, color = c.textMuted))
                        }
                    },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

/** "14:32" today, "yesterday", "Mon" this week, "24 Oct" before. */
private fun whenText(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val days = ((now.timeInMillis - millis) / DateUtils.DAY_IN_MILLIS).toInt()
    return when {
        DateUtils.isToday(millis) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(then.time)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "yesterday"
        days < 6 -> SimpleDateFormat("EEE", Locale.getDefault()).format(then.time)
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> SimpleDateFormat("d MMM", Locale.getDefault()).format(then.time)
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(then.time)
    }
}
