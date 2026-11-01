package com.pocketrocket.updates

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** What the Updates screen and its Settings row show. */
data class UpdatesState(
    val rows: List<UpdateRow> = emptyList(),
    /** When the last Check now (every row) ended, epoch millis; 0 never. */
    val checkedAt: Long = 0,
    val checking: Boolean = false,
    /** The update running now, and the ones waiting after it, in order. */
    val current: UpdateComponent? = null,
    val queued: List<UpdateComponent> = emptyList(),
    /** How many updates the current run has finished, for "Updating 2 of 3". */
    val done: Int = 0,
) {
    val available: Int get() = rows.count { it.available }
    val updating: Boolean get() = current != null
    val total: Int get() = done + queued.size + (if (current != null) 1 else 0)
    /** Every row has an answer from a check (cached or this run). */
    val allKnown: Boolean get() = rows.isNotEmpty() && rows.all { it.latest != null || it.installed == null }
}

/**
 * The Updates screen's model (decision 41): the [UpdateSource]s behind one state. Check now asks them
 * all at once; updates run one at a time from a queue, PocketRocket always last because installing it
 * restarts the app. Nothing checks or installs on its own except [onAppOpen]'s check when the last is old.
 */
object Updates {
    private const val PREFS = "pocketrocket"
    private const val KEY_CHECKED_AT = "updates_checked_at"

    val sources: List<UpdateSource> = listOf(AppSource, ClaudeSource, GitSource, DockitoSource, FlutterSource, RustSource) + PackageToolSources

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = MutableStateFlow(UpdatesState())

    private val rows = combine(sources.map { it.row }) { rows -> rows.filter { it.shown } }

    val state: StateFlow<UpdatesState> = rows
        .combine(control) { rows, c -> c.copy(rows = rows) }
        .stateIn(scope, SharingStarted.Eagerly, UpdatesState())

    @Volatile private var loaded = false
    private var runner: kotlinx.coroutines.Job? = null

    /** Installed versions and cached checks, no network; then a check of all when the last is older than [checkAfterMs]. */
    fun onAppOpen(context: Context, checkAfterMs: Long = 6 * 60 * 60 * 1000L) {
        val app = context.applicationContext
        if (!loaded) {
            loaded = true
            val at = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CHECKED_AT, 0)
            control.update { it.copy(checkedAt = at) }
        }
        sources.forEach { it.load(app) }
        if (System.currentTimeMillis() - control.value.checkedAt >= checkAfterMs) checkAll(app)
    }

    /** Check now: asks every source in parallel. Not while updating. */
    fun checkAll(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (control.value.checking || control.value.updating) return
            control.update { it.copy(checking = true) }
        }
        scope.launch {
            sources.map { source -> async { runCatching { source.check(app) } } }.awaitAll()
            val now = System.currentTimeMillis()
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_CHECKED_AT, now).apply()
            control.update { it.copy(checking = false, checkedAt = now) }
        }
    }

    /** Every row as it is now, once a check that's running has ended. */
    suspend fun settledRows(): List<UpdateRow> {
        control.first { !it.checking }
        return rows.first()
    }

    /** Update on one row. Joins the queue when another update is running. */
    fun update(context: Context, component: UpdateComponent) = update(context, listOf(component))

    /** Update all: every row that is out of date, PocketRocket last. */
    fun updateAll(context: Context) = update(context, state.value.rows.filter { it.available }.map { it.component })

    /** The components [updateAll] would run, in order. */
    fun pending(): List<UpdateComponent> = updateOrder(state.value.rows.filter { it.available }.map { it.component })

    /** Runs [components] one at a time after anything already queued. The caller has asked about PocketRocket first. */
    fun update(context: Context, components: List<UpdateComponent>) {
        val app = context.applicationContext
        synchronized(this) {
            control.update { c ->
                val waiting = updateOrder((c.queued + components).distinct().filter { it != c.current })
                c.copy(queued = waiting)
            }
            if (runner?.isActive != true) runner = scope.launch { runQueue(app) }
        }
    }

    private suspend fun runQueue(context: Context) {
        while (true) {
            val next = synchronized(this) {
                val c = control.value
                val head = c.queued.firstOrNull()
                if (head == null) {
                    control.update { it.copy(current = null, done = 0) }
                    null
                } else {
                    control.update { it.copy(current = head, queued = it.queued.drop(1)) }
                    head
                }
            } ?: return
            runCatching { sources.first { it.component == next }.update(context) }
            control.update { it.copy(done = it.done + 1) }
        }
    }
}
