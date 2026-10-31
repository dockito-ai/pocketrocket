package com.pocketrocket.auth

import org.json.JSONObject

/** Who Claude Code is signed in as, read from `claude auth status --json`. Holds no tokens. */
data class ClaudeAccount(
    val email: String?,
    val organization: String?,
    /**
     * Claude Code's plan, or null. `auth status` gives "pro", "max", "team", "enterprise"; a session's `initialize` reply
     * gives it whole, as "Claude Max".
     */
    val subscription: String?,
) {
    /** "Claude Max", "Claude Pro" and so on; null when Claude Code did not report a plan. */
    val planLabel: String?
        get() = subscription?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("Claude ", ignoreCase = true)) it else "Claude " + it.replaceFirstChar(Char::uppercase)
        }

    /**
     * The organization worth showing: a real team or company name. Personal plans get a default
     * "<email>'s Organization", which says nothing, so it is hidden.
     */
    val teamName: String?
        get() = organization?.takeIf { org ->
            org.isNotBlank() && org != email && !(email != null && org.equals("$email's Organization", ignoreCase = true))
        }

    companion object {
        /** Parses the status JSON. Returns null when signed out or when the output is not JSON. */
        fun fromStatusJson(json: String): ClaudeAccount? {
            val obj = runCatching { JSONObject(json.substring(json.indexOf('{'))) }.getOrNull() ?: return null
            if (!obj.optBoolean("loggedIn", false)) return null
            return ClaudeAccount(
                email = obj.stringOrNull("email"),
                organization = obj.stringOrNull("orgName"),
                subscription = obj.stringOrNull("subscriptionType"),
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
