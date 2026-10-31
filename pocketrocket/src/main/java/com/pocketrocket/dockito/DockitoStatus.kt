package com.pocketrocket.dockito

import org.json.JSONObject

/** A workspace the login can reach, with its access (View, Publish or Everything). */
data class DockitoWorkspace(val slug: String, val name: String, val level: String)

/** Who the Dockito login is, as `dockito whoami --json` reports it. No token, ever. */
data class DockitoAccount(
    val name: String,
    val username: String?,
    val email: String?,
    /** When the login ends if unused, ISO 8601, as the CLI gives it. */
    val expiresAt: String?,
    val workspaces: List<DockitoWorkspace>,
)

sealed interface DockitoStatus {
    data class SignedIn(val account: DockitoAccount) : DockitoStatus
    data object SignedOut : DockitoStatus

    /** A login is saved but the CLI could not say whose (offline, Dockito down). */
    data class Unknown(val message: String) : DockitoStatus

    companion object {
        /**
         * Reads `whoami --json` output. Signed in when it names a user. Otherwise signed out when there is no
         * login, and unknown (say, offline or expired) when there is. Falls back to whoami's text form
         * ("Name username · email" on the first line) for a CLI without --json.
         */
        fun from(exit: Int, output: String, hasLogin: Boolean): DockitoStatus {
            if (exit == 0) parseAccount(output)?.let { return SignedIn(it) }
            if (!hasLogin) return SignedOut
            val json = jsonIn(output)
            val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: output.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: "Dockito did not answer."
            return Unknown(message)
        }

        fun parseAccount(output: String): DockitoAccount? {
            val json = jsonIn(output)
            if (json != null) {
                val user = json.optJSONObject("user") ?: return null
                val name = user.optString("name").ifBlank { user.optString("username") }.ifBlank { return null }
                val list = json.optJSONArray("workspaces")
                val workspaces = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val w = list!!.optJSONObject(i) ?: return@mapNotNull null
                    DockitoWorkspace(w.optString("slug"), w.optString("name").ifBlank { w.optString("slug") }, w.optString("level"))
                }
                return DockitoAccount(
                    name = name,
                    username = user.optString("username").ifBlank { null },
                    email = user.optString("email").ifBlank { null },
                    expiresAt = json.optJSONObject("token")?.optString("expiresAt")?.ifBlank { null },
                    workspaces = workspaces,
                )
            }
            // Text form: "Shankar Kakumani shankarkakumani · shankar@example.com"
            val first = output.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val match = TEXT.matchEntire(first) ?: return null
            return DockitoAccount(match.groupValues[1].trim(), match.groupValues[2], match.groupValues[3], null, emptyList())
        }

        private val TEXT = Regex("""^(.+)\s+(\S+)\s+·\s+(\S+@\S+)$""")

        private fun jsonIn(output: String): JSONObject? {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(output.substring(start, end + 1)) }.getOrNull()
        }
    }
}
