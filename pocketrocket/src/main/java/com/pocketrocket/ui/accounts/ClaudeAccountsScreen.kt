package com.pocketrocket.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.pocketrocket.ui.components.DashedAddButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrColors
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.auth.ClaudeStatuses
import com.pocketrocket.auth.ClaudeUsage
import com.pocketrocket.auth.PlanUsage
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.browser.Chromium
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.sessions.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Claude: every Claude account, the default marked; tap one for its page. */
@Composable
fun ClaudeAccountsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val list by ClaudeAccounts.state.collectAsState()
    val statuses by ClaudeStatuses.all.collectAsState()
    val usage by ClaudeUsage.all.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(list.accounts.map { it.id }) { ClaudeStatuses.check(context, list.accounts) }

    val c = PrTheme.colors
    AccountPage("Claude accounts", onBack) {
        list.accounts.forEach { entry ->
            val status = statuses[entry.id] ?: SignInStatus.Checking
            val plan = usage[entry.id] ?: ClaudeUsage.get(entry)
            QuietCard(radius = 14.dp, padding = 14.dp, onClick = { onOpen(entry.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InitialBadge(entry.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.name, style = mockText(15.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (list.isDefault(entry)) DefaultChip()
                        }
                        Text(claudeSummary(status, plan, sessionCount(entry)), style = mockText(12.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusDot(statusColor(status, plan, c))
                    Text("›", style = mockText(14.sp, color = c.textMuted))
                }
            }
        }
        DashedAddButton("+ Add a Claude account", onClick = { adding = true }, modifier = Modifier.padding(top = 6.dp))
        Text(
            "Tap an account for its usage, status and settings. New workspaces start on the default.",
            style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.4.sp),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }

    if (adding) {
        TextPrompt(
            title = "Add a Claude account",
            label = "Name",
            initial = "",
            body = "A name for this subscription, like work or side. You sign in to it next.",
            confirm = "Add and sign in",
            onDismiss = { adding = false },
        ) { name ->
            adding = false
            val added = ClaudeAccounts.add(name)
            Dockito.onClaudeAccountAdded(context, added.folder)
            Chromium.onClaudeAccountAdded(context, added)
            onAdded(added.id)
        }
    }
}

/** "Max · 42% of 5-hour · 4 sessions", "Not signed in", "Pro · limit reached · resets 6:40 pm". */
internal fun claudeSummary(status: SignInStatus, usage: PlanUsage?, sessions: Int): String {
    val parts = mutableListOf<String>()
    when (status) {
        SignInStatus.Checking -> parts += "Checking…"
        SignInStatus.SignedOut -> return "Not signed in"
        is SignInStatus.SignedIn -> status.account.subscription?.let { parts += it.replaceFirstChar(Char::uppercase) }
    }
    usage?.fiveHour?.let { w ->
        parts += if (usage.limited) listOfNotNull("limit reached", resetText(w.resetsAt, w.resetsText)).joinToString(" · ")
        else "${w.percent}% of 5-hour"
    }
    if (sessions > 0) parts += if (sessions == 1) "1 session" else "$sessions sessions"
    return parts.joinToString(" · ").ifEmpty { "Signed in" }
}

internal fun statusColor(status: SignInStatus, usage: PlanUsage?, c: PrColors) = when {
    status !is SignInStatus.SignedIn -> c.textMuted
    usage?.limited == true -> c.amber
    else -> c.green
}

/** Sessions in the grid running as [entry]. */
internal fun sessionCount(entry: ClaudeAccountEntry): Int =
    SessionManager.sessions.value.count { it.state.value.profile.configDir == entry.configDir?.path }

/** "resets in 2h 14m", "resets Mon 9:59 am" in the phone's time zone, or Claude Code's own wording. */
internal fun resetText(resetsAt: Long?, resetsText: String?): String? {
    if (resetsAt != null) {
        val ms = resetsAt * 1000 - System.currentTimeMillis()
        if (ms <= 0) return "resets now"
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "resets in ${minutes}m"
            minutes < 24 * 60 -> "resets in ${minutes / 60}h ${minutes % 60}m"
            else -> "resets " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(resetsAt * 1000))
        }
    }
    return resetsText?.let { "resets $it" }
}
