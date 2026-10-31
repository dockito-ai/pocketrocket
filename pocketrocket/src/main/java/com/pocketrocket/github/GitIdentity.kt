package com.pocketrocket.github

import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.GitHubAccountEntry
import com.pocketrocket.profiles.writeAtomically

/**
 * A GitHub account's git config file: commit identity plus a core.sshCommand that uses only that
 * account's key. ~/.gitconfig includes it for the folders bound to the account (see AccountWiring), so
 * every git command there, from the app, Claude or a terminal, commits and pushes as that account.
 */
object GitIdentity {
    /** GitHub's published ed25519 host key (checked against ssh-keyscan, 29 Oct 2026). */
    private const val GITHUB_HOST_KEY =
        "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"

    /** The noreply address GitHub uses for web commits: `<id>+<login>@users.noreply.github.com`. */
    fun noreplyEmail(userId: Long, login: String) = "$userId+$login@users.noreply.github.com"

    /** The ssh command git runs for this account. */
    fun sshCommand(entry: GitHubAccountEntry) =
        "ssh -i ${entry.sshKey.path} -o IdentitiesOnly=yes -o UserKnownHostsFile=${AccountPaths.githubKnownHosts.path}"

    fun write(entry: GitHubAccountEntry) {
        writeKnownHosts()
        val text = buildString {
            append("# Written by PocketRocket for GitHub account ${entry.login}. Change it in Settings > GitHub.\n")
            append("[user]\n")
            append("\tname = ${quote(entry.userName.ifBlank { entry.login })}\n")
            append("\temail = ${quote(entry.userEmail)}\n")
            append("[core]\n")
            append("\tsshCommand = ${quote(sshCommand(entry))}\n")
        }
        writeAtomically(entry.gitConfig, text)
    }

    private fun writeKnownHosts() {
        val file = AccountPaths.githubKnownHosts
        if (file.exists() && file.readText().contains(GITHUB_HOST_KEY)) return
        writeAtomically(file, GITHUB_HOST_KEY + "\n")
    }

    /** A git config value in double quotes, with backslashes and quotes escaped. */
    internal fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
