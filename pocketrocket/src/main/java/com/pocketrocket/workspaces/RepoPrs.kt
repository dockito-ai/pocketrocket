package com.pocketrocket.workspaces

import android.content.Context
import com.pocketrocket.git.ChecksSummary
import com.pocketrocket.git.PrFilter
import com.pocketrocket.git.PullRequest
import com.pocketrocket.git.PullRequests
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A repo card's PR line (BranchesPRs.html §1): open PRs, how many wait for the account's review, and the PR of the
 * checked-out branch ([branchPr], [mine] when the account opened it).
 */
data class RepoPrSummary(val open: Int, val toReview: Int, val branchPr: PullRequest?, val mine: Boolean)

/**
 * PR counts for the workspace's repo cards, loaded lazily through gh and kept for [TTL_MS] so the Workspace screen
 * opens at once and does not ask GitHub on every visit. Any gh error (offline, no login, not GitHub) gives null,
 * and the card shows no PR line.
 */
object RepoPrs {
    private const val TTL_MS = 2 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Pair<Long, RepoPrSummary?>>()

    /** The last summary read for [dir], however old, or null. */
    fun cached(dir: File): RepoPrSummary? = cache[dir.path]?.second

    suspend fun load(context: Context, dir: File, branch: String): RepoPrSummary? {
        val now = System.currentTimeMillis()
        cache[dir.path]?.let { (at, value) -> if (now - at < TTL_MS && (value == null || value.branchPr == null || value.branchPr.headRef == branch)) return value }
        val prs = PullRequests(context, dir)
        val summary = runCatching {
            if (prs.unavailable() != null) null
            else coroutineScope {
                val open = async { prs.list(PrFilter.Open, limit = 100, fields = "number,author,headRefName,statusCheckRollup") }
                val review = async { prs.list(PrFilter.ToReview, limit = 100, fields = "number") }
                val list = open.await()
                val pr = list.firstOrNull { it.headRef == branch }
                RepoPrSummary(list.size, review.await().size, pr, pr?.byMe(prs.me) == true)
            }
        }.getOrNull()
        cache[dir.path] = now to summary
        return summary
    }

    /** "checks passing", "checks running", "1 check failing", or null when the PR has no checks. */
    fun checksPhrase(pr: PullRequest): String? {
        val s = ChecksSummary.of(pr.checks)
        return when {
            s.total == 0 -> null
            s.failing > 0 -> if (s.failing == 1) "1 check failing" else "${s.failing} checks failing"
            s.running > 0 -> "checks running"
            else -> "checks passing"
        }
    }
}
