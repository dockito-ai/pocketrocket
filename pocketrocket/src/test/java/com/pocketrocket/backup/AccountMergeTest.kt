package com.pocketrocket.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMergeTest {
    private fun plan(
        backupClaude: List<ClaudeSide> = emptyList(), backupClaudeDefault: String? = null,
        phoneClaude: List<ClaudeSide> = emptyList(), phoneClaudeDefault: String? = null,
        backupGitHub: List<GitHubSide> = emptyList(), backupGitHubDefault: String? = null,
        phoneGitHub: List<GitHubSide> = emptyList(), phoneGitHubDefault: String? = null,
        backupDockito: List<String> = emptyList(), phoneDockito: List<String> = emptyList(),
    ) = AccountMerge.plan(
        backupClaude, backupClaudeDefault, phoneClaude, phoneClaudeDefault,
        backupGitHub, backupGitHubDefault, phoneGitHub, phoneGitHubDefault,
        backupDockito, null, phoneDockito, null,
    )

    private fun claude(id: String, email: String?, org: String? = null) = ClaudeSide(id, id, email, org, email != null)

    @Test fun the_same_person_under_another_id_matches() {
        val p = plan(listOf(claude("work", "a@x.com")), "work", listOf(claude("main", "a@x.com")), "main")
        assertEquals(MergedAccount("work", "main", "a@x.com", MergeKind.Matched), p.claude.single())
        assertEquals("main", p.claudeDefault)
    }

    @Test fun same_id_different_person_is_added_under_a_free_id() {
        val p = plan(listOf(claude("main", "a@x.com")), "main", listOf(claude("main", "b@x.com")), "main")
        val a = p.claude.single()
        assertEquals(MergeKind.Added, a.kind)
        assertEquals("main-2", a.toId)
        assertEquals("main", p.claudeDefault)
    }

    @Test fun swapped_ids_land_on_their_own_people() {
        val p = plan(
            listOf(claude("main", "a@x.com"), claude("work", "b@x.com")), "main",
            listOf(claude("main", "b@x.com"), claude("work", "a@x.com")), "main",
        )
        assertEquals("work", p.claudeId("main"))
        assertEquals("main", p.claudeId("work"))
        assertTrue(p.claude.all { it.kind == MergeKind.Matched })
    }

    @Test fun same_email_in_another_organization_is_someone_else() {
        val p = plan(listOf(claude("main", "a@x.com", "org1")), "main", listOf(claude("main", "a@x.com", "org2")), "main")
        assertEquals(MergeKind.Added, p.claude.single().kind)
    }

    @Test fun no_identity_is_never_matched_and_both_are_kept() {
        val noWho = ClaudeSide("main", "main", null, null, signedIn = true)
        val p = plan(listOf(noWho), "main", listOf(claude("main", "a@x.com")), "main")
        val a = p.claude.single()
        assertEquals(MergeKind.Added, a.kind)
        assertEquals("main-2", a.toId)
        assertTrue(a.noIdentity)
    }

    @Test fun a_signed_out_phone_account_of_the_same_id_is_filled_and_the_backup_default_used() {
        val p = plan(
            listOf(claude("main", "a@x.com"), claude("work", "b@x.com")), "work",
            listOf(ClaudeSide("main", "personal", null, null, false)), "main",
        )
        assertEquals(MergeKind.Filled, p.claude[0].kind)
        assertEquals("main", p.claude[0].toId)
        assertEquals(MergeKind.Added, p.claude[1].kind)
        assertEquals("work", p.claudeDefault)
    }

    @Test fun a_signed_in_phone_account_without_an_email_is_not_overwritten() {
        val phone = ClaudeSide("main", "personal", null, null, signedIn = true)
        val p = plan(listOf(claude("main", "a@x.com")), "main", listOf(phone), "main")
        assertEquals(MergeKind.Added, p.claude.single().kind)
        assertEquals("main", p.claudeDefault)
    }

    @Test fun github_matches_by_login_ignoring_case() {
        val p = plan(
            backupGitHub = listOf(GitHubSide("old-id", "SamDev"), GitHubSide("other", "other")), backupGitHubDefault = "other",
            phoneGitHub = listOf(GitHubSide("samdev", "samdev")), phoneGitHubDefault = "samdev",
        )
        assertEquals(MergedAccount("old-id", "samdev", "SamDev", MergeKind.Matched), p.github[0])
        assertEquals(MergeKind.Added, p.github[1].kind)
        assertEquals("samdev", p.githubDefault)
    }

    @Test fun an_empty_phone_takes_the_backups_default() {
        val p = plan(backupGitHub = listOf(GitHubSide("a", "a"), GitHubSide("b", "b")), backupGitHubDefault = "b")
        assertEquals("b", p.githubDefault)
    }

    @Test fun dockito_matches_by_email() {
        val p = plan(backupDockito = listOf("A@x.com", "b@x.com"), phoneDockito = listOf("a@x.com"))
        assertEquals(MergeKind.Matched, p.dockito[0].kind)
        assertEquals(MergeKind.Added, p.dockito[1].kind)
    }

    private fun b(folder: String, claude: String? = null, github: String? = null, dockito: String? = null) = AccountMerge.Binding(folder, claude, github, dockito)

    @Test fun bindings_point_at_the_surviving_ids_and_the_phones_stay() {
        val p = plan(
            listOf(claude("work", "a@x.com")), "work", listOf(claude("main", "a@x.com")), "main",
            listOf(GitHubSide("old", "sam")), "old", listOf(GitHubSide("sam", "sam")), "sam",
        )
        val merged = AccountMerge.bindings(p, listOf(b("/w/a", "work", "old")), listOf(b("/w/phone", "main"), b("/w/a", "main")), emptyList())
        assertEquals(listOf(b("/w/a", "main", "sam"), b("/w/phone", "main")), merged)
    }

    @Test fun a_folder_following_the_default_is_pinned_when_the_default_moved() {
        // Backup's default was a@x, kept on the phone as a separate account while b@x stays the phone's default.
        val p = plan(listOf(claude("main", "a@x.com")), "main", listOf(claude("main", "b@x.com")), "main")
        val merged = AccountMerge.bindings(p, listOf(b("/w/a")), emptyList(), listOf("/w/a", "/w/unbound"))
        assertEquals(listOf(b("/w/a", "main-2"), b("/w/unbound", "main-2")), merged)
    }

    @Test fun a_folder_following_the_default_stays_following_when_nothing_moved() {
        val p = plan(listOf(claude("main", "a@x.com")), "main", listOf(claude("main", "a@x.com")), "main")
        val merged = AccountMerge.bindings(p, listOf(b("/w/a")), emptyList(), listOf("/w/a", "/w/unbound"))
        assertEquals(listOf(b("/w/a")), merged)
        assertFalse(merged.any { it.folder == "/w/unbound" })
    }
}
