package com.pocketrocket.health

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings › Health (R15, decision 22): runs every check off the main thread and keeps the last report.
 * The quick, local checks (keep-alive, permissions) land first; setup and accounts, which start processes
 * and reach the network, follow a few seconds later. One run at a time.
 */
object Health {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _report = MutableStateFlow<HealthReport?>(null)
    private val _checking = MutableStateFlow(false)
    private var job: Job? = null

    val report: StateFlow<HealthReport?> = _report.asStateFlow()
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Runs all checks again. [ifOlderThanMs] skips the run when the last one is that fresh. */
    @Synchronized
    fun check(context: Context, ifOlderThanMs: Long = 0) {
        if (job?.isActive == true) return
        val last = _report.value
        if (last != null && ifOlderThanMs > 0 && System.currentTimeMillis() - last.checkedAt < ifOlderThanMs) return
        val app = context.applicationContext
        _checking.value = true
        job = scope.launch {
            try {
                val stop = LastStop.read(app)
                val local = listOf(KeepAliveChecks.run(app), PermissionChecks.run(app))
                val pending = (last?.groups ?: emptyList()).filter { g -> local.none { it.title == g.title } }
                _report.value = HealthReport(local + pending, stop, System.currentTimeMillis())
                val setup = async { SetupChecks.run(app) }
                val accounts = async { AccountChecks.run(app) }
                _report.value = HealthReport(local + setup.await() + accounts.await(), stop, System.currentTimeMillis())
            } finally {
                _checking.value = false
            }
        }
    }

    /** Re-reads only the local checks (after coming back from a system screen); cheap, no processes. */
    fun refreshLocal(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val local = listOf(KeepAliveChecks.run(app), PermissionChecks.run(app))
            val rest = _report.value?.groups?.filter { g -> local.none { it.title == g.title } } ?: return@launch
            _report.value = _report.value?.copy(groups = local + rest)
        }
    }
}
