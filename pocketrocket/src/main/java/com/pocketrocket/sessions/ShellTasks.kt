package com.pocketrocket.sessions

import com.pocketrocket.sessions.protocol.StreamEvent

/**
 * A Bash call Claude Code runs as shell task [taskId] (2.1.316 runs every Bash call this way). A [background] one
 * answers at once ("Command running in background") and goes on until its notification; a foreground one can be
 * moved there. At the end: [status] (`completed`, `failed`, `stopped`) and [exitCode]. [outputFile] holds all it
 * printed, for a background shell. [startedAt]/[endedAt] are wall-clock times [Session] stamps, as for agents.
 */
data class ShellRun(
    val taskId: String,
    val background: Boolean = false,
    val status: String? = null,
    val exitCode: Int? = null,
    val outputFile: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) {
    val failed: Boolean get() = status == "failed" || (exitCode != null && exitCode != 0)
    val stopped: Boolean get() = status == "stopped" || status == "killed"

    fun elapsedMs(now: Long): Long? = startedAt?.let { ((endedAt ?: now) - it).coerceAtLeast(0) }
}

/** True while this Bash call's shell still runs: no end seen, and no result yet unless it is in the background. */
val ChatItem.Tool.shellRunning: Boolean
    get() {
        val run = shell ?: return false
        return run.status == null && run.endedAt == null && (result == null || run.background)
    }

/** A shell running in the background: the status line counts these and the Background sheet lists them. */
val ChatItem.Tool.backgroundShell: Boolean
    get() = shell?.background == true

/** What shell task events do to a session; pure, like [SessionReducer], which hands them here. */
object ShellTasks {

    fun onEvent(state: SessionState, event: StreamEvent): SessionState = when (event) {
        is StreamEvent.ShellStarted -> state.updateTool({ it.id == event.toolUseId && it.name == "Bash" }) {
            it.copy(shell = (it.shell ?: ShellRun(event.taskId)).copy(taskId = event.taskId, background = event.background || it.shell?.background == true))
        }
        is StreamEvent.TaskBackgrounded -> state.updateTool({ it.shell?.taskId == event.taskId }) { it.copy(shell = it.shell?.copy(background = true)) }
        is StreamEvent.ShellEnded -> state.updateTool({ it.id == event.toolUseId && it.shell != null }) {
            val run = it.shell!!
            it.copy(shell = run.copy(status = event.status ?: "completed", exitCode = event.exitCode, outputFile = event.outputFile ?: run.outputFile))
        }
        else -> state
    }

    /** A background shell's tool result names its output file ("Output is being written to: …/tasks/<id>.output."). */
    fun onResult(tool: ChatItem.Tool, result: String): ChatItem.Tool {
        val run = tool.shell ?: return tool
        val file = outputPath.find(result)?.groupValues?.get(1) ?: return tool
        return tool.copy(shell = run.copy(outputFile = file))
    }

    private val outputPath = Regex("""Output is being written to: (\S+\.output)""")

    /** Clock stamps at [now]: when a shell is first seen running, and when it has ended. */
    fun time(state: SessionState, now: Long): SessionState {
        fun stamped(tool: ChatItem.Tool): ShellRun? {
            val run = tool.shell ?: return null
            return when {
                run.startedAt == null && tool.shellRunning -> run.copy(startedAt = now)
                run.startedAt != null && run.endedAt == null && !tool.shellRunning -> run.copy(endedAt = now)
                else -> null
            }
        }
        if (state.items.none { it is ChatItem.Tool && stamped(it) != null }) return state
        return state.copy(items = state.items.map { if (it is ChatItem.Tool) stamped(it)?.let { run -> it.copy(shell = run) } ?: it else it })
    }

    /** The process ended: its shells went with it. */
    fun stopAll(state: SessionState, now: Long): SessionState {
        if (state.items.none { it is ChatItem.Tool && it.shellRunning }) return state
        return state.copy(
            items = state.items.map { item ->
                if (item is ChatItem.Tool && item.shellRunning) item.copy(shell = item.shell!!.copy(status = "stopped", endedAt = item.shell.startedAt?.let { now })) else item
            },
        )
    }

    private fun SessionState.updateTool(match: (ChatItem.Tool) -> Boolean, change: (ChatItem.Tool) -> ChatItem.Tool): SessionState {
        if (items.none { it is ChatItem.Tool && match(it) }) return this
        return copy(items = items.map { if (it is ChatItem.Tool && match(it)) change(it) else it })
    }
}
