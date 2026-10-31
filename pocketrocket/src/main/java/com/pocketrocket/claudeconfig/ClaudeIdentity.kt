package com.pocketrocket.claudeconfig

import com.pocketrocket.auth.ClaudeAccount
import org.json.JSONObject

/** Who a Claude Code process runs as and which version it is, from its `initialize` reply. Holds no tokens. */
data class ClaudeIdentity(val account: ClaudeAccount?, val version: String?) {
    companion object {
        /**
         * Reads `account.email`, `account.organization`, `account.subscriptionType` and `claude_code_version` only;
         * the token and key source fields beside them are left alone. With no login `account` has none of the three.
         */
        fun fromInitialize(reply: JSONObject): ClaudeIdentity? {
            val account = reply.optJSONObject("account")?.let { o ->
                val email = o.text("email")
                val organization = o.text("organization")
                val subscription = o.text("subscriptionType") ?: o.text("subscription")
                if (email == null && organization == null && subscription == null) null
                else ClaudeAccount(email, organization, subscription)
            }
            val version = reply.text("claude_code_version")
            return if (account == null && version == null) null else ClaudeIdentity(account, version)
        }

        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    }
}
