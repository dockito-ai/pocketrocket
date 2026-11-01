package com.pocketrocket.builds

import java.io.File

/** One build in the phone's build slots (decision 97), from its `<pid>.build` file in ~/.pocketrocket/builds. */
data class BuildEntry(
    /** The process the build belongs to: the wrapper for cargo, make, ninja and flutter, the daemon for Gradle. */
    val pid: Int,
    /** The process holding or waiting for the lock; the entry is stale once it is gone. */
    val helper: Int,
    val running: Boolean,
    /** Seconds since the epoch: when it asked for a slot, and when it got one (null while waiting). */
    val queuedAt: Long,
    val startedAt: Long?,
    val folder: String,
    /** The Claude session's folder when a session started it, else empty (a terminal). */
    val session: String,
    val command: String,
) {
    /** "gradle assembleDebug" in "my-app". */
    val folderName: String get() = folder.trimEnd('/').substringAfterLast('/')
}

/** Running builds first (oldest first), then the waiting ones in the order they take a slot. */
data class BuildQueueState(val slots: Int, val running: List<BuildEntry>, val waiting: List<BuildEntry>) {
    val isEmpty: Boolean get() = running.isEmpty() && waiting.isEmpty()

    companion object {
        val EMPTY = BuildQueueState(0, emptyList(), emptyList())
    }
}

/**
 * Reads the build queue `pocketrocket-build-slot` keeps (setup/pocketrocket-build-slot). Pure Kotlin: the folder and a
 * liveness check come in, so tests need neither Android nor real processes.
 */
object BuildQueue {
    const val SUFFIX = ".build"

    /** One status file's text (key=value lines); null when it isn't one. */
    fun parse(text: String): BuildEntry? {
        val fields = text.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
        }.toMap()
        val pid = fields["pid"]?.toIntOrNull() ?: return null
        val helper = fields["helper"]?.toIntOrNull() ?: return null
        val running = when (fields["state"]) {
            "running" -> true
            "waiting" -> false
            else -> return null
        }
        return BuildEntry(
            pid = pid,
            helper = helper,
            running = running,
            queuedAt = fields["queued"]?.toLongOrNull() ?: 0L,
            startedAt = fields["started"]?.toLongOrNull()?.takeIf { running },
            folder = fields["folder"].orEmpty(),
            session = fields["session"].orEmpty(),
            command = fields["command"].orEmpty(),
        )
    }

    /** The slot count the script last wrote; 0 when unknown. */
    fun parseSlots(text: String?): Int = text?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** Running ones by start, waiting ones by arrival (ties to the lower helper pid, as the script decides turns). */
    fun order(entries: List<BuildEntry>, slots: Int): BuildQueueState = BuildQueueState(
        slots,
        entries.filter { it.running }.sortedWith(compareBy<BuildEntry>({ it.startedAt ?: it.queuedAt }, { it.helper })),
        entries.filter { !it.running }.sortedWith(compareBy<BuildEntry>({ it.queuedAt }, { it.helper })),
    )

    /** The queue in [dir], dropping entries whose helper process is gone ([alive]). */
    fun read(dir: File, alive: (Int) -> Boolean): BuildQueueState {
        val files = dir.listFiles()?.filter { it.name.endsWith(SUFFIX) }.orEmpty()
        val entries = files.mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }.filter { alive(it.helper) }
        val slots = parseSlots(runCatching { File(dir, "slots").readText() }.getOrNull())
        return order(entries, slots)
    }
}
