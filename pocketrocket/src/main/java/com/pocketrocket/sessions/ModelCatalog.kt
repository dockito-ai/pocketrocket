package com.pocketrocket.sessions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The models to offer before a session runs: the list the CLI last reported (saved here by [SessionManager]), else
 * its aliases. "default" is the account's default model and is always first.
 */
object ModelCatalog {
    private const val FILE = "pocketrocket"
    private const val KEY = "claude_models"

    private val fallback = listOf(
        ModelOption("default", "Default", "The account's default model", null),
        ModelOption("opus", "Opus", "", null),
        ModelOption("sonnet", "Sonnet", "", null),
        ModelOption("haiku", "Haiku", "", null),
    )

    fun options(context: Context): List<ModelOption> {
        val saved = runCatching { ModelOption.listFrom(JSONArray(prefs(context).getString(KEY, null) ?: return fallback)) }.getOrNull()
        val list = saved?.takeIf { it.isNotEmpty() } ?: return fallback
        return if (list.any { it.value == "default" }) list.sortedBy { it.value != "default" } else listOf(fallback.first()) + list
    }

    /** What Rocket itself can run on, in Settings and by voice. It needs a real model, so not the account's "default". */
    fun forRocket(context: Context): List<ModelOption> = options(context).filter { it.value != "default" }

    fun remember(context: Context, models: List<ModelOption>) {
        if (models.isEmpty()) return
        val json = JSONArray(models.map {
            JSONObject().put("value", it.value).put("displayName", it.displayName).put("description", it.description).put("resolvedModel", it.resolvedModel ?: "")
        }).toString()
        val prefs = prefs(context)
        if (prefs.getString(KEY, null) != json) prefs.edit().putString(KEY, json).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
