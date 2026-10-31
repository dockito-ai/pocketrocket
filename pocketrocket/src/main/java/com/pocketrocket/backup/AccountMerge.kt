package com.pocketrocket.backup

/** How one backup account lands on the phone. */
enum class MergeKind {
    /** The same person is on the phone: the backup's config comes back, the phone's login stays. */
    Matched,
    /** The phone's account of this id isn't signed in: the backup's config and login take its place. */
    Filled,
    /** Not on the phone: added, with its login. */
    Added,
}

data class MergedAccount(val fromId: String, val toId: String, val label: String, val kind: MergeKind, val noIdentity: Boolean = false)

data class AccountPlan(
    val claude: List<MergedAccount>,
    val github: List<MergedAccount>,
    val dockito: List<MergedAccount>,
    val claudeDefault: String?,
    val githubDefault: String?,
    val dockitoDefault: String?,
    /** The backup's defaults, as ids on the phone after the merge. */
    val backupClaudeDefault: String?,
    val backupGitHubDefault: String?,
    val backupDockitoDefault: String?,
) {
    fun claudeId(id: String?) = claude.firstOrNull { it.fromId == id }?.toId
    fun githubId(id: String?) = github.firstOrNull { it.fromId == id }?.toId
    fun dockitoId(id: String?) = dockito.firstOrNull { it.fromId == id?.lowercase() }?.toId
}

/**
 * Matches a backup's accounts to the phone's by who they are, never by id (restore usually runs over a fresh
 * install that is already signed in). Pure. Claude: same email, and same organization when both sides know it.
 * GitHub: same login. Dockito: same email. An account with no identity is never matched: both are kept.
 */
internal object AccountMerge {

    fun plan(
        backupClaude: List<ClaudeSide>, backupClaudeDefault: String?,
        phoneClaude: List<ClaudeSide>, phoneClaudeDefault: String?,
        backupGitHub: List<GitHubSide>, backupGitHubDefault: String?,
        phoneGitHub: List<GitHubSide>, phoneGitHubDefault: String?,
        backupDockito: List<String>, backupDockitoDefault: String?,
        phoneDockito: List<String>, phoneDockitoDefault: String?,
    ): AccountPlan {
        val claude = planClaude(backupClaude, phoneClaude)
        val github = planGitHub(backupGitHub, phoneGitHub)
        val dockito = backupDockito.map { it.lowercase() }.distinct().map { email ->
            val onPhone = phoneDockito.any { it.equals(email, ignoreCase = true) }
            MergedAccount(email, email, email, if (onPhone) MergeKind.Matched else MergeKind.Added)
        }
        val mapClaude = { id: String? -> claude.firstOrNull { it.fromId == id }?.toId }
        val mapGitHub = { id: String? -> github.firstOrNull { it.fromId == id }?.toId }
        val backupClaudeNow = mapClaude(backupClaudeDefault) ?: claude.firstOrNull()?.toId
        val backupGitHubNow = mapGitHub(backupGitHubDefault) ?: github.firstOrNull()?.toId
        val backupDockitoNow = backupDockitoDefault?.lowercase()?.takeIf { d -> dockito.any { it.toId == d } } ?: dockito.firstOrNull()?.toId
        // The phone's default stays while it means something: a real login there. A fresh, signed-out phone has none.
        val phoneSigned = phoneClaude.filter { it.signedIn }.map { it.id }
        val claudeDefault = phoneClaudeDefault?.takeIf { it in phoneSigned } ?: phoneSigned.firstOrNull() ?: backupClaudeNow ?: phoneClaude.firstOrNull()?.id
        val githubDefault = phoneGitHubDefault?.takeIf { d -> phoneGitHub.any { it.id == d } } ?: phoneGitHub.firstOrNull()?.id ?: backupGitHubNow
        val dockitoDefault = phoneDockitoDefault?.lowercase()?.takeIf { d -> phoneDockito.any { it.equals(d, true) } }
            ?: phoneDockito.firstOrNull()?.lowercase() ?: backupDockitoNow
        return AccountPlan(claude, github, dockito, claudeDefault, githubDefault, dockitoDefault, backupClaudeNow, backupGitHubNow, backupDockitoNow)
    }

    private fun sameClaude(a: ClaudeSide, b: ClaudeSide): Boolean =
        a.email != null && a.email == b.email && (a.org == null || b.org == null || a.org == b.org)

    private fun planClaude(backup: List<ClaudeSide>, phone: List<ClaudeSide>): List<MergedAccount> {
        val taken = mutableSetOf<String>()
        val result = linkedMapOf<String, MergedAccount>()
        // Matches first: a backup account may land on a phone id another backup account also has.
        backup.forEach { b ->
            val p = phone.firstOrNull { it.id !in taken && sameClaude(b, it) } ?: return@forEach
            taken += p.id
            result[b.id] = MergedAccount(b.id, p.id, b.email ?: b.name, MergeKind.Matched)
        }
        backup.filter { it.id !in result }.forEach { b ->
            val p = phone.firstOrNull { it.id == b.id }
            if (p != null && p.id !in taken && !p.signedIn) {
                taken += p.id
                result[b.id] = MergedAccount(b.id, p.id, b.email ?: b.name, MergeKind.Filled)
            }
        }
        val used = taken + phone.map { it.id }
        val added = used.toMutableSet()
        backup.filter { it.id !in result }.forEach { b ->
            val id = freeId(b.id, added)
            added += id
            result[b.id] = MergedAccount(b.id, id, b.email ?: b.name, MergeKind.Added, noIdentity = b.email == null)
        }
        return backup.map { result.getValue(it.id) }
    }

    private fun planGitHub(backup: List<GitHubSide>, phone: List<GitHubSide>): List<MergedAccount> {
        val used = phone.map { it.id }.toMutableSet()
        return backup.map { b ->
            val p = phone.firstOrNull { it.login.equals(b.login, ignoreCase = true) }
            if (p != null) {
                MergedAccount(b.id, p.id, b.login, MergeKind.Matched)
            } else {
                val id = freeId(b.id, used)
                used += id
                MergedAccount(b.id, id, b.login, MergeKind.Added)
            }
        }
    }

    /** [id], or [id] with the first number that makes it free. Never the first Claude account's special id. */
    private fun freeId(id: String, used: Set<String>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) id else "$id-$it" }.first { it !in used }

    /** A folder's account choice, as `bindings.json` holds it: an id, or null to follow the default. */
    data class Binding(val folder: String, val claude: String?, val github: String?, val dockito: String?)

    /**
     * The merged bindings. The backup's come first, pointing at the surviving ids. A folder that followed the
     * default in the backup is pinned to that account when the merged default is someone else, so a workspace
     * doesn't quietly change account. The phone's bindings for other folders stay. [backupWorkspaces] with no
     * binding of their own count as following the default.
     */
    fun bindings(plan: AccountPlan, backup: List<Binding>, phone: List<Binding>, backupWorkspaces: List<String>): List<Binding> {
        val covered = backup.map { it.folder }.toSet()
        val all = backup + backupWorkspaces.filter { it !in covered }.map { Binding(it, null, null, null) }
        fun pin(default: String?, merged: String?) = default.takeIf { it != merged }
        val restored = all.map { b ->
            Binding(
                b.folder,
                b.claude?.let { plan.claudeId(it) ?: it } ?: pin(plan.backupClaudeDefault, plan.claudeDefault),
                b.github?.let { plan.githubId(it) ?: it } ?: pin(plan.backupGitHubDefault, plan.githubDefault),
                b.dockito?.let { plan.dockitoId(it) ?: it } ?: pin(plan.backupDockitoDefault, plan.dockitoDefault),
            )
        }.filterNot { it.claude == null && it.github == null && it.dockito == null && it.folder !in covered }
        val folders = restored.map { it.folder }.toSet()
        return restored + phone.filter { it.folder !in folders }
    }
}
