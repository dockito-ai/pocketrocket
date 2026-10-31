package com.pocketrocket.git

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pure parsers for `gh pr` JSON and `gh pr diff`, unit-tested on the JVM (org.json). */
object PrParsers {

    /** `gh pr list --json` fields for [list]. */
    const val LIST_FIELDS = "number,title,author,headRefName,baseRefName,isDraft,reviewDecision,statusCheckRollup,updatedAt,reviewRequests,state,latestReviews"

    /** `gh pr view --json` fields for [detail]. */
    const val VIEW_FIELDS = "$LIST_FIELDS,body,createdAt,additions,deletions,changedFiles,comments,commits,url"

    fun list(json: String): List<PullRequest> {
        val array = JSONArray(json)
        return (0 until array.length()).map { pr(array.getJSONObject(it)) }
    }

    fun pr(o: JSONObject) = PullRequest(
        number = o.optInt("number"),
        title = o.optString("title"),
        author = login(o.optJSONObject("author")),
        headRef = o.optString("headRefName"),
        baseRef = o.optString("baseRefName"),
        isDraft = o.optBoolean("isDraft"),
        state = o.optString("state", "OPEN"),
        reviewDecision = o.optString("reviewDecision"),
        checks = checks(o.optJSONArray("statusCheckRollup")),
        updated = seconds(o.optString("updatedAt")) ?: 0L,
        reviewRequests = o.optJSONArray("reviewRequests").objects().map { r -> r.optString("login").ifEmpty { r.optString("name") } }.filter { it.isNotEmpty() },
        reviews = o.optJSONArray("latestReviews").objects().map { r -> PrReview(login(r.optJSONObject("author")), r.optString("state")) },
    )

    fun detail(json: String): PrDetail {
        val o = JSONObject(json)
        return PrDetail(
            pr = pr(o),
            body = o.optString("body"),
            created = seconds(o.optString("createdAt")) ?: 0L,
            additions = o.optInt("additions"),
            deletions = o.optInt("deletions"),
            changedFiles = o.optInt("changedFiles"),
            comments = o.optJSONArray("comments")?.length() ?: 0,
            commits = o.optJSONArray("commits").objects().map { c ->
                val sha = c.optString("oid")
                val author = c.optJSONArray("authors").objects().firstOrNull()?.let { a -> a.optString("name").ifEmpty { a.optString("login") } }.orEmpty()
                Commit(sha, sha.take(7), author, seconds(c.optString("committedDate")) ?: 0L, c.optString("messageHeadline"))
            },
            url = o.optString("url"),
        )
    }

    fun mergeMethods(json: String): MergeMethods {
        val o = JSONObject(json)
        return MergeMethods(o.optBoolean("squashMergeAllowed", true), o.optBoolean("mergeCommitAllowed", true), o.optBoolean("rebaseMergeAllowed", true))
    }

    /**
     * `statusCheckRollup`: CheckRuns (status, conclusion, workflowName / name, start and end) and StatusContexts
     * (context, state). Neutral counts as passing, skipped as neither.
     */
    fun checks(array: JSONArray?): List<PrCheck> = array.objects().map { o ->
        if (o.optString("__typename") == "StatusContext") {
            val state = when (o.optString("state")) {
                "SUCCESS" -> CheckState.Passing
                "FAILURE", "ERROR" -> CheckState.Failing
                else -> CheckState.Running
            }
            PrCheck(o.optString("context"), state, null)
        } else {
            val done = o.optString("status") == "COMPLETED"
            val state = when {
                !done -> CheckState.Running
                o.optString("conclusion") in setOf("SUCCESS", "NEUTRAL") -> CheckState.Passing
                o.optString("conclusion") == "SKIPPED" -> CheckState.Skipped
                else -> CheckState.Failing
            }
            val workflow = o.optString("workflowName")
            val name = o.optString("name")
            val start = seconds(o.optString("startedAt"))
            val end = seconds(o.optString("completedAt"))
            PrCheck(
                if (workflow.isNotEmpty() && workflow != name) "$workflow / $name" else name,
                state,
                if (done && start != null && end != null && end >= start) end - start else null,
            )
        }
    }

    /**
     * `gh pr diff`: one unified diff for every file, split per file into [PrFile]s with the kind (A, D, R or M) and
     * line counts, in the diff's order.
     */
    fun files(diff: String): List<PrFile> {
        val chunks = ArrayList<List<String>>()
        var current: MutableList<String>? = null
        for (line in diff.lineSequence()) {
            if (line.startsWith("diff --git ")) current = mutableListOf<String>().also { chunks += it }
            current?.add(line)
        }
        return chunks.map { lines ->
            var path: String? = null
            var original: String? = null
            var kind = 'M'
            for (line in lines) {
                when {
                    line.startsWith("@@") -> break
                    line.startsWith("new file mode") -> kind = 'A'
                    line.startsWith("deleted file mode") -> kind = 'D'
                    line.startsWith("rename from ") -> { kind = 'R'; original = line.removePrefix("rename from ") }
                    line.startsWith("rename to ") -> path = line.removePrefix("rename to ")
                    line.startsWith("+++ b/") -> path = line.removePrefix("+++ b/")
                    line.startsWith("--- a/") && path == null -> path = line.removePrefix("--- a/")
                }
            }
            val header = lines.first().removePrefix("diff --git ")
            val name = path ?: header.substringAfterLast(" b/", header)
            val parsed = GitParsers.diff(lines.joinToString("\n"))
            PrFile(FileChange(name, kind, ChangeArea.Staged, original, parsed.added, parsed.deleted), parsed)
        }
    }

    /** "app/dependabot" is how gh names a GitHub App; show "dependabot". */
    private fun login(author: JSONObject?): String = author?.optString("login").orEmpty().removePrefix("app/")

    /** An ISO time from GitHub ("2026-10-28T02:14:50Z") in seconds, or null (GitHub's year-1 "not yet" included). */
    fun seconds(iso: String?): Long? {
        if (iso.isNullOrEmpty()) return null
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val millis = runCatching { format.parse(iso)?.time }.getOrNull() ?: return null
        return if (millis <= 0) null else millis / 1000
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
