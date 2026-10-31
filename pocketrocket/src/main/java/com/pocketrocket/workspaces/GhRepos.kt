package com.pocketrocket.workspaces

import android.content.Context
import com.pocketrocket.github.GhCli
import com.pocketrocket.profiles.GitHubAccountEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A repo of a GitHub account, for the clone picker. */
data class GhRepo(val name: String, val owner: String, val description: String, val private: Boolean, val sshUrl: String, val httpsUrl: String)

/**
 * Every repo one account can reach (own, collaborator, and its organizations'), most recently pushed
 * first, with that account's own GH_CONFIG_DIR. `gh repo list` alone only returns repos the account owns.
 */
object GhRepos {
    private const val FIELDS = "{name, owner: .owner.login, description, private, ssh_url, html_url}"

    suspend fun list(context: Context, account: GitHubAccountEntry): Result<List<GhRepo>> = withContext(Dispatchers.IO) {
        runCatching {
            val (exit, lines) = GhCli.run(
                context, account.ghConfigDir,
                "api", "--paginate", "--jq", ".[] | $FIELDS",
                "/user/repos?affiliation=owner,collaborator,organization_member&sort=pushed&per_page=100",
            )
            if (exit != 0) error(lines.lastOrNull { it.isNotBlank() } ?: "gh exited with $exit")
            lines.filter { it.startsWith("{") }.map { line ->
                val o = JSONObject(line)
                GhRepo(
                    name = o.getString("name"),
                    owner = o.optString("owner"),
                    description = o.optString("description").takeUnless { it == "null" }.orEmpty(),
                    private = o.optBoolean("private"),
                    sshUrl = o.optString("ssh_url"),
                    httpsUrl = o.optString("html_url"),
                )
            }
        }
    }
}
