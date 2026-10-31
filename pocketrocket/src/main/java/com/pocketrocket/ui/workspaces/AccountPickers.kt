package com.pocketrocket.ui.workspaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.profiles.DockitoAccountEntry
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.auth.ClaudeStatuses
import com.pocketrocket.auth.ClaudeUsage
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccountEntry

/**
 * Notes under each Claude account, as the mockup words them: "Max · default", or with [usage]
 * "Claude Max · default · 42% of 5-hour used" (settings) / "Max · 42% used" (new session).
 */
@Composable
internal fun claudeNotes(accounts: List<ClaudeAccountEntry>, style: NoteStyle): Map<String, String> {
    val context = LocalContext.current
    val statuses by ClaudeStatuses.all.collectAsState()
    val usage by ClaudeUsage.all.collectAsState()
    LaunchedEffect(accounts.map { it.id }) { ClaudeStatuses.check(context, accounts.filter { ClaudeStatuses.of(it.id) == SignInStatus.Checking }) }
    val defaultId = ClaudeAccounts.default.id
    return accounts.associate { a ->
        val plan = (statuses[a.id] as? SignInStatus.SignedIn)?.account?.subscription?.replaceFirstChar(Char::uppercase)
        val signedOut = statuses[a.id] == SignInStatus.SignedOut
        val percent = (usage[a.id] ?: ClaudeUsage.get(a))?.fiveHour?.percent
        val parts = when (style) {
            NoteStyle.Short -> listOfNotNull(if (signedOut) "not signed in" else plan, if (a.id == defaultId) "default" else null)
            NoteStyle.Usage -> listOfNotNull(if (signedOut) "not signed in" else plan, percent?.let { "$it% used" })
            NoteStyle.Long -> listOfNotNull(
                if (signedOut) "not signed in" else plan?.let { "Claude $it" },
                if (a.id == defaultId) "default" else null,
                percent?.let { "$it% of 5-hour used" },
            )
        }
        a.id to parts.joinToString(" · ").ifEmpty { " " }
    }
}

internal enum class NoteStyle { Short, Usage, Long }

/** "Shankar Kakumani <…@users.noreply.github.com>" for the identity line. */
internal fun identityLine(account: GitHubAccountEntry): String {
    val email = account.userEmail
    val shown = if (email.endsWith("@users.noreply.github.com")) "…@users.noreply.github.com" else email
    return "${account.userName.ifBlank { account.login }} <$shown>"
}

/** One account in an [AccountDropdown]: its name and a short note ("Max", "you@x.com"). */
internal data class AccountOption(val id: String, val name: String, val note: String = "")

internal fun githubOptions(accounts: List<GitHubAccountEntry>) = accounts.map { AccountOption(it.id, it.login, it.userEmail) }

internal fun dockitoOptions(accounts: List<DockitoAccountEntry>) = accounts.map { AccountOption(it.id, it.name, it.email) }

@Composable
internal fun claudeOptions(accounts: List<ClaudeAccountEntry>): List<AccountOption> {
    val notes = claudeNotes(accounts, NoteStyle.Usage)
    return accounts.map { AccountOption(it.id, it.name, notes[it.id].orEmpty().trim()) }
}

/**
 * The account picker for every kind (Claude, GitHub, Dockito): one row showing the current choice, which
 * opens a menu of all accounts, the default marked. With [allowDefault], the menu starts with "Default",
 * picked as null so the choice follows the default when it changes; [selected] null shows it. Fits any
 * number of accounts.
 */
@Composable
internal fun AccountDropdown(
    options: List<AccountOption>,
    selected: String?,
    defaultId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowDefault: Boolean = true,
) {
    val c = PrTheme.colors
    var open by remember { mutableStateOf(false) }
    val default = options.firstOrNull { it.id == defaultId }
    val current = options.firstOrNull { it.id == selected } ?: if (!allowDefault) default ?: options.firstOrNull() else null
    fun noteOf(o: AccountOption) = listOfNotNull(if (o.id == defaultId) "default" else null, o.note.ifBlank { null }).joinToString(" · ")
    val title = current?.name ?: if (default != null) "Default" else "None"
    val note = current?.let(::noteOf) ?: default?.let { listOfNotNull(it.name, it.note.ifBlank { null }).joinToString(" · ") }.orEmpty()
    Box(modifier.fillMaxWidth()) {
        MockBox(Modifier.fillMaxWidth(), background = c.surface, border = BorderStroke(1.dp, c.border), onClick = { open = true }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = mockText(14.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (note.isNotBlank()) Text(note, style = mockText(11.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("▾", style = mockText(14.sp, color = c.textMuted))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card) {
            if (allowDefault && default != null) MenuOption("Default", default.name, selected == null) { open = false; onSelect(null) }
            options.forEach { o -> MenuOption(o.name, noteOf(o), o.id == current?.id) { open = false; onSelect(o.id) } }
        }
    }
}

@Composable
private fun MenuOption(name: String, note: String, checked: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, style = mockText(14.sp, if (checked) FontWeight.SemiBold else FontWeight.Normal, if (checked) c.accent else c.text))
                if (note.isNotBlank()) Text(note, style = mockText(11.sp, color = c.textMuted))
            }
        },
        trailingIcon = if (checked) ({ Text("✓", style = mockText(14.sp, FontWeight.SemiBold, c.accent)) }) else null,
        onClick = onClick,
    )
}
