package com.pocketrocket.auth

import android.content.Context
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.profiles.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One plan limit: "5-hour limit" 42%, resetting at [resetsAt] (epoch seconds) or as Claude Code worded it. */
data class UsageWindow(val label: String, val percent: Int, val resetsAt: Long? = null, val resetsText: String? = null, val limited: Boolean = false)

/** An account's plan usage as last seen, and when ([updatedAt], epoch ms). */
data class PlanUsage(val windows: List<UsageWindow>, val updatedAt: Long) {
    val limited: Boolean get() = windows.any { it.limited || it.percent >= 100 }
    val fiveHour: UsageWindow? get() = windows.firstOrNull { it.label == FIVE_HOUR }

    companion object {
        const val FIVE_HOUR = "5-hour limit"
        const val WEEKLY = "Weekly"
    }
}

/**
 * Plan usage per Claude account, only as Claude Code reports it; nothing is estimated. Two sources:
 *
 * - Every session's `rate_limit_event` stream-json line (after each reply) carries
 *   `rate_limit_info.unifiedWindows.{five_hour,seven_day}` with `utilization` (0 to 1) and `resetsAt`.
 *   [observe] keeps the latest per account in ~/.pocketrocket/accounts/usage/<id>.json.
 * - [refresh] runs `claude -p /cost` for the account, which prints "Current session: 55% used · resets ..."
 *   and "Current week (all models): 69% used · resets ..." (2.1.312), plus any per-model weekly line.
 */
object ClaudeUsage {
    private val cache = MutableStateFlow<Map<String, PlanUsage>>(emptyMap())
    val all: StateFlow<Map<String, PlanUsage>> = cache.asStateFlow()

    private fun file(accountId: String) = File(AccountPaths.root, "usage/$accountId.json")

    /** The last usage seen for [account], from memory or its file. */
    fun get(account: ClaudeAccountEntry): PlanUsage? =
        cache.value[account.id] ?: load(account.id)?.also { u -> cache.value = cache.value + (account.id to u) }

    /** Called with every stdout line of a session running with CLAUDE_CONFIG_DIR [configDir] (null: ~/.claude). */
    fun observe(configDir: String?, line: String) {
        if (!line.startsWith("{\"type\":\"rate_limit_event\"")) return
        val info = runCatching { JSONObject(line).getJSONObject("rate_limit_info") }.getOrNull() ?: return
        val usage = fromRateLimitInfo(info) ?: return
        val account = ClaudeAccounts.forConfigDir(configDir) ?: return
        store(account.id, usage)
    }

    /** Asks Claude Code for the account's current usage (`claude -p /cost`). Null when it reported none. Blocking. */
    fun refresh(context: Context, account: ClaudeAccountEntry): PlanUsage? {
        val (exit, lines) = ClaudeCli.run(context, arrayOf("-p", "/cost"), account.configDir)
        if (exit != 0) return null
        val usage = fromCostText(lines) ?: return null
        store(account.id, usage)
        return usage
    }

    internal fun fromRateLimitInfo(info: JSONObject, now: Long = System.currentTimeMillis()): PlanUsage? {
        val windows = info.optJSONObject("unifiedWindows") ?: return null
        val list = listOf("five_hour" to PlanUsage.FIVE_HOUR, "seven_day" to PlanUsage.WEEKLY, "seven_day_opus" to "Weekly · Opus")
            .mapNotNull { (key, label) ->
                val w = windows.optJSONObject(key) ?: return@mapNotNull null
                UsageWindow(
                    label = label,
                    percent = (w.optDouble("utilization", 0.0) * 100).toInt().coerceIn(0, 100),
                    resetsAt = w.optLong("resetsAt").takeIf { it > 0 },
                    limited = w.optString("status").let { it.isNotEmpty() && it != "allowed" && it != "allowed_warning" },
                )
            }
        return list.takeIf { it.isNotEmpty() }?.let { PlanUsage(it, now) }
    }

    private val COST_LINE = Regex("^\\s*(.+?):\\s*(\\d+)% used(?:\\s*·\\s*resets (.+))?\\s*$")

    internal fun fromCostText(lines: List<String>, now: Long = System.currentTimeMillis()): PlanUsage? {
        val windows = lines.mapNotNull { COST_LINE.find(it) }.map { m ->
            val (label, percent, resets) = m.destructured
            val resetsAt = parseUtcReset(resets, now)
            UsageWindow(
                label = costLabel(label), percent = percent.toInt().coerceIn(0, 100),
                resetsAt = resetsAt, resetsText = resets.ifBlank { null }.takeIf { resetsAt == null },
            )
        }
        return windows.takeIf { it.isNotEmpty() }?.let { PlanUsage(it, now) }
    }

    private val RESET_UTC = Regex("^(?:([A-Z][a-z]{2}) (\\d{1,2}),? )?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s*\\(UTC\\)$", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /**
     * "Oct 29, 8:19pm (UTC)" or "8:19pm (UTC)" from `/cost` to epoch seconds, so the screen shows it in the
     * phone's time zone. The CLI runs without TZ and words it in UTC; the year is the next one that fits.
     */
    internal fun parseUtcReset(text: String, now: Long = System.currentTimeMillis()): Long? {
        val m = RESET_UTC.find(text.trim()) ?: return null
        val (mon, day, hour, minute, ampm) = m.destructured
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = now }
        if (mon.isNotEmpty()) {
            val month = MONTHS.indexOf(mon.lowercase()).takeIf { it >= 0 } ?: return null
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day.toInt())
        }
        cal.set(Calendar.HOUR_OF_DAY, hour.toInt() % 12 + if (ampm.equals("pm", true)) 12 else 0)
        cal.set(Calendar.MINUTE, minute.ifEmpty { "0" }.toInt())
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // A reset is always ahead; allow a minute of slack for a reset that is happening now.
        if (cal.timeInMillis < now - 60_000) cal.add(if (mon.isNotEmpty()) Calendar.YEAR else Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis / 1000
    }

    private val RESETS_UTC_IN_TEXT = Regex("(?<=resets )((?:[A-Z][a-z]{2} \\d{1,2},? )?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\s*\\(UTC\\))", RegexOption.IGNORE_CASE)

    /**
     * Claude Code's "You've hit your 5-hour limit · resets 8:20pm (UTC)" with the time in the phone's
     * time zone: "resets 1:50am", or "resets Thu 1:50am" when it is not today. Other text is left alone.
     */
    fun localizeUtcResets(text: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        RESETS_UTC_IN_TEXT.replace(text) { m ->
            val at = parseUtcReset(m.value, now) ?: return@replace m.value
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }
            val sameDay = today.format(Date(at * 1000)) == today.format(Date(now))
            SimpleDateFormat(if (sameDay) "h:mma" else "EEE h:mma", Locale.US).apply { timeZone = zone }
                .format(Date(at * 1000)).replace("AM", "am").replace("PM", "pm")
        }

    /** Claude Code's wording to the mockup's: "Current session" is the 5-hour window. */
    private fun costLabel(label: String) = when {
        label.equals("Current session", ignoreCase = true) -> PlanUsage.FIVE_HOUR
        label.startsWith("Current week", ignoreCase = true) -> {
            val model = label.substringAfter('(', "").substringBefore(')').trim()
            if (model.isEmpty() || model.equals("all models", ignoreCase = true)) PlanUsage.WEEKLY else "Weekly · ${model.replaceFirstChar(Char::uppercase)}"
        }
        else -> label
    }

    private fun store(accountId: String, usage: PlanUsage) {
        cache.value = cache.value + (accountId to usage)
        runCatching { writeAtomically(file(accountId), toJson(usage).toString()) }
    }

    private fun toJson(u: PlanUsage) = JSONObject().put("updatedAt", u.updatedAt).put("windows", JSONArray(u.windows.map { w ->
        JSONObject().put("label", w.label).put("percent", w.percent).put("limited", w.limited)
            .put("resetsAt", w.resetsAt ?: JSONObject.NULL).put("resetsText", w.resetsText ?: JSONObject.NULL)
    }))

    private fun load(accountId: String): PlanUsage? = runCatching {
        val o = JSONObject(file(accountId).readText())
        val arr = o.getJSONArray("windows")
        PlanUsage((0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            UsageWindow(
                w.getString("label"), w.getInt("percent"),
                w.optLong("resetsAt").takeIf { !w.isNull("resetsAt") && it > 0 },
                w.optString("resetsText").takeIf { !w.isNull("resetsText") && it.isNotBlank() },
                w.optBoolean("limited"),
            )
        }, o.getLong("updatedAt"))
    }.getOrNull()
}
