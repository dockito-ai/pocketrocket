package com.pocketrocket.ui.workspaces

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.status
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.history.EarlierConversations
import com.pocketrocket.termux.Termux
import com.pocketrocket.ui.LocalRouteOnTop
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.chat.SessionActivity
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.git.GitScreens
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.workspaces.RepoPrs
import com.pocketrocket.workspaces.RepoStatus
import com.pocketrocket.workspaces.RepoSummary
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A workspace (mockup Workspace): its accounts, its sessions, its repos (each opens the Git panel),
 * Browse files and a terminal in the folder with the workspace's accounts.
 */
@Composable
fun WorkspaceScreen(path: String, onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val workspaces by Workspaces.state.collectAsState()
    val ws = workspaces.firstOrNull { it.path == path }
    if (ws == null) {
        // Gone (removed, or moved by the file manager above, which then fixes this route): leave only when on top,
        // since back always takes the top screen.
        if (LocalRouteOnTop.current) LaunchedEffect(Unit) { onBack() }
        return
    }
    val bindings by AccountWiring.bindings.collectAsState()
    val claude by ClaudeAccounts.state.collectAsState()
    val github by GitHubAccounts.state.collectAsState()
    val all by RepoStatus.all.collectAsState()
    val repos = all[ws.path]
    val sessions by SessionManager.sessions.collectAsState()
    val mine = sessions.map { it to it.state.collectAsState().value }.filter { Workspaces.containing(it.second.folder)?.path == ws.path }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose {} }
    LaunchedEffect(refresh) { RepoStatus.refresh(context, ws) }
    val earlier by produceState(0, refresh, mine.size) {
        value = withContext(Dispatchers.IO) {
            EarlierConversations.list(ws.path, ClaudeAccounts.current.accounts, mine.map { it.second.conversationId }.toSet()).size
        }
    }
    val claudeName = remember(bindings, claude) { AccountWiring.claudeFor(ws.path).name }
    val githubName = remember(bindings, github) { AccountWiring.githubFor(ws.path)?.login ?: "not connected" }

    MockPage {
        MockTopBar(ws.name, MockIcons.Back, onBack, titleSize = 20.sp, titleWeight = FontWeight.Bold, subtitle = Workspaces.tilde(ws.path), bottom = 4.dp) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { navigate(Routes.workspaceSettings(ws.path)) }, contentAlignment = Alignment.Center) {
                Icon(MockIcons.Gear, contentDescription = "Workspace settings", tint = c.textSoft, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp).clickable { navigate(Routes.workspaceSettings(ws.path)) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountPill("Claude", claudeName)
            AccountPill("GitHub", githubName)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHead("SESSIONS", "+ New") { navigate(Routes.newSession(ws.path)) }
                if (mine.isEmpty()) Text("No sessions here yet.", style = mockText(13.sp, color = c.textMuted), modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp))
                mine.forEach { (session, state) ->
                    val dot = c.status(state.shownStatus)
                    val needsYou = state.status == SessionStatus.WaitingForPermission
                    MockBox(
                        Modifier.fillMaxWidth(),
                        border = BorderStroke(if (needsYou) 1.5.dp else 1.dp, if (needsYou) c.amber else c.border),
                        onClick = { SessionActivity.open(context, session.id) },
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(8.dp).background(dot, CircleShape))
                            Text(state.title, style = mockText(14.sp, FontWeight.Medium, c.text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (state.folder == ws.path) "root" else state.folder.removePrefix(ws.path + "/"),
                                style = mockText(12.sp, color = c.textMuted, mono = true), maxLines = 1,
                            )
                        }
                    }
                }
                if (earlier > 0) Text(
                    "Earlier conversations here · $earlier ›",
                    style = mockText(13.sp, color = c.textMuted),
                    modifier = Modifier.clickable { navigate(Routes.newSession(ws.path, resume = true)) }.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHead("REPOS", "+ Clone here") { navigate(Routes.clone(ws.path)) }
                when {
                    repos == null -> Text("Reading repos…", style = mockText(13.sp, color = c.textMuted))
                    repos.isEmpty() -> Text("No repos yet. Clone one, or run git init in a terminal here.", style = mockText(13.sp, color = c.textMuted))
                    else -> repos.forEach { RepoCard(it) { GitScreens.open(context, it.dir.path) } }
                }
            }

            MockBox(
                Modifier.fillMaxWidth(), radius = 14.dp, background = c.background,
                onClick = { repos?.firstOrNull()?.let { GitScreens.browse(context, it.dir.path) } },
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Browse files", style = mockText(14.sp, color = c.text))
                    Text("›", style = mockText(14.sp, color = c.textMuted))
                }
            }
            OutlineButton("Open a terminal here", { Termux.openTerminalIn(context, ws.dir, ws.name) }, Modifier.fillMaxWidth(), color = c.textSoft)
        }
    }
}

@Composable
private fun AccountPill(label: String, name: String) {
    val c = PrTheme.colors
    Text(
        buildAnnotatedString {
            append("$label · ")
            withStyle(SpanStyle(color = c.accent)) { append(name) }
        },
        style = mockText(12.sp, color = c.text),
        maxLines = 1,
        modifier = Modifier.background(c.card, RoundedCornerShape(999.dp)).border(1.dp, c.border, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionHead(label: String, action: String, onAction: () -> Unit) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Caps(label, size = 12.sp, spacing = 0.4.sp, modifier = Modifier.weight(1f))
        Text(action, style = mockText(13.sp, FontWeight.SemiBold, c.accent), modifier = Modifier.clickable(onClick = onAction).padding(vertical = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(r: RepoSummary, onClick: () -> Unit) {
    val c = PrTheme.colors
    MockBox(Modifier.fillMaxWidth(), radius = 14.dp, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = mockText(15.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.branch, style = mockText(12.sp, color = c.green, mono = true), maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (r.changed > 0) "● ${r.changed} changed" else "clean", style = mockText(12.sp, color = c.textSoft))
                Text(
                    when {
                        r.ahead > 0 && r.behind > 0 -> "↑${r.ahead} ↓${r.behind}"
                        r.ahead > 0 -> "↑${r.ahead} to push"
                        r.behind > 0 -> "↓${r.behind} to pull"
                        r.hasUpstream -> "up to date"
                        else -> "not pushed yet"
                    },
                    style = mockText(12.sp, color = c.textSoft),
                )
            }
            r.lastCommit?.let { Text(it, style = mockText(12.sp, color = c.textMuted, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            RepoPrLine(r)
        }
    }
}

/**
 * The card's PR line (BranchesPRs.html §1): "⇄ 3 open PRs" in purple, review requests in amber, and the checked-out
 * branch's PR. Loaded lazily through gh and cached briefly ([RepoPrs]); nothing shows when gh can't answer.
 */
@Composable
private fun RepoPrLine(r: RepoSummary) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val summary by produceState(RepoPrs.cached(r.dir), r.dir.path, r.branch) { value = RepoPrs.load(context, r.dir, r.branch) }
    val s = summary ?: return
    if (s.open == 0) return
    fun prs(n: Int) = if (n == 1) "1 PR" else "$n PRs"
    Text(
        buildAnnotatedString {
            if (s.toReview > 0 && s.toReview == s.open) {
                withStyle(SpanStyle(color = c.amber)) { append("⇄ ${prs(s.toReview)} waiting for your review") }
                return@buildAnnotatedString
            }
            withStyle(SpanStyle(color = c.purple)) { append("⇄ ${s.open} open ${if (s.open == 1) "PR" else "PRs"}") }
            if (s.toReview > 0) withStyle(SpanStyle(color = c.amber)) { append(" · ${s.toReview} waiting for your review") }
            s.branchPr?.let { pr ->
                val note = listOfNotNull("yours".takeIf { s.mine }, RepoPrs.checksPhrase(pr)).joinToString(", ")
                withStyle(SpanStyle(color = c.textMuted)) { append(" · #${pr.number}" + if (note.isNotEmpty()) " $note" else "") }
            }
        },
        style = mockText(12.sp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
