package com.pocketrocket.backup

import org.json.JSONObject
import java.io.File

/**
 * What first-run setup keeps true in a Claude account's settings.json (`write_claude_defaults` in
 * setup/pocketrocket-setup.sh), put back after a restore so a backup can't undo it: PocketRocket's updater owns
 * updates, Termux's ripgrep is used, and no bionic preload leaks into the Bash tool.
 */
internal object ClaudeDefaults {
    /** False when [settings] isn't valid JSON: it is left alone, as setup leaves it. */
    fun apply(settings: File): Boolean {
        val json = try {
            JSONObject(settings.readText())
        } catch (_: Exception) {
            return false
        }
        json.put("autoUpdates", false)
        val env = json.optJSONObject("env") ?: JSONObject()
        env.remove("LD_PRELOAD")
        env.put("USE_BUILTIN_RIPGREP", "0")
        json.put("env", env)
        settings.writeText(json.toString(2) + "\n")
        return true
    }
}
