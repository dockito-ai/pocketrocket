package com.pocketrocket.usage

import android.app.ActivityManager
import android.content.Context
import com.pocketrocket.keepalive.SessionKeepAlive

/** Memory of a group of processes. */
data class RamGroup(val kb: Long, val processes: Int)

/** One live session's memory, its child tools included. */
data class SessionRam(val id: String, val title: String, val ram: RamGroup)

/**
 * The app's memory right now. [rssProcesses] counts the processes whose PSS couldn't be read and were
 * measured by RSS, which counts shared memory in full and so reads high.
 */
data class RamReport(
    val sessions: List<SessionRam>,
    val app: RamGroup,
    val other: RamGroup,
    val rssProcesses: Int,
    val cpuMs: Long,
    /** The whole phone's memory as Android reports it, not just the app's. Null if Android won't say. */
    val phone: PhoneShare? = null,
) {
    val totalKb: Long get() = sessions.sumOf { it.ram.kb } + app.kb + other.kb
}

/** Reads memory and CPU time of the app's live processes. One read per call: nothing samples in the background. */
object RamUsage {

    fun read(context: Context): RamReport {
        val procs = ProcessTable.read()
        val running = SessionKeepAlive.liveSessions()
        val owners = SessionTrees.attribute(procs, running.associate { it.id to it.conversationId })
        val pss = procs.associate { it.pid to ProcessTable.pssKb(it.pid) }
        fun kb(p: Proc) = pss[p.pid] ?: p.rssKb
        fun group(list: List<Proc>) = RamGroup(list.sumOf(::kb), list.size)

        val (inSessions, rest) = procs.partition { it.pid in owners }
        val (app, other) = rest.partition { isAppProcess(it, context.packageName) }
        return RamReport(
            sessions = running.map { s -> SessionRam(s.id, s.title, group(inSessions.filter { owners[it.pid] == s.id })) },
            app = group(app),
            other = group(other),
            rssProcesses = procs.count { pss[it.pid] == null && it.rssKb > 0 },
            cpuMs = procs.sumOf { ProcessTable.cpuMs(it.pid) ?: 0L },
            phone = phone(context),
        )
    }

    /** Needs no permission. The total reads a little under the advertised RAM; shown as Android reports it. */
    private fun phone(context: Context): PhoneShare? = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        PhoneShare(info.totalMem, info.totalMem - info.availMem)
    }.getOrNull()

    /** The app's own Android processes are named after the package, with `:name` for extra ones. */
    internal fun isAppProcess(proc: Proc, packageName: String): Boolean =
        proc.cmdline == packageName || proc.cmdline.startsWith("$packageName:")
}
