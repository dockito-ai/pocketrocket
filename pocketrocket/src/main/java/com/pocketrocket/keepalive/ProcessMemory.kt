package com.pocketrocket.keepalive

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.pocketrocket.usage.ProcessTable
import com.pocketrocket.usage.SessionTrees
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug builds only: logs the resident memory of each running session's Claude Code process tree once a
 * minute (`adb logcat -s PocketRocketMemory`), so we know what a session costs. Only ids and sizes are
 * logged, nothing else about the session. Settings › Usage reads the same processes on screen.
 */
internal object ProcessMemory {

    private const val TAG = "PocketRocketMemory"
    private const val INTERVAL_MS = 60_000L

    fun start(context: Context, scope: CoroutineScope) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(INTERVAL_MS)
                val running = SessionKeepAlive.liveSessions()
                if (running.isEmpty()) continue
                val procs = ProcessTable.read()
                val owners = SessionTrees.attribute(procs, running.associate { it.id to it.conversationId })
                var total = 0L
                running.forEach { session ->
                    val tree = procs.filter { owners[it.pid] == session.id }
                    val kb = tree.sumOf { it.rssKb }
                    total += kb
                    Log.d(TAG, "session ${session.id}: ${kb / 1024} MB RSS in ${tree.size} process(es) ${tree.map { it.pid }}")
                }
                Log.d(TAG, "${running.size} session(s): ${total / 1024} MB RSS total")
            }
        }
    }
}
