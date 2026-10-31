package com.pocketrocket.usage

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/** One of the app's own processes, as /proc describes it. */
internal data class Proc(val pid: Int, val ppid: Int, val rssKb: Long, val cmdline: String)

/** Reads the app's own processes from /proc. Anything else's is skipped, and so is one that exits mid-read. */
internal object ProcessTable {

    fun read(): List<Proc> {
        val me = Process.myUid()
        return File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            runCatching {
                val status = ProcText.status(File(dir, "status").readText())
                if (status.uid != me) return@runCatching null
                Proc(dir.name.toInt(), status.ppid, status.rssKb, File(dir, "cmdline").readText().replace('\u0000', ' ').trim())
            }.getOrNull()
        }
    }

    /** Proportional memory, or null when the kernel won't show it for this process. */
    fun pssKb(pid: Int): Long? = runCatching { ProcText.pssKb(File("/proc/$pid/smaps_rollup").readText()) }.getOrNull()

    /** CPU used by the process and the children it has waited for, or null when it has gone. */
    fun cpuMs(pid: Int): Long? = runCatching {
        ProcText.cpuTicks(File("/proc/$pid/stat").readText())?.let { it * 1000 / clockTicksPerSecond }
    }.getOrNull()

    private val clockTicksPerSecond: Long by lazy { Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L }
}

/** The text formats of /proc files, apart from the reading so they can be tested. */
internal object ProcText {

    class Status(val uid: Int, val ppid: Int, val rssKb: Long)

    fun status(text: String): Status {
        val lines = text.lineSequence().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        return Status(
            uid = lines["Uid"]?.split(WHITESPACE)?.firstOrNull()?.toIntOrNull() ?: -1,
            ppid = lines["PPid"]?.toIntOrNull() ?: 0,
            // Kernel threads and zombies have no VmRSS line.
            rssKb = lines["VmRSS"]?.substringBefore(' ')?.toLongOrNull() ?: 0,
        )
    }

    /** The Pss line of smaps_rollup; Pss_Anon and friends are parts of it. */
    fun pssKb(text: String): Long? =
        text.lineSequence().firstOrNull { it.startsWith("Pss:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

    /** utime + stime + cutime + cstime. The name in brackets may hold spaces, so fields count from the last ')'. */
    fun cpuTicks(stat: String): Long? {
        val fields = stat.substringAfterLast(')').trim().split(WHITESPACE)
        if (fields.size <= CSTIME) return null
        return (UTIME..CSTIME).sumOf { fields[it].toLongOrNull() ?: return null }
    }

    private val WHITESPACE = Regex("\\s+")

    // Positions after the name: state is field 3 of proc(5), so field n is at n - 3.
    private const val UTIME = 14 - 3
    private const val CSTIME = 17 - 3
}

/** Which session each process belongs to: a process whose command line has the conversation id, and its descendants. */
internal object SessionTrees {

    /** pid to session id. [sessions] maps session id to conversation id; a blank conversation id matches nothing. */
    fun attribute(procs: List<Proc>, sessions: Map<String, String>): Map<Int, String> {
        val byPid = procs.associateBy { it.pid }
        val roots = HashMap<Int, String>()
        sessions.forEach { (id, conversation) ->
            if (conversation.isBlank()) return@forEach
            procs.forEach { if (conversation in it.cmdline) roots.putIfAbsent(it.pid, id) }
        }
        val owners = HashMap<Int, String>()
        procs.forEach { proc ->
            var at: Proc? = proc
            var hops = 0
            while (at != null && hops++ < MAX_DEPTH) {
                val owner = roots[at.pid]
                if (owner != null) {
                    owners[proc.pid] = owner
                    break
                }
                at = byPid[at.ppid]
            }
        }
        return owners
    }

    private const val MAX_DEPTH = 64
}
