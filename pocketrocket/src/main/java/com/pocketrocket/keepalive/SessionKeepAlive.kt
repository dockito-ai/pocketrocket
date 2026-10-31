package com.pocketrocket.keepalive

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.pocketrocket.MainActivity
import com.pocketrocket.health.LastStop
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.shownWith
import com.pocketrocket.termux.Termux
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps session processes alive with the app in the background and the screen off, through Termux's own
 * foreground service (TermuxService) rather than a second one. The service asks this object whether chat
 * sessions need it ([isActive]), what its notification should say ([describe]) and whether to hold its
 * CPU wake lock ([wantsWakeLock]); this object tells it when any of that changes ([Host]).
 *
 * Both are held only while some session is working (Starting, Working, or a finished turn with subagents or
 * background shells still running) or waiting for an answer (WaitingForPermission), and let go once every
 * session is idle (ready, Done, Error, Paused). Nothing
 * starts on boot (decision 8).
 */
object SessionKeepAlive {

    /** One session as the service sees it. [alive] means a real Claude Code process runs (never for a demo). */
    data class Live(
        val id: String,
        val conversationId: String,
        val alive: Boolean,
        val status: SessionStatus,
        val title: String = "",
        val demo: Boolean = false,
        /** Shells running in the background: they keep the service on after the turn ends. */
        val shells: Int = 0,
        /** Subagents still at work: like shells, they keep the service on after the turn ends (decision 79). */
        val agents: Int = 0,
    ) {
        /** [status] as shown: a finished turn with agents or shells still running counts as working. */
        val shown: SessionStatus get() = status.shownWith(agents + shells)
    }

    /** TermuxService, while it runs. */
    fun interface Host {
        fun onSessionsChanged()
    }

    /** Statuses that keep the service and the wake lock on. */
    private val BUSY = setOf(SessionStatus.Starting, SessionStatus.Working, SessionStatus.WaitingForPermission)

    @Volatile private var host: Host? = null
    @Volatile private var live: List<Live> = emptyList()
    @Volatile private var wakeLockHeld = false

    /** When sessions last started working or waiting (0 while none do), for the notification's clock. */
    @Volatile private var busySince = 0L

    private val _busyNow = MutableStateFlow(false)

    /** [isActive], to watch: the wake word listens while a session runs (decision 54). */
    val busyNow: StateFlow<Boolean> = _busyNow

    private fun busy(): List<Live> = live.filter { it.alive && (it.shown in BUSY || it.agents + it.shells > 0) }

    @JvmStatic
    fun setHost(service: Host?) {
        host = service
    }

    /** True while any session is working, waiting for an answer or running a subagent or background shell; the service must not stop then. */
    @JvmStatic
    fun isActive(): Boolean = busy().isNotEmpty()

    /**
     * Fills the service notification with the chat sessions' states ([KeepAliveNotification]); [terminals]
     * plain terminals run beside them. False when no chat session is worth showing: the service then keeps
     * Termux's own text and buttons.
     */
    @JvmStatic
    fun describe(context: Context, builder: Notification.Builder, terminals: Int): Boolean =
        KeepAliveNotification.apply(context, builder, live, terminals, busySince)

    /** The CPU stays awake under the same rule as the service: some session working or waiting. */
    @JvmStatic
    fun wantsWakeLock(): Boolean = isActive()

    /** The service reports whether it holds the sessions' wake lock, for Health. */
    @JvmStatic
    fun setWakeLockHeld(held: Boolean) {
        wakeLockHeld = held
    }

    /** True while TermuxService runs (it registers as [Host] in onCreate). */
    fun serviceRunning(): Boolean = host != null

    fun wakeLockHeld(): Boolean = wakeLockHeld

    /** Sessions keeping the service on right now. */
    fun busyCount(): Int = busy().size

    /** Sessions with a Claude Code process, busy or idle. */
    fun aliveCount(): Int = live.count { it.alive }

    /**
     * Tapping the service notification opens PocketRocket's Home, never the terminal. A launcher intent only
     * brings the task forward as it was left, terminal on top; naming MainActivity clears what sits above it.
     */
    @JvmStatic
    fun openAppIntent(context: Context): PendingIntent {
        val home = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSIONS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, home, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The user pressed Exit on the service notification: stop the processes, keep the sessions. */
    @JvmStatic
    fun onExitRequested() = SessionManager.pauseAll()

    internal fun update(context: Context, sessions: List<Live>) {
        live = sessions
        _busyNow.value = isActive()
        busySince = if (!isActive()) 0L else busySince.takeIf { it > 0 } ?: System.currentTimeMillis()
        LastStop.recordRunning(context, sessions.count { it.alive })
        val service = host
        if (service != null) service.onSessionsChanged()
        else if (isActive()) runCatching { Termux.startForegroundService(context) }
    }

    internal fun liveSessions(): List<Live> = live.filter { it.alive }
}
