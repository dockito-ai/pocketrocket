package com.pocketrocket.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.pocketrocket.assistant.ActivityLog
import com.pocketrocket.assistant.AssistantMemory
import com.pocketrocket.assistant.AutoModeNet
import com.pocketrocket.assistant.MemoryBook
import com.pocketrocket.assistant.Note
import com.pocketrocket.assistant.ProjectScan
import com.pocketrocket.assistant.Saved
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.AccentSwitch
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pocketrocket.assistant.SensitiveActions
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/** Settings › Assistant › Activity (mockup In-Activity), newest first. Commands show until the app restarts; they aren't kept on disk. */
@Composable
fun AssistantActivityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val entries by remember { ActivityLog.entries(context) }.collectAsState()
    val clock = remember { DateFormat.getTimeFormat(context) }
    MockPage {
        MockTopBar("Activity", MockIcons.Back, onBack)
        if (entries.isEmpty()) {
            Text("Nothing yet. What Rocket says on its own and what it approves for you shows here.", style = mockText(14.sp, color = c.textSoft).copy(lineHeight = 21.sp), modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            items(entries.asReversed()) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(clock.format(entry.at), style = mockText(12.sp, color = c.textSoft, mono = true), modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.text, style = mockText(14.sp, color = c.text))
                        entry.command?.takeIf { it.isNotBlank() }?.let { Text(it, style = mockText(13.sp, color = c.accent, mono = true), maxLines = 4) }
                        Text(entry.how, style = mockText(12.sp, color = c.textSoft))
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = c.divider)
            }
        }
    }
}

/** Settings › Assistant › Memory: every note to edit or delete, the nicknames, and Refresh projects. */
@Composable
fun AssistantMemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val book by AssistantMemory.book.collectAsState()
    var editing by remember { mutableStateOf<Note?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }
    var refreshed by remember { mutableStateOf<String?>(null) }
    MockPage {
        MockTopBar("Memory", MockIcons.Back, onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "What Rocket has learned from you, ${book.notes.size} of ${MemoryBook.MAX_NOTES} notes. Notes can't remove an approval, and tokens and passwords are refused.",
                style = mockText(14.sp, color = c.textSoft).copy(lineHeight = 21.sp),
            )
            Column {
                Caps("NOTES", modifier = Modifier.padding(bottom = 4.dp))
                if (book.notes.isEmpty()) Text("Nothing yet. Say \"Remember...\" to Rocket.", style = mockText(14.sp, color = c.textMuted), modifier = Modifier.padding(vertical = 12.dp))
                book.notes.forEachIndexed { i, note ->
                    MemoryRow(note.text, if (note.habit) "A habit you said yes to" else null, onEdit = { editing = note }, onDelete = { AssistantMemory.change { it.forget(note.id) } })
                    if (i < book.notes.lastIndex) HorizontalDivider(thickness = 1.dp, color = c.divider)
                }
            }
            Column {
                Caps("NICKNAMES", modifier = Modifier.padding(bottom = 4.dp))
                if (book.nicknames.isEmpty()) Text("None yet. Rocket asks when it doesn't know a name.", style = mockText(14.sp, color = c.textMuted), modifier = Modifier.padding(vertical = 12.dp))
                book.nicknames.forEachIndexed { i, nick ->
                    MemoryRow(nick.name, nick.project.substringAfterLast('/'), onEdit = null, onDelete = { AssistantMemory.change { it.forgetNickname(nick.name) } })
                    if (i < book.nicknames.lastIndex) HorizontalDivider(thickness = 1.dp, color = c.divider)
                }
            }
            AccentButton(
                "Refresh projects",
                onClick = {
                    ProjectScan.refresh(context)
                    refreshed = "${AssistantMemory.book.value.projects.size} projects"
                },
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp, radius = 14.dp, fontSize = 15.sp, background = c.card, content = c.text,
            )
            refreshed?.let { Text(it, style = mockText(13.sp, color = c.textSoft)) }
            refused?.let { Text(it, style = mockText(13.sp, color = c.amber)) }
        }
    }
    editing?.let { note ->
        var text by remember(note.id) { mutableStateOf(note.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit note") },
            text = { MockTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Note") },
            confirmButton = {
                TextButton(onClick = {
                    val result = AssistantMemory.book.value.edit(note.id, text)
                    if (result is Saved.Yes) { AssistantMemory.change { (it.edit(note.id, text) as? Saved.Yes)?.book ?: it }; refused = null }
                    else refused = if (result == Saved.LooksLikeSecret) "That looks like a token or password, so it wasn't saved." else "A note can't be empty."
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemoryRow(text: String, note: String?, onEdit: (() -> Unit)?, onDelete: () -> Unit) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = mockText(14.sp, color = c.text))
            note?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
        }
        onEdit?.let { TextButton(onClick = it) { Text("Edit", style = mockText(13.sp, color = c.accent)) } }
        TextButton(onClick = onDelete) { Text("Delete", style = mockText(13.sp, color = c.textSoft)) }
    }
}

/** Settings › Assistant › Projects (mockup In-Projects): a switch per project. Off, Rocket can only tell you its status. */
@Composable
fun AssistantProjectsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val book by AssistantMemory.book.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ProjectScan.refresh(context) } }
    MockPage {
        MockTopBar("Projects", MockIcons.Back, onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "Rocket knows your workspaces, their sessions and branches. Turn one off and Rocket can only tell you its status.",
                style = mockText(14.sp, color = c.textSoft).copy(lineHeight = 21.sp),
            )
            Column {
                if (book.projects.isEmpty()) Text("No workspaces yet.", style = mockText(14.sp, color = c.textMuted))
                book.projects.forEachIndexed { i, project ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(Workspaces.display(project.path), style = mockText(14.sp, color = c.text))
                            if (project.description.isNotEmpty()) Text(project.description, style = mockText(12.sp, color = c.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        AccentSwitch(project.enabled) { on -> AssistantMemory.change { it.setEnabled(project.path, on) } }
                    }
                    if (i < book.projects.lastIndex) HorizontalDivider(thickness = 1.dp, color = c.divider)
                }
            }
            Column {
                Caps("IN THESE PROJECTS", modifier = Modifier.padding(bottom = 4.dp))
                listOf(
                    "Status, tokens, show changes" to "Just does it",
                    "Start agents, send messages, compact" to "Just does it",
                    "Allow, push, Auto mode" to "Asks you first",
                ).forEachIndexed { i, (what, how) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(what, style = mockText(14.sp, color = c.text), modifier = Modifier.weight(1f))
                        Text(how, style = mockText(13.sp, color = c.textSoft))
                    }
                    if (i < 2) HorizontalDivider(thickness = 1.dp, color = c.divider)
                }
            }
        }
    }
}

/** Settings › Assistant › Sensitive actions. Read-only: the list is decided in the app, and showing it can't change it. */
@Composable
fun SensitiveActionsScreen(onBack: () -> Unit) {
    val c = PrTheme.colors
    MockPage {
        MockTopBar("Sensitive actions", MockIcons.Back, onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "These always show a popup from the app with exactly what will happen, and need your fingerprint or PIN, even with the phone unlocked. Ignoring the popup means no. Claude and Rocket's memory can't change this list.",
                style = mockText(14.sp, color = c.textSoft).copy(lineHeight = 21.sp),
            )
            Column {
                Caps("FINGERPRINT, EVERY TIME", modifier = Modifier.padding(bottom = 4.dp))
                SensitiveActions.FINGERPRINT.forEachIndexed { i, (name, note) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = mockText(14.sp, color = c.text))
                        Text(note, style = mockText(12.sp, color = c.textMuted))
                    }
                    if (i < SensitiveActions.FINGERPRINT.lastIndex) HorizontalDivider(thickness = 1.dp, color = c.divider)
                }
            }
            Column {
                Caps("A SPOKEN YES, UNLOCKED", modifier = Modifier.padding(bottom = 4.dp))
                SensitiveActions.SPOKEN.forEachIndexed { i, name ->
                    Text(name, style = mockText(14.sp, color = c.text), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    if (i < SensitiveActions.SPOKEN.lastIndex) HorizontalDivider(thickness = 1.dp, color = c.divider)
                }
            }
            Text(
                "An agent in Manual stops and asks, so the fingerprint sits on its request. An agent in Auto stops the same way when a command on this list is written out, while Rocket is on and for agents started since. " + AutoModeNet.SETTINGS_NOTE,
                style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp),
            )
        }
    }
}
