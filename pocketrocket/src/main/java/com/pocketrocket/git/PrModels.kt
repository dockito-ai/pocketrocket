package com.pocketrocket.git

/** Where one check of a PR stands. */
enum class CheckState { Passing, Running, Failing, Skipped }

/** One check run or commit status of a PR: "build / assembleDebug", and how long it took once done. */
data class PrCheck(val name: String, val state: CheckState, val seconds: Long?)

/** The checks of a PR counted up, for "✓ 4/4 checks", "● 2 running", "✗ 1 failing". */
data class ChecksSummary(val passing: Int, val running: Int, val failing: Int, val total: Int) {
    companion object {
        fun of(checks: List<PrCheck>) = ChecksSummary(
            passing = checks.count { it.state == CheckState.Passing },
            running = checks.count { it.state == CheckState.Running },
            failing = checks.count { it.state == CheckState.Failing },
            total = checks.count { it.state != CheckState.Skipped },
        )
    }
}

/** A reviewer's latest review: [state] is GitHub's APPROVED, CHANGES_REQUESTED, COMMENTED or DISMISSED. */
data class PrReview(val author: String, val state: String)

/**
 * A pull request as `gh pr list --json` gives it. [author] is the login ("dependabot" for app/dependabot).
 * [state] is OPEN, CLOSED or MERGED; [reviewDecision] APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED or empty.
 * [updated] is in seconds since the epoch; [reviewRequests] are logins (or team names).
 */
data class PullRequest(
    val number: Int,
    val title: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val isDraft: Boolean,
    val state: String,
    val reviewDecision: String,
    val checks: List<PrCheck>,
    val updated: Long,
    val reviewRequests: List<String>,
    val reviews: List<PrReview>,
) {
    val isOpen: Boolean get() = state == "OPEN"
    val approvals: Int get() = reviews.count { it.state == "APPROVED" }

    /** True when [login] is asked to review it. */
    fun requested(login: String?) = login != null && reviewRequests.any { it.equals(login, ignoreCase = true) }

    fun byMe(login: String?) = login != null && author.equals(login, ignoreCase = true)
}

/** Everything the PR screen shows, from `gh pr view --json`. Times in seconds since the epoch. */
data class PrDetail(
    val pr: PullRequest,
    val body: String,
    val created: Long,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val comments: Int,
    val commits: List<Commit>,
    val url: String,
)

/** One file of a PR's diff: its change (kind and counts) and its rows for the diff viewer. */
data class PrFile(val change: FileChange, val diff: Diff)

/** The merge methods a repo allows (`gh repo view --json squashMergeAllowed,...`). */
data class MergeMethods(val squash: Boolean, val merge: Boolean, val rebase: Boolean)

/** How a PR is merged; [flag] is gh's. */
enum class MergeMethod(val flag: String) { Squash("--squash"), Merge("--merge"), Rebase("--rebase") }

/** The PR list's filters (BranchesPRs.html §2) and their `gh pr list` arguments. */
enum class PrFilter(val label: String, val args: List<String>) {
    Open("Open", listOf("--state", "open")),
    Mine("Mine", listOf("--state", "open", "--author", "@me")),
    ToReview("To review", listOf("--state", "open", "--search", "review-requested:@me")),
    Closed("Closed", listOf("--state", "closed")),
}
