package com.pocketrocket.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.auth.ClaudeStatuses
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccounts
import com.pocketrocket.ui.components.MockIcons

/** Settings → ACCOUNTS (mockup Settings): a Claude card and a GitHub card (Dockito's logins are on its tool page, decision 64), each showing the default and opening its list. */
@Composable
fun AccountsSetting(onClaude: () -> Unit, onGitHub: () -> Unit) {
    val context = LocalContext.current
    val claude by ClaudeAccounts.state.collectAsState()
    val github by GitHubAccounts.state.collectAsState()
    val statuses by ClaudeStatuses.all.collectAsState()
    val default = claude.default

    LaunchedEffect(default?.id) { default?.let { ClaudeStatuses.check(context, listOf(it)) } }

    val claudeLine = default?.let { entry ->
        when (val s = statuses[entry.id]) {
            is SignInStatus.SignedIn -> listOfNotNull(entry.name, s.account.subscription?.replaceFirstChar(Char::uppercase), "default").joinToString(" · ")
            SignInStatus.SignedOut -> "${entry.name} · not signed in"
            else -> "${entry.name} · default"
        }
    } ?: "Not set up"
    val githubLine = github.default?.let { "${it.login} · default" } ?: "Not connected"

    Section("ACCOUNTS") {
        SettingsCard(tile = { LetterTile("C") }, title = "Claude", line = claudeLine, trailing = countLabel(claude.accounts.size), onClick = onClaude)
        SettingsCard(tile = { IconTile(MockIcons.Branch) }, title = "GitHub", line = githubLine, trailing = countLabel(github.accounts.size), onClick = onGitHub)
    }
}

private fun countLabel(count: Int) = if (count > 0) "$count ›" else "›"
