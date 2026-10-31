package com.pocketrocket.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** One line of "will merge": who, and what happens to them. */
data class MergeLine(val who: String, val what: String)

/** What Restore shows before it touches anything. */
data class RestorePreview(val manifest: BackupManifest, val merges: List<MergeLine>, val replacedRepos: List<String>)

internal object RestorePreviewer {
    fun build(manifest: BackupManifest, phoneHome: File): RestorePreview {
        val phone = AccountFiles(phoneHome)
        val plan = AccountMerge.plan(
            manifest.claude, null, phone.claudeAccounts(), phone.defaultOf(phone.claudeList),
            manifest.github, null, phone.githubAccounts(), phone.defaultOf(phone.githubList),
            manifest.dockito, null, phone.dockitoEmails(), phone.defaultOf(phone.dockitoList),
        )
        fun lines(tool: String, list: List<MergedAccount>, matched: String) = list.map {
            MergeLine(
                "${it.label} ($tool)",
                when {
                    it.kind == MergeKind.Matched -> matched
                    it.noIdentity -> "Added as a separate account: it doesn't say who it is, so it can't be matched"
                    else -> "Added, with its login"
                },
            )
        }
        val merges = lines("Claude", plan.claude, "Same account. Its config comes back, this phone's login stays") +
            lines("GitHub", plan.github, "Same account. Commit identity comes back, this phone's login and key stay") +
            lines("Dockito", plan.dockito, "Same account. This phone's login stays")
        val replaced = manifest.repos.filter { name ->
            Files.exists(File(phoneHome.parentFile, name).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        return RestorePreview(manifest, merges, replaced)
    }
}
