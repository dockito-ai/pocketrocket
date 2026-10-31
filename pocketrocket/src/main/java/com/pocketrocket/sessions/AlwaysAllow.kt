package com.pocketrocket.sessions

import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "Always allow" choice on a permission card, built from the `can_use_tool` request's `permission_suggestions`.
 * [updates] go back verbatim as `updatedPermissions` in the allow answer (Agent SDK control protocol); [what] says
 * in plain words what they allow, e.g. "`git status`" or "edits for this session".
 *
 * Only the suggestions named in [what] are sent, so the button never allows more than it says: rule suggestions win;
 * without them, accept-edits mode; without that, extra directories.
 */
data class AlwaysAllow(val what: String, val updates: JSONArray) {

    val label: String get() = "Always allow $what"

    companion object {
        private const val HOME = TermuxConstants.TERMUX_HOME_DIR_PATH

        fun from(suggestions: JSONArray): AlwaysAllow? {
            val all = suggestions.objects()
            val rules = all.filter { it.optString("type") == "addRules" && it.optString("behavior") == "allow" }
            if (rules.isNotEmpty()) {
                val names = rules.flatMap { it.optJSONArray("rules").objects() }.map(::ruleWords).distinct()
                if (names.isNotEmpty()) return AlwaysAllow(names.joinToString(", "), JSONArray(rules))
            }
            all.firstOrNull { it.optString("type") == "setMode" && it.optString("mode") == "acceptEdits" }?.let {
                val scope = if (it.optString("destination") == "session") " for this session" else ""
                return AlwaysAllow("edits$scope", JSONArray().put(it))
            }
            all.firstOrNull { it.optString("type") == "addDirectories" }?.let {
                val dirs = it.optJSONArray("directories").strings().map(::shortPath)
                if (dirs.isNotEmpty()) return AlwaysAllow("access to ${dirs.joinToString(", ")}", JSONArray().put(it))
            }
            return null
        }

        /** `{toolName: Bash, ruleContent: "npm test:*"}` → "`npm test` commands"; no content → the whole tool. */
        private fun ruleWords(rule: JSONObject): String {
            val tool = rule.optString("toolName")
            val content = rule.optString("ruleContent")
            return when {
                content.isBlank() -> tool
                tool == "Bash" && content.endsWith(":*") -> "`${content.removeSuffix(":*")}` commands"
                tool == "Bash" -> "`$content`"
                else -> "$tool on `${shortPath(content)}`"
            }
        }

        private fun shortPath(path: String) = if (path.startsWith(HOME)) "~" + path.removePrefix(HOME) else path

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }
}
