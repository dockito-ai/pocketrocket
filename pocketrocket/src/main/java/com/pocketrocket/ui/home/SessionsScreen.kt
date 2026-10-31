package com.pocketrocket.ui.home

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.status
import com.pocketrocket.ui.theme.statusEdge
import com.pocketrocket.ui.theme.statusFill
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.Unseen
import com.pocketrocket.sessions.demo.DemoConversation
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.accounts.TextPrompt
import com.pocketrocket.ui.chat.EndSessionDialog
import com.pocketrocket.ui.chat.SessionActivity
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.EmptyState
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.RocketMark
import com.pocketrocket.ui.sessions.statusLabel
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.workspaces.GitHead
import com.pocketrocket.workspaces.Workspace
import com.pocketrocket.workspaces.Workspaces

/** Sessions of one workspace (or of folders outside any), as Home groups them. */
private data class SessionGroup(val id: String, val label: String, val path: String, val workspace: Workspace?, val items: List<Pair<Session, SessionState>>)

/**
 * Home (mockup Main): sessions grouped by workspace, with a tab per workspace and collapsible groups.
 * Long-press a card to end its session; long-press New session for the demo sessions (no login).
 */
@Composable
fun SessionsScreen(navigate: (String) -> Unit, onOpenTerminal: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val sessions by SessionManager.sessions.collectAsState()
    val workspaces by Workspaces.state.collectAsState()
    val states = sessions.map { it to it.state.collectAsState().value }
    val unseen by Unseen.ids.collectAsState()
    var tab by rememberSaveable { mutableStateOf("all") }
    var closed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var ending by remember { mutableStateOf<Session?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Session?>(null) }

    val groups = remember(states, workspaces) { group(states) }
    if (tab != "all" && groups.none { it.id == tab }) tab = "all"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sessions", style = mockText(22.sp, FontWeight.Bold, c.text, letterSpacing = (-0.3).sp))
                if (states.isNotEmpty()) SummaryPill(states.map { it.second })
            }
            if (states.isEmpty()) {
                EmptyState(
                    title = "No sessions yet",
                    body = "A session is a conversation with Claude Code in one of your workspaces. Start one to get going.",
                    illustration = { RocketMark(size = 88.dp) },
                    action = {
                        AccentButton("New session", onClick = { navigate(Routes.newSession()) }, height = 48.dp, radius = 16.dp, fontSize = 14.sp, modifier = Modifier.width(200.dp))
                        TextButton(onClick = onOpenTerminal) { Text("Open a terminal instead", style = mockText(13.sp, color = c.textMuted)) }
                    },
                )
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabPill("All", states.size, tab == "all") { tab = "all" }
                groups.forEach { g -> TabPill(g.label, g.items.size, tab == g.id) { tab = g.id } }
            }
            HorizontalDivider(thickness = 1.dp, color = c.divider)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                groups.filter { tab == "all" || it.id == tab }.forEach { g ->
                    item(key = g.id) {
                        val open = g.id !in closed
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    Modifier.weight(1f).heightIn(min = 44.dp).clickable { closed = if (open) closed + g.id else closed - g.id },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(MockIcons.ChevronRight, null, tint = c.textSoft, modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f))
                                    Text(g.path, style = mockText(13.sp, color = c.textSoft, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    // Folded away with news inside: the mark shows on the group instead.
                                    if (!open && g.items.any { (s, _) -> s.id in unseen }) UnseenDot()
                                    Text(
                                        "${g.items.size}",
                                        style = mockText(11.sp, color = c.textMuted),
                                        modifier = Modifier.background(c.card, RoundedCornerShape(999.dp)).border(1.dp, c.border, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                                    )
                                }
                                g.workspace?.let { ws ->
                                    Text("Open ›", style = mockText(12.sp, color = c.textMuted), modifier = Modifier.clickable { navigate(Routes.workspace(ws.path)) }.padding(vertical = 12.dp))
                                }
                            }
                            if (open) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    g.items.forEach { (session, state) ->
                                        Box {
                                            HomeSessionCard(state, g.workspace, unseen = session.id in unseen, onClick = { SessionActivity.open(context, session.id) }, onLongClick = { menuFor = session.id })
                                            DropdownMenu(expanded = menuFor == session.id, onDismissRequest = { menuFor = null }) {
                                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; renaming = session })
                                                DropdownMenuItem(text = { Text("End session") }, onClick = { menuFor = null; ending = session })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (states.isNotEmpty()) NewSessionFab(
            onClick = { navigate(Routes.newSession(if (tab == "all") "" else groups.firstOrNull { it.id == tab }?.workspace?.path.orEmpty())) },
            onLongClick = { DemoConversation.entries.forEach(SessionManager::startDemo) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 20.dp),
        )
    }

    renaming?.let { session ->
        TextPrompt("Rename session", "Name", session.state.value.title, "Save", onDismiss = { renaming = null }) {
            session.rename(it)
            renaming = null
        }
    }

    ending?.let { EndSessionDialog(it) { ending = null } }
}

private fun group(states: List<Pair<Session, SessionState>>): List<SessionGroup> =
    states.groupBy { Workspaces.containing(it.second.folder) }
        .map { (ws, items) ->
            if (ws != null) SessionGroup(ws.path, ws.name, Workspaces.display(ws.path), ws, items)
            else SessionGroup("other", "Other", "other folders", null, items)
        }
        .sortedWith(compareBy({ it.workspace == null }, { it.label.lowercase() }))

/** The "● 1 needs you  ● 2 working" pill by the title. */
@Composable
private fun SummaryPill(states: List<SessionState>) {
    val c = PrTheme.colors
    val waiting = states.count { it.status == SessionStatus.WaitingForPermission }
    val working = states.count { it.status == SessionStatus.Working || it.status == SessionStatus.Starting }
    val parts = buildList {
        if (waiting > 0) add(c.amber to "$waiting needs you")
        if (working > 0) add(c.blue to "$working working")
        if (isEmpty()) add(c.green to "${states.size} session${if (states.size == 1) "" else "s"}")
    }
    Row(
        Modifier.background(c.card, RoundedCornerShape(999.dp)).border(1.dp, c.border, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, (color, text) ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, style = mockText(11.sp, color = c.textMuted))
        }
    }
}

@Composable
private fun TabPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.heightIn(min = 36.dp).clip(shape).background(if (selected) c.text else Color.Transparent)
            .border(1.dp, if (selected) c.text else c.border, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = mockText(12.sp, FontWeight.Medium, if (selected) c.background else c.textSoft))
        Text("$count", style = mockText(11.sp, color = if (selected) c.onTextCount else c.textMuted))
    }
}

/** "app · main": where in the workspace the session runs, and the branch there. */
private fun where(state: SessionState, ws: Workspace?): String {
    val folder = state.folder.trimEnd('/')
    val name = when {
        ws == null -> Workspaces.display(folder)
        folder == ws.path -> ws.dir.name
        else -> folder.removePrefix(ws.path + "/")
    }
    return listOfNotNull(name, GitHead.branch(folder)).joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSessionCard(state: SessionState, ws: Workspace?, unseen: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = PrTheme.colors
    val fg = c.status(state.shownStatus)
    val chip = c.statusFill(state.shownStatus)
    val edge = c.statusEdge(state.shownStatus)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(BorderStroke(1.dp, edge), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (unseen) UnseenDot()
            Text(state.title, style = mockText(14.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(
                Modifier.background(chip, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).background(fg, CircleShape))
                Text(state.statusLabel, style = mockText(10.5.sp, FontWeight.SemiBold, fg))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(remember(state.folder) { where(state, ws) }, style = mockText(11.sp, color = c.textMuted, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("·", style = mockText(11.sp, color = c.textMuted))
            Text(state.profile.name, style = mockText(11.sp, color = c.textMuted), maxLines = 1)
        }
        Text(state.lastLine, style = mockText(12.sp, color = c.textPreview).copy(lineHeight = 17.4.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Something happened in a session you have not looked at yet ([Unseen]). */
@Composable
private fun UnseenDot() = Box(Modifier.size(8.dp).background(PrTheme.colors.accent, CircleShape))

/** The orange "+ New session" button over the list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewSessionFab(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.accent).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MockIcons.Plus, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        Text("New session", style = mockText(14.sp, FontWeight.SemiBold, c.onAccent))
    }
}
