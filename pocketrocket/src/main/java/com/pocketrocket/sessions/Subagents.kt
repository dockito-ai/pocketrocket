package com.pocketrocket.sessions

import com.pocketrocket.sessions.protocol.StreamEvent

/** How a subagent stands, for its card, its row in the Background sheet and its screen. */
enum class AgentState { Running, Done, Failed, Stopped }

val ChatItem.Tool.agentState: AgentState?
    get() {
        val run = agent ?: return null
        return when {
            agentRunning -> AgentState.Running
            run.status == "stopped" || run.status == "killed" -> AgentState.Stopped
            isError || run.status == "failed" -> AgentState.Failed
            else -> AgentState.Done
        }
    }

/**
 * What a subagent's own lines do to its record ([AgentRun]): its tool calls with their results and its messages, kept in
 * order, the latest [LOG_KEEP] of them. Pure, like [SessionReducer], which hands these events here.
 */
object Subagents {

    /** Steps and messages kept per agent; older ones are let go and counted, so a long agent can't fill the memory. */
    const val LOG_KEEP = 200

    /** Characters kept of one tool result inside an agent (the card shows its first 40 lines anyway). */
    const val RESULT_KEEP = 4_000

    fun onEvent(state: SessionState, event: StreamEvent): SessionState = when (event) {
        is StreamEvent.SubagentToolUse -> state.updateAgent(event.parentToolUseId) {
            it.added(ChatItem.Tool(event.id, event.name, ToolSummary.of(event.name, event.input), ToolSummary.inputJson(event.input)))
                .copy(stepsSeen = it.stepsSeen + 1)
        }
        is StreamEvent.SubagentToolResult -> state.updateAgent(event.parentToolUseId) { run ->
            run.copy(log = run.log.map { if (it is ChatItem.Tool && it.id == event.toolUseId) it.copy(result = kept(event.content), isError = event.isError) else it })
        }
        is StreamEvent.SubagentText -> state.updateAgent(event.parentToolUseId) {
            // Entries ever added never shrink, so the count keeps message ids unique.
            it.added(ChatItem.Assistant("${event.parentToolUseId}-m${it.dropped + it.log.size}", event.text))
        }
        is StreamEvent.SubagentUpdate -> state.updateAgent(event.toolUseId) {
            it.copy(
                taskId = event.taskId ?: it.taskId,
                activity = event.activity ?: it.activity, status = event.status ?: it.status, summary = event.summary ?: it.summary,
                tokens = event.tokens ?: it.tokens, toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs,
            )
        }
        else -> state
    }

    private fun AgentRun.added(entry: ChatItem): AgentRun {
        val log = log + entry
        val over = (log.size - LOG_KEEP).coerceAtLeast(0)
        return copy(log = log.drop(over), dropped = dropped + over)
    }

    private fun kept(text: String): String =
        if (text.length <= RESULT_KEEP) text else text.take(RESULT_KEEP) + "\n… ${text.length - RESULT_KEEP} more characters not kept"

    private fun SessionState.updateAgent(toolUseId: String, change: (AgentRun) -> AgentRun): SessionState {
        if (items.none { it is ChatItem.Tool && it.id == toolUseId && it.agent != null }) return this
        return copy(items = items.map { if (it is ChatItem.Tool && it.id == toolUseId && it.agent != null) it.copy(agent = change(it.agent)) else it })
    }

    /**
     * What the subagent handed back, once it has: its Task call's result without the CLI's frame, or for a background
     * agent (whose result only says it was launched) its last message. Null while it runs, once stopped, or when there
     * is none.
     */
    fun finalAnswer(tool: ChatItem.Tool): String? {
        val state = tool.agentState ?: return null
        if (state == AgentState.Running || state == AgentState.Stopped) return null
        val result = tool.result?.takeUnless { it.startsWith("Async agent launched") }
        return result?.let(::handBack)?.ifBlank { null }
            ?: tool.agent?.log?.lastOrNull { it is ChatItem.Assistant }?.let { (it as ChatItem.Assistant).text }
    }

    /** The tool result wraps the subagent's report in a frame; keep only the report. */
    internal fun handBack(result: String): String {
        val body = result.substringAfter("The report follows:\n", result)
        return body.substringBefore("\nagentId:").lines().joinToString("\n") { it.removePrefix("  ") }.trim()
    }

    /** Copy all: the task, the prompt, every step and message kept, and the final answer, as plain text. */
    fun transcript(tool: ChatItem.Tool): String {
        val run = tool.agent ?: return ""
        return buildString {
            append("Agent").append(run.type?.let { " ($it)" }.orEmpty()).append(": ").append(run.description).append("\n")
            run.prompt?.let { append("\nPrompt:\n").append(it.trim()).append("\n") }
            if (run.dropped > 0) append("\n(").append(earlier(run.dropped)).append(")\n")
            run.log.forEach { entry ->
                when (entry) {
                    is ChatItem.Assistant -> append("\n").append(entry.text.trim()).append("\n")
                    is ChatItem.Tool -> {
                        append("\n● ").append(entry.name).append(entry.summary.takeIf { it != entry.name }?.let { " $it" }.orEmpty()).append("\n")
                        entry.result?.let { append(if (entry.isError) "  ✗ " else "  ⎿ ").append(it.trim().replace("\n", "\n    ")).append("\n") }
                    }
                    else -> Unit
                }
            }
            finalAnswer(tool)?.let { append("\nFinal answer:\n").append(it.trim()).append("\n") }
        }.trimEnd() + "\n"
    }

    /** "1 earlier step not kept", "12 earlier steps not kept". */
    fun earlier(n: Int): String = "$n earlier ${if (n == 1) "step" else "steps"} not kept"
}
