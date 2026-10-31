package com.pocketrocket.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.AccentSwitch
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.auth.ClaudeStatuses
import com.pocketrocket.auth.ClaudeUsage
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.claudeconfig.ClaudeConnections
import com.pocketrocket.ui.claudeconfig.AccountSetupSection
import com.pocketrocket.ui.claudeconfig.ConnectionsSection
import com.pocketrocket.ui.claudeconfig.PluginsSection
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.ui.sessions.displayPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Claude account: who it is, plan usage, what uses it, name and default, sign in/out. */
@Composable
fun ClaudeAccountScreen(id: String, onBack: () -> Unit, onSignIn: (String) -> Unit, onOpenMemory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by ClaudeAccounts.state.collectAsState()
    val entry = list.get(id) ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val statuses by ClaudeStatuses.all.collectAsState()
    val status = statuses[id] ?: SignInStatus.Checking
    val usages by ClaudeUsage.all.collectAsState()
    val usage = usages[id] ?: remember(id) { ClaudeUsage.get(entry) }
    val bindings by AccountWiring.bindings.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(id) {
        ClaudeStatuses.check(context, listOf(entry))
        if (ClaudeStatuses.of(id) is SignInStatus.SignedIn) {
            refreshing = true
            withContext(Dispatchers.IO) { ClaudeUsage.refresh(context, entry) }
            refreshing = false
        }
    }

    val c = PrTheme.colors
    AccountPage(entry.name, onBack, gap = 22.dp, top = 8.dp) {
        // Who.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            InitialBadge(entry.name, 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val (who, line) = when (status) {
                    is SignInStatus.SignedIn -> (status.account.email ?: "Claude account") to
                        listOfNotNull(status.account.planLabel, status.account.teamName, "signed in").joinToString(" · ")
                    SignInStatus.SignedOut -> "Not signed in" to "Sign in with a Claude Pro or Max plan."
                    SignInStatus.Checking -> "Checking sign-in…" to null
                }
                Text(who, style = mockText(15.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                line?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
            }
            if (list.isDefault(entry)) DefaultChip(large = true)
        }

        // Usage.
        QuietCard {
            SectionLabel("Usage")
            if (usage == null || usage.windows.isEmpty()) {
                Text(
                    if (refreshing) "Asking Claude Code for this plan's usage…"
                    else if (status is SignInStatus.SignedOut) "Sign in to see this plan's limits."
                    else "No usage reported yet. It shows after the first reply in a session on this account.",
                    style = mockText(13.sp, color = c.textMuted),
                )
            } else {
                usage.windows.forEach { w ->
                    val detail = listOfNotNull("${w.percent}%", if (w.limited) "limit reached" else null, resetText(w.resetsAt, w.resetsText)).joinToString(" · ")
                    UsageBar(w.label, detail, w.percent / 100f)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "From Claude Code: the last reply in any session on this account, or a check when this page opens." +
                        (usage?.let { " Updated " + ago(it.updatedAt) + "." } ?: ""),
                    style = mockText(11.sp, color = c.textMuted),
                    modifier = Modifier.weight(1f),
                )
                if (refreshing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                }
            }
        }

        // Connections: only checked when asked, so opening this page starts nothing extra.
        ConnectionsSection(entry, status)
        PluginsSection(entry)
        AccountSetupSection(entry, onOpenMemory)

        // In use by.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("In use by")
            val folders = bindings.filter { (it.claude ?: ClaudeAccounts.default.id) == id }.map { it.folder }
            val sessions = sessionCount(entry)
            if (folders.isEmpty() && sessions == 0) {
                Text(
                    if (list.isDefault(entry)) "Nothing yet. Folders without their own account use this one." else "Nothing yet.",
                    style = mockText(12.sp, color = c.textMuted),
                )
            }
            folders.forEach { LinkRow(displayPath(it), "folder") }
            if (sessions > 0) LinkRow("Sessions", if (sessions == 1) "1 session" else "$sessions sessions")
        }

        // Account.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel("Account", Modifier.padding(bottom = 6.dp))
            SettingLine("Name", onClick = { renaming = true }) { Text("${entry.name} ›", style = mockText(14.sp, color = c.textMuted)) }
            SettingLine("Default for new workspaces", last = true) {
                AccentSwitch(list.isDefault(entry)) { if (it) ClaudeAccounts.setDefault(id) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status is SignInStatus.SignedOut) {
                AccentButton("Sign in", onClick = { onSignIn(id) }, height = 44.dp, radius = 12.dp, fontSize = 14.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("Sign in again", onClick = { onSignIn(id) }, modifier = Modifier.weight(1f))
                    OutlineButton(
                        "Sign out",
                        onClick = { confirmSignOut = true }, enabled = status is SignInStatus.SignedIn,
                        modifier = Modifier.weight(1f), color = c.redSoft, border = c.redBorder,
                    )
                }
            }
            if (!entry.isMain) {
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove this account", style = mockText(14.sp, color = c.redSoft))
                }
            }
        }
    }

    if (renaming) {
        TextPrompt("Rename account", "Name", entry.name, "Save", onDismiss = { renaming = false }) {
            renaming = false
            ClaudeAccounts.rename(id, it)
        }
    }
    if (confirmSignOut) {
        val who = (status as? SignInStatus.SignedIn)?.account?.email ?: entry.name
        Confirm(
            "Sign out of $who?",
            "Sessions on this account stop working until you sign in again. Other accounts are not affected.",
            "Sign out", onDismiss = { confirmSignOut = false },
        ) {
            ClaudeConnections.forget(id)
            scope.launch { ClaudeStatuses.signOut(context, entry) }
        }
    }
    if (confirmRemove) {
        Confirm(
            "Remove ${entry.name}?",
            "Signs it out and deletes its Claude Code settings and history on this phone. Folders using it switch to the default.",
            "Remove", onDismiss = { confirmRemove = false },
        ) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    com.pocketrocket.auth.ClaudeCli.logout(context, entry.configDir)
                    ClaudeAccounts.remove(id)
                    ClaudeConnections.forget(id)
                }
                onBack()
            }
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
internal fun ago(epochMs: Long): String {
    val min = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}
