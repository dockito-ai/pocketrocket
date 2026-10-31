package com.pocketrocket.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrParsersTest {

    /** Shaped like `gh pr list --json [PrParsers.LIST_FIELDS]` (gh 2.103). */
    private val list = """
        [{"author":{"is_bot":true,"login":"app/dependabot"},"baseRefName":"main","headRefName":"chore/kotlin","isDraft":false,
          "latestReviews":[],"number":40,"reviewDecision":"REVIEW_REQUIRED",
          "reviewRequests":[{"__typename":"User","login":"ShankarKakumani"},{"__typename":"Team","name":"android"}],
          "state":"OPEN","statusCheckRollup":[
            {"__typename":"CheckRun","completedAt":"2026-10-28T02:14:50Z","conclusion":"SUCCESS","name":"assembleDebug","startedAt":"2026-10-28T02:11:38Z","status":"COMPLETED","workflowName":"build"},
            {"__typename":"CheckRun","completedAt":"0001-01-01T00:00:00Z","conclusion":"","name":"API 36","startedAt":"2026-10-28T02:12:00Z","status":"IN_PROGRESS","workflowName":"instrumented"},
            {"__typename":"StatusContext","context":"ci/legacy","state":"PENDING","startedAt":"2026-10-28T02:12:00Z"}],
          "title":"Bump Kotlin to 2.1","updatedAt":"2026-10-28T02:20:00Z"},
         {"author":{"login":"ShankarKakumani"},"baseRefName":"main","headRefName":"feat/attachments","isDraft":true,
          "latestReviews":[{"author":{"login":"alice"},"state":"APPROVED"},{"author":{"login":"bob"},"state":"COMMENTED"}],
          "number":42,"reviewDecision":"APPROVED","reviewRequests":[],"state":"OPEN",
          "statusCheckRollup":[
            {"__typename":"CheckRun","completedAt":"2026-10-28T03:00:00Z","conclusion":"FAILURE","name":"unit tests","startedAt":"2026-10-28T02:59:00Z","status":"COMPLETED","workflowName":"unit tests"},
            {"__typename":"CheckRun","completedAt":"2026-10-28T03:00:00Z","conclusion":"SKIPPED","name":"deploy","startedAt":"2026-10-28T03:00:00Z","status":"COMPLETED","workflowName":"cd"}],
          "title":"Attachments in the chat","updatedAt":"2026-10-28T04:00:00Z"}]
    """.trimIndent()

    @Test
    fun listOfPullRequests() {
        val prs = PrParsers.list(list)
        assertEquals(listOf(40, 42), prs.map { it.number })
        val kotlin = prs[0]
        assertEquals("dependabot", kotlin.author)
        assertEquals("chore/kotlin", kotlin.headRef)
        assertEquals("main", kotlin.baseRef)
        assertTrue(kotlin.isOpen)
        assertFalse(kotlin.isDraft)
        assertEquals(listOf("ShankarKakumani", "android"), kotlin.reviewRequests)
        assertTrue(kotlin.requested("shankarkakumani"))
        assertFalse(kotlin.requested(null))
        assertEquals(1793154000L, kotlin.updated)

        val mine = prs[1]
        assertTrue(mine.isDraft)
        assertTrue(mine.byMe("ShankarKakumani"))
        assertEquals(1, mine.approvals)
        assertEquals(listOf(PrReview("alice", "APPROVED"), PrReview("bob", "COMMENTED")), mine.reviews)
    }

    @Test
    fun checks() {
        val prs = PrParsers.list(list)
        val checks = prs[0].checks
        assertEquals(listOf("build / assembleDebug", "instrumented / API 36", "ci/legacy"), checks.map { it.name })
        assertEquals(listOf(CheckState.Passing, CheckState.Running, CheckState.Running), checks.map { it.state })
        assertEquals(192L, checks[0].seconds)
        assertNull(checks[1].seconds)
        assertEquals(ChecksSummary(passing = 1, running = 2, failing = 0, total = 3), ChecksSummary.of(checks))

        val failing = prs[1].checks
        // Same workflow and job name: shown once.
        assertEquals("unit tests", failing[0].name)
        assertEquals(CheckState.Failing, failing[0].state)
        assertEquals(CheckState.Skipped, failing[1].state)
        assertEquals(ChecksSummary(passing = 0, running = 0, failing = 1, total = 1), ChecksSummary.of(failing))
    }

    @Test
    fun emptyAndMissingFields() {
        assertTrue(PrParsers.list("[]").isEmpty())
        val pr = PrParsers.list("""[{"number":7,"headRefName":"x"}]""").single()
        assertEquals(7, pr.number)
        assertEquals("OPEN", pr.state)
        assertTrue(pr.checks.isEmpty())
        assertEquals("", pr.author)
        assertEquals(0L, pr.updated)
    }

    @Test
    fun detail() {
        val json = """
            {"number":40,"title":"Bump","state":"MERGED","author":{"login":"app/dependabot"},"headRefName":"chore/kotlin","baseRefName":"main",
             "body":"Bumps Kotlin.","createdAt":"2026-10-27T02:00:00Z","additions":18,"deletions":14,"changedFiles":4,
             "comments":[{"body":"a"},{"body":"b"}],"url":"https://github.com/o/r/pull/40",
             "commits":[{"oid":"7d0b14aa","messageHeadline":"Bump Kotlin","committedDate":"2026-10-27T02:00:00Z","authors":[{"login":"dependabot","name":"dependabot[bot]"}]}]}
        """.trimIndent()
        val d = PrParsers.detail(json)
        assertEquals("MERGED", d.pr.state)
        assertFalse(d.pr.isOpen)
        assertEquals("Bumps Kotlin.", d.body)
        assertEquals(18, d.additions)
        assertEquals(14, d.deletions)
        assertEquals(4, d.changedFiles)
        assertEquals(2, d.comments)
        assertEquals("https://github.com/o/r/pull/40", d.url)
        assertEquals(Commit("7d0b14aa", "7d0b14a", "dependabot[bot]", 1793066400L, "Bump Kotlin"), d.commits.single())
    }

    @Test
    fun mergeMethods() {
        assertEquals(MergeMethods(squash = true, merge = false, rebase = true),
            PrParsers.mergeMethods("""{"mergeCommitAllowed":false,"rebaseMergeAllowed":true,"squashMergeAllowed":true}"""))
    }

    @Test
    fun diffSplitPerFile() {
        val diff = """
            diff --git a/src/A.kt b/src/A.kt
            index 1..2 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
            diff --git a/docs/new.md b/docs/new.md
            new file mode 100644
            --- /dev/null
            +++ b/docs/new.md
            @@ -0,0 +1,2 @@
            +one
            +two
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            --- a/gone.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            diff --git a/old name.kt b/new name.kt
            similarity index 100%
            rename from old name.kt
            rename to new name.kt
        """.trimIndent()
        val files = PrParsers.files(diff)
        assertEquals(listOf("src/A.kt", "docs/new.md", "gone.txt", "new name.kt"), files.map { it.change.path })
        assertEquals(listOf('M', 'A', 'D', 'R'), files.map { it.change.kind })
        assertEquals(1, files[0].change.added)
        assertEquals(1, files[0].change.deleted)
        assertEquals(2, files[1].change.added)
        assertEquals(1, files[2].change.deleted)
        assertEquals("old name.kt", files[3].change.originalPath)
        assertTrue(files[3].diff.rows.isEmpty())
        assertEquals(4, files[0].diff.rows.size) // the hunk header and three lines
    }

    @Test
    fun isoTimes() {
        assertEquals(1793154000L, PrParsers.seconds("2026-10-28T02:20:00Z"))
        assertNull(PrParsers.seconds("0001-01-01T00:00:00Z"))
        assertNull(PrParsers.seconds(""))
        assertNull(PrParsers.seconds("not a time"))
    }
}
