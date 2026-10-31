package com.pocketrocket.workspaces

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.writeAtomically
import com.pocketrocket.setup.Launchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * How `pocketrocket-workspace` (setup/pocketrocket-workspace, decision 73) reaches the app: files in
 * ~/.pocketrocket/requests/, which only the app's own uid can read or write, the same way Claude's
 * sign-in hands its URL back through a file (auth/ClaudeLogin). The script writes `<id>.req` (by rename,
 * so it is never read half-written); the app, watching the folder, takes it (renames it to `.taken`),
 * creates `<id>.out` at once as its acknowledgement, appends the lines to print, and writes the exit code
 * to `<id>.exit` last. The script waits a few seconds for `.out` (no answer: the app isn't running) and
 * then follows it until `.exit` appears. The work itself is [WorkspaceCliRunner].
 *
 * The app installs the script as $PREFIX/bin/pocketrocket-workspace from its assets at every start.
 */
object WorkspaceRequests {
    private const val TAG = "PocketRocketWsCli"
    private const val LAUNCHER = "pocketrocket-workspace"
    private val dir: File get() = File(AccountPaths.home, ".pocketrocket/requests")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Held for the life of the process: a FileObserver that is garbage collected stops watching. */
    @Volatile private var observer: FileObserver? = null
    @Volatile private lateinit var app: Context

    /** App start: install the script, clear old files, watch for requests and answer any already waiting. */
    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        if (!AccountPaths.home.isDirectory) return // before the first-run setup
        Launchers.install(app, LAUNCHER)
        dir.mkdirs()
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.filter { !it.name.endsWith(".req") && it.lastModified() < dayAgo }?.forEach { it.delete() }
        if (observer == null) {
            @Suppress("DEPRECATION")
            observer = object : FileObserver(dir.path, FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.endsWith(".req")) take(File(dir, path))
                }
            }.also { it.startWatching() }
        }
        dir.listFiles()?.filter { it.name.endsWith(".req") }?.forEach(::take)
    }

    /** Claims [request] (only one rename can win) and answers it off the main thread. */
    private fun take(request: File) {
        val id = request.name.removeSuffix(".req")
        val taken = File(dir, "$id.taken")
        if (!request.renameTo(taken)) return
        val out = File(dir, "$id.out")
        runCatching { out.writeText("") }
        scope.launch {
            val code = try {
                val parsed = WorkspaceCli.parseRequest(taken.readText())
                if (parsed == null) {
                    append(out, "error: This pocketrocket-workspace doesn't match the app. Restart PocketRocket to update it.")
                    WorkspaceCliRunner.USAGE
                } else {
                    WorkspaceCliRunner.run(app, parsed) { append(out, it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                append(out, "error: ${e.message ?: e.javaClass.simpleName}")
                WorkspaceCliRunner.FAILED
            } finally {
                taken.delete()
            }
            runCatching { writeAtomically(File(dir, "$id.exit"), "$code\n") }
        }
    }

    /** One write per call, so the script never prints half a line. */
    @Synchronized
    private fun append(out: File, text: String) {
        runCatching { FileOutputStream(out, true).use { it.write((text.trimEnd('\n') + "\n").toByteArray()) } }
    }
}
