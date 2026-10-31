package com.pocketrocket.health

import android.content.Context
import com.pocketrocket.auth.ClaudeStatuses
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.github.GhCli
import com.pocketrocket.health.HealthState.NeedsAttention
import com.pocketrocket.health.HealthState.Ok
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.GitHubAccountEntry
import com.pocketrocket.profiles.GitHubAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** ACCOUNTS: every Claude login still valid, every GitHub key still accepted by github.com. */
internal object AccountChecks {

    suspend fun run(context: Context): HealthGroup = coroutineScope {
        val claude = async { claude(context) }
        val github = async { github(context) }
        HealthGroup("ACCOUNTS", listOf(claude.await(), github.await()))
    }

    /** Reuses the accounts screens' own check (`claude auth status` per config dir), so they update too. */
    private suspend fun claude(context: Context): HealthItem {
        val accounts = ClaudeAccounts.current.accounts
        ClaudeStatuses.check(context, accounts)
        val out = accounts.filter { ClaudeStatuses.of(it.id) !is SignInStatus.SignedIn }
        val signedIn = accounts.size - out.size
        return when {
            accounts.isEmpty() -> HealthItem("Claude", NeedsAttention, "No account", HealthFix.Navigate("claude"))
            out.isEmpty() -> HealthItem("Claude", Ok, "$signedIn signed in")
            else -> HealthItem("Claude", NeedsAttention, "${out.joinToString { it.name }} signed out", HealthFix.Navigate("claude"))
        }
    }

    private suspend fun github(context: Context): HealthItem = coroutineScope {
        val accounts = GitHubAccounts.current.accounts
        if (accounts.isEmpty()) return@coroutineScope HealthItem("GitHub", Ok, "None connected", HealthFix.Navigate("github", "Connect"))
        val rejected = accounts.map { a -> async(Dispatchers.IO) { a to keyAccepted(context, a) } }.awaitAll().filter { !it.second }.map { it.first }
        val n = accounts.size
        if (rejected.isEmpty()) HealthItem("GitHub", Ok, "$n connected · keys accepted")
        else HealthItem("GitHub", NeedsAttention, "${rejected.joinToString { it.login }}: key not accepted", HealthFix.Navigate("github"))
    }

    /** `ssh -T git@github.com` with only this account's key: GitHub answers "Hi <login>!" and exits 1. */
    private fun keyAccepted(context: Context, account: GitHubAccountEntry): Boolean {
        if (!account.sshKey.exists()) return false
        val (_, lines) = runCatching {
            GhCli.runTool(
                context, GhCli.bin("ssh"),
                arrayOf(
                    "-T", "-i", account.sshKey.path, "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes",
                    "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=8", "git@github.com",
                ),
            )
        }.getOrElse { return false }
        return lines.any { "successfully authenticated" in it }
    }
}
