package com.pocketrocket.keepalive

import android.app.Notification
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.pocketrocket.notifications.SessionNotifier
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.runningWork

/**
 * What Termux's service notification shows while chat sessions are open: a headline for the most urgent
 * session ("pocketrocket needs you"), a coloured symbol and count per state (a session whose turn ended while subagents
 * or background shells still run counts as ⧗ in progress, decision 79) ("✋ 1  ⚠ 1  ⧗ 2  ✓ 1", in urgency order and tab-dot colours),
 * one line per session when expanded, a clock since sessions started working, and an Open button for the
 * session that needs a look. Colours are the app's status colours (ui/theme/StatusColors).
 */
internal object KeepAliveNotification {

    /** How a session is counted here, most urgent first; [symbol] tags its count: ✋ needs you, ⚠ error, ⧗ in progress, ✓ done. Text-style symbols, so they take the state colour. */
    enum class Group(private val one: String, private val many: String, val symbol: String, val color: Int) {
        NeedsYou("needs you", "need you", "\u270B\uFE0E", 0xFFF5B83D.toInt()),
        Error("error", "errors", "\u26A0\uFE0E", 0xFFFF6B6B.toInt()),
        Working("working", "working", "\u29D7", 0xFF7AB4FF.toInt()),
        Done("done", "done", "\u2713", 0xFF5BD69A.toInt());

        fun words(count: Int) = if (count == 1) one else many
    }

    /** PocketRocket's orange, used when nothing needs a look. */
    private const val ACCENT = 0xFFFF8A3D.toInt()

    /** At most this many sessions get their own line; the rest are "+N more". */
    private const val MAX_LINES = 6

    fun groupOf(status: SessionStatus): Group? = when (status) {
        SessionStatus.WaitingForPermission -> Group.NeedsYou
        SessionStatus.Error -> Group.Error
        SessionStatus.Starting, SessionStatus.Working -> Group.Working
        SessionStatus.Done, SessionStatus.WaitingForInput -> Group.Done
        SessionStatus.Paused -> null
    }

    /** Sessions worth showing, most urgent first: every running one, and ones that stopped on an error. */
    fun shown(sessions: List<SessionKeepAlive.Live>): List<SessionKeepAlive.Live> =
        sessions.filter { !it.demo && groupOf(it.shown) != null && (it.alive || it.status == SessionStatus.Error) }
            .sortedBy { groupOf(it.shown)!!.ordinal }

    /** "pocketrocket needs you", "2 sessions need you", "blog hit an error", "3 sessions working", "All done". */
    fun headline(shown: List<SessionKeepAlive.Live>): String {
        val top = shown.firstOrNull() ?: return "No sessions running"
        val group = groupOf(top.shown)!!
        val count = shown.count { groupOf(it.shown) == group }
        if (count == 1 && group != Group.Done) return when (group) {
            Group.NeedsYou -> "${top.title} needs you"
            Group.Error -> "${top.title} hit an error"
            else -> "${top.title} is working"
        }
        return when (group) {
            Group.NeedsYou -> "$count sessions need you"
            Group.Error -> "$count sessions hit an error"
            Group.Working -> "$count sessions working"
            Group.Done -> "All done"
        }
    }

    /** Count per state, in urgency order, leaving out empty ones. */
    fun counts(shown: List<SessionKeepAlive.Live>): List<Pair<Group, Int>> =
        Group.entries.map { g -> g to shown.count { groupOf(it.shown) == g } }.filter { it.second > 0 }

    /**
     * Fills in [builder] for the chat sessions in [sessions] ([terminals] plain terminals run too). Returns
     * false, leaving Termux's own text and buttons, when no chat session is worth showing.
     */
    fun apply(context: Context, builder: Notification.Builder, sessions: List<SessionKeepAlive.Live>, terminals: Int, busySince: Long): Boolean {
        val shown = shown(sessions)
        if (shown.isEmpty()) return false
        val headline = headline(shown)

        val strip = SpannableStringBuilder()
        counts(shown).forEach { (group, count) ->
            if (strip.isNotEmpty()) strip.append("   ")
            coloured(strip, group, "${group.symbol} $count")
        }
        running(shown)?.let { strip.append("   ·  ").append(it) }
        if (terminals > 0) strip.append("   ·  $terminals terminal${if (terminals == 1) "" else "s"}")

        val inbox = Notification.InboxStyle().setBigContentTitle(headline)
        shown.take(MAX_LINES).forEach { session ->
            val group = groupOf(session.shown)!!
            val line = SpannableStringBuilder()
            dot(line, group)
            bold(line, session.title)
            line.append("  ").append(detail(session))
            inbox.addLine(line)
        }
        if (shown.size > MAX_LINES) inbox.setSummaryText("+${shown.size - MAX_LINES} more")
        else if (terminals > 0) inbox.setSummaryText("$terminals terminal${if (terminals == 1) "" else "s"}")

        val top = groupOf(shown.first().shown)!!
        builder.setContentTitle(headline)
            .setContentText(strip)
            .setStyle(inbox)
            .setColor(if (top == Group.NeedsYou || top == Group.Error) top.color else ACCENT)

        // A clock since sessions started working; hidden once nothing works or waits.
        if (busySince > 0) builder.setShowWhen(true).setWhen(busySince).setUsesChronometer(true)

        // The session that needs a look opens straight from here.
        if (top == Group.NeedsYou || top == Group.Error) {
            val session = shown.first()
            builder.addAction(Notification.Action.Builder(null, "Open ${session.title}", SessionNotifier.openIntent(context, session.id)).build())
        }
        return true
    }

    /** "1 agent · 2 shells running": subagents and background shells across [shown], for the strip; null when none run. */
    fun running(shown: List<SessionKeepAlive.Live>): String? =
        runningWork(shown.sumOf { it.agents }, shown.sumOf { it.shells }).takeIf { it.isNotEmpty() }?.let { "$it running" }

    /** What a session's line says after its name: "working", or "working · 1 agent · 2 shells" while those run. */
    fun detail(session: SessionKeepAlive.Live): String {
        val words = groupOf(session.shown)?.words(1) ?: ""
        val running = runningWork(session.agents, session.shells)
        return if (running.isEmpty()) words else "$words · $running"
    }

    private fun dot(text: SpannableStringBuilder, group: Group) {
        val start = text.length
        text.append("● ")
        text.setSpan(ForegroundColorSpan(group.color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun coloured(text: SpannableStringBuilder, group: Group, part: String) {
        val start = text.length
        bold(text, part)
        text.setSpan(ForegroundColorSpan(group.color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun bold(text: SpannableStringBuilder, part: String) {
        val start = text.length
        text.append(part)
        text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
