package com.pocketrocket.sessions

import org.json.JSONObject

/** A one-line description of a tool call from its input, for tool cards and permission prompts. */
object ToolSummary {

    private val keys = listOf("command", "file_path", "notebook_path", "pattern", "url", "query", "description", "prompt", "path")

    fun of(name: String, input: JSONObject): String {
        val key = keys.firstOrNull { input.optString(it).isNotBlank() } ?: return name
        val value = input.optString(key)
        val short = if (key.endsWith("path")) value.substringAfterLast('/') else value
        return short.lineSequence().first().take(160)
    }
}
