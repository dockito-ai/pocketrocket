package com.pocketrocket.sessions

import com.pocketrocket.claudeconfig.ClaudeIdentity
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.protocol.StreamEvent
import org.json.JSONObject
import java.util.Locale

/** Pure state changes for a session: what each stream event and each user action does to [SessionState]. */
object SessionReducer {

    /** Request id prefixes of our control requests, so their replies can be told apart. */
    const val INIT_ID = "pr-init"
    const val MODEL_ID = "pr-model-"
    /** The chosen model sent again to a new process: no notice. */
    const val RESTORE_MODEL_ID = "pr-restore-model-"
    const val MODE_ID = "pr-mode-"
    const val CONTEXT_ID = "pr-context-"
    const val REWIND_ID = "pr-rewind-"
    /** Stop or move a shell task ([ShellTasks]). */
    const val TASK_ID = "pr-task-"
    /** Configuration requests (`claudeconfig/`): their replies go to whoever asked and never reach the state. */
    const val CONFIG_ID = "pr-config-"

    fun onEvent(state: SessionState, event: StreamEvent): SessionState = when (event) {
        is StreamEvent.Init -> state.copy(
            conversationId = event.sessionId.ifEmpty { state.conversationId },
            model = event.model ?: state.model,
            mode = PermissionMode.of(event.permissionMode) ?: state.mode,
            commands = mergeCommands(state.commands, event.slashCommands),
        )
        is StreamEvent.TextDelta -> onTextDelta(state, event).working()
        is StreamEvent.AssistantText -> onAssistantText(state, event).working()
        is StreamEvent.ToolUse -> state.append(
            ChatItem.Tool(
                event.id, event.name, ToolSummary.of(event.name, event.input), ToolSummary.inputJson(event.input),
                agent = if (event.name in AGENT_TOOLS) AgentRun(
                    event.input.optString("description").ifBlank { "Subagent" }, event.input.optString("subagent_type").ifBlank { null },
                    prompt = event.input.optString("prompt").ifBlank { null },
                    background = event.input.optBoolean("run_in_background"),
                ) else null,
            ),
        ).working()
        is StreamEvent.UserEcho -> onUserEcho(state, event.uuid)
        is StreamEvent.ToolResult -> state.copy(
            items = state.items.map {
                if (it is ChatItem.Tool && it.id == event.toolUseId) ShellTasks.onResult(it, event.content).copy(result = event.content, isError = event.isError, agent = it.agent?.launched(event.content)) else it
            },
        )
        is StreamEvent.ApiError -> state.append(ChatItem.Error(state.nextId(), event.text.ifBlank { event.code }))
        is StreamEvent.PermissionRequest -> state.copy(
            status = SessionStatus.WaitingForPermission,
            permission = PendingPermission(
                event.requestId, event.toolName, ToolSummary.of(event.toolName, event.input), event.input, event.reason,
                AlwaysAllow.from(event.suggestions),
            ),
        )
        is StreamEvent.PermissionCancelled ->
            if (state.permission?.requestId == event.requestId) state.copy(permission = null).working() else state
        is StreamEvent.Result -> onResult(state, event)
        is StreamEvent.Usage -> state.copy(context = ContextUsage(event.contextTokens, state.context?.window))
        is StreamEvent.Status -> state.copy(
            compacting = event.status == "compacting",
            mode = PermissionMode.of(event.permissionMode) ?: state.mode,
        )
        is StreamEvent.Compacted -> state.append(
            ChatItem.Notice(state.nextId(), "Compacted · ${tokens(event.preTokens)} → ${tokens(event.postTokens)} tokens"),
        ).copy(compacting = false, context = ContextUsage(event.postTokens, state.context?.window, afterCompact = true))
        is StreamEvent.CommandOutput -> onCommandOutput(state, event.text)
        is StreamEvent.SubagentToolUse, is StreamEvent.SubagentToolResult, is StreamEvent.SubagentText, is StreamEvent.SubagentUpdate ->
            Subagents.onEvent(state, event)
        is StreamEvent.ShellStarted, is StreamEvent.TaskBackgrounded, is StreamEvent.ShellEnded -> ShellTasks.onEvent(state, event)
        is StreamEvent.PromptSuggestion -> state.copy(suggestion = event.text)
        is StreamEvent.ControlResponse -> onControlResponse(state, event)
    }

    private val AGENT_TOOLS = setOf("Task", "Agent")

    /** Keeps the richer list from the initialize reply; adds names that appeared since (skills found mid-session). */
    private fun mergeCommands(known: List<SlashCommand>, names: List<String>): List<SlashCommand> {
        if (names.isEmpty()) return known
        val have = known.map { it.name }.toSet()
        return known + names.filterNot { it in have }.map { SlashCommand(it) }
    }

    /**
     * Stamps subagents with wall-clock time [now]: [AgentRun.startedAt] when one is first seen running, [AgentRun.endedAt]
     * when one has stopped. [Session] calls it after each line, so replayed history (never stamped) has no clock.
     */
    fun timeAgents(state: SessionState, now: Long): SessionState {
        fun stamped(tool: ChatItem.Tool): AgentRun? {
            val run = tool.agent ?: return null
            return when {
                run.startedAt == null && tool.result == null && tool.agentRunning -> run.copy(startedAt = now)
                run.startedAt != null && run.endedAt == null && !tool.agentRunning -> run.copy(endedAt = now)
                else -> null
            }
        }
        if (state.items.none { it is ChatItem.Tool && stamped(it) != null }) return state
        return state.copy(items = state.items.map { if (it is ChatItem.Tool) stamped(it)?.let { run -> it.copy(agent = run) } ?: it else it })
    }

    /** The process ended (or a replay stopped short): subagents still running are stopped, at [now]. */
    fun stopAgents(state: SessionState, now: Long): SessionState {
        if (state.runningAgents == 0) return state
        return state.copy(
            items = state.items.map { item ->
                val run = (item as? ChatItem.Tool)?.takeIf { it.agentRunning }?.agent ?: return@map item
                (item as ChatItem.Tool).copy(agent = run.copy(status = "stopped", endedAt = run.startedAt?.let { now }))
            },
        )
    }

    /** The CLI may send an agent to the background on its own; its result then only says it was launched (2.1.312). */
    private fun AgentRun.launched(result: String): AgentRun =
        if (result.startsWith("Async agent launched")) copy(background = true) else this

    /** A local command's output (/cost, /context...): one line as a notice, more as a reply. /compact's "Compacted" is said by its boundary. */
    private fun onCommandOutput(state: SessionState, text: String): SessionState = when {
        text == "Compacted" -> state
        '\n' !in text && text.length <= 100 -> state.append(ChatItem.Notice(state.nextId(), text))
        else -> state.append(ChatItem.Assistant(state.nextId(), text))
    }

    private fun onControlResponse(state: SessionState, event: StreamEvent.ControlResponse): SessionState {
        val id = event.requestId
        val reply = event.response
        return when {
            id == INIT_ID && reply != null -> {
                val models = ModelOption.listFrom(reply.optJSONArray("models"))
                val commands = SlashCommand.listFrom(reply)
                state.copy(
                    models = models.ifEmpty { state.models },
                    identity = ClaudeIdentity.fromInitialize(reply) ?: state.identity,
                    commands = mergeCommands(commands.ifEmpty { state.commands }, state.commands.map { it.name }),
                    mode = PermissionMode.of(reply.optString("current_permission_mode").ifBlank { null }) ?: state.mode,
                    model = state.model ?: models.firstOrNull { it.value == "default" }?.resolvedModel,
                )
            }
            id.startsWith(RESTORE_MODEL_ID) -> state.copy(model = reply?.optString("model")?.ifBlank { null } ?: state.model)
            id.startsWith(MODEL_ID) -> if (reply != null) {
                val switched = state.copy(model = reply.optString("model").ifBlank { state.model })
                switched.append(ChatItem.Notice(state.nextId(), "Model · ${switched.modelName}"))
            } else state.append(ChatItem.Notice(state.nextId(), "Couldn't switch model: ${event.error}"))
            id.startsWith(MODE_ID) -> if (reply != null) {
                state.copy(mode = PermissionMode.of(reply.optString("mode").ifBlank { null }) ?: state.mode)
            } else state.append(ChatItem.Notice(state.nextId(), "Couldn't change mode: ${event.error}"))
            id.startsWith(CONTEXT_ID) && reply != null -> {
                val used = reply.optLong("totalTokens", -1)
                val window = reply.optLong("maxTokens", -1)
                val known = state.context
                when {
                    used < 0 -> state
                    // The summary count still holds the last pre-compaction call until the next reply; keep the boundary's.
                    known != null && known.afterCompact -> state.copy(context = known.copy(window = window.takeIf { it > 0 } ?: known.window))
                    else -> state.copy(context = ContextUsage(used, window.takeIf { it > 0 } ?: known?.window))
                }
            }
            id.startsWith(REWIND_ID) -> onRewound(state, reply, event.error)
            id.startsWith(TASK_ID) && reply == null -> state.append(ChatItem.Notice(state.nextId(), "Couldn't change the background task: ${event.error}"))
            else -> state
        }
    }

    /**
     * The CLI's answer to a rewind. Edit and Retry took the messages out of the chat before asking, so then there is
     * nothing left to drop; otherwise the message and everything after it go, and it goes back in the chat box.
     */
    private fun onRewound(state: SessionState, reply: JSONObject?, error: String?): SessionState {
        if (reply == null || !reply.optBoolean("rewound")) {
            val why = error ?: reply?.optString("reason")?.ifBlank { null } ?: "the CLI said no"
            return state.append(ChatItem.Notice(state.nextId(), "Couldn't rewind: $why"))
        }
        val target = reply.optString("targetMessageUuid")
        val at = state.items.indexOfFirst { it is ChatItem.User && it.uuid == target }
        // A rewind past a compaction undoes it too, so the CLI's next count is the one to trust.
        val trusted = state.copy(context = state.context?.copy(afterCompact = false))
        val message = state.items.getOrNull(at) as? ChatItem.User ?: return trusted
        return trusted.copy(items = state.items.take(at), draft = Draft(message.text, state.nextDraftKey(), message.attachments)).rewound()
    }

    /**
     * Edit: [message] and everything after it leave the chat and it goes back in the chat box, text and attachments.
     * They are kept aside for Undo until the rewind is sent ([onRewindSent]).
     */
    fun onEdit(state: SessionState, message: ChatItem.User): SessionState {
        val uuid = message.uuid ?: return state
        val at = state.items.indexOfFirst { it.id == message.id }
        if (at < 0) return state
        return state.copy(
            items = state.items.take(at),
            rewind = PendingRewind(uuid, at, state.items.drop(at)),
            draft = Draft(message.text, state.nextDraftKey(), message.attachments),
        )
    }

    /** Undo of an Edit not yet sent: its messages come back where they were, and the chat box gets back what it held. */
    fun onEditUndone(state: SessionState): SessionState {
        val pending = state.rewind ?: return state
        return state.copy(
            items = state.items.take(pending.at) + pending.dropped + state.items.drop(pending.at),
            rewind = null,
            draft = Draft("", state.nextDraftKey(), undo = true),
        )
    }

    /** The Edit's rewind goes to the CLI: what it set aside is gone for good. */
    fun onRewindSent(state: SessionState): SessionState = state.copy(rewind = null).rewound()

    /** Retry: [message] and everything after it leave the chat, before the rewind and the same message again are sent. */
    fun onRetry(state: SessionState, message: ChatItem.User): SessionState {
        val at = state.items.indexOfFirst { it.id == message.id }
        if (at < 0) return state
        return state.copy(items = state.items.take(at)).rewound()
    }

    private fun SessionState.rewound() = append(ChatItem.Notice(nextId(), "Rewound · files unchanged"))

    private fun SessionState.nextDraftKey() = (draft?.key ?: 0) + 1

    /** Grows the streaming item for this block, or starts one. Only the last item can be the one growing. */
    private fun onTextDelta(state: SessionState, event: StreamEvent.TextDelta): SessionState {
        val last = state.items.lastOrNull()
        if (last is ChatItem.Assistant && last.streaming && last.messageId == event.messageId && last.block == event.index) {
            return state.copy(items = state.items.dropLast(1) + last.copy(text = last.text + event.text))
        }
        if (event.text.isEmpty()) return state
        return state.append(ChatItem.Assistant(state.nextId(), event.text, streaming = true, messageId = event.messageId, block = event.index))
    }

    /**
     * A finished text block. If it was streamed, the first still-streaming item of the same message takes the final
     * text (blocks finish in order), keeping its id so the list does not redraw; a blank block is dropped. Otherwise
     * it is a new item (no partial messages, or the fake transport).
     */
    private fun onAssistantText(state: SessionState, event: StreamEvent.AssistantText): SessionState {
        val at = if (event.messageId == null) -1 else state.items.indexOfFirst {
            it is ChatItem.Assistant && it.streaming && it.messageId == event.messageId
        }
        if (at < 0) return if (event.text.isBlank()) state else state.append(ChatItem.Assistant(state.nextId(), event.text, messageId = event.messageId))
        val streamed = state.items[at] as ChatItem.Assistant
        val items = state.items.toMutableList()
        if (event.text.isBlank()) items.removeAt(at) else items[at] = streamed.copy(text = event.text, streaming = false)
        return state.copy(items = items)
    }

    /** Ends any text still streaming (an interrupted turn, or a block whose full message never came). */
    private fun SessionState.settled(): SessionState {
        if (items.none { it is ChatItem.Assistant && it.streaming }) return this
        return copy(items = items.mapNotNull { if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false).takeIf { a -> a.text.isNotBlank() } else it })
    }

    private fun onResult(state: SessionState, event: StreamEvent.Result): SessionState {
        val ended = state.settled().copy(
            permission = null,
            compacting = false,
            conversationId = event.sessionId ?: state.conversationId,
            context = event.contextWindow?.let { w -> (state.context ?: ContextUsage(0)).copy(window = w) } ?: state.context,
        )
        val settled = when {
            event.interrupted || event.text == "Interrupted" -> {
                // Keep planning ends the turn on purpose; "Interrupted" under it would only repeat that.
                val planning = (state.items.lastOrNull() as? ChatItem.Notice)?.text == "Kept planning"
                (if (planning) ended else ended.append(ChatItem.Notice(state.nextId(), "Interrupted"))).copy(status = SessionStatus.Done)
            }
            event.isError -> {
                // The CLI often already reported the error as an assistant message; don't say it twice.
                val said = (state.items.lastOrNull() as? ChatItem.Error)?.text == event.text
                val failed = if (said || event.text.isNullOrBlank()) ended else ended.append(ChatItem.Error(state.nextId(), event.text))
                failed.copy(status = SessionStatus.Error)
            }
            // Claude sometimes answers only in the result (no closing text block); show it then.
            state.items.lastOrNull() !is ChatItem.Assistant && !event.text.isNullOrBlank() ->
                ended.append(ChatItem.Assistant(state.nextId(), event.text)).copy(status = SessionStatus.Done)
            else -> ended.copy(status = SessionStatus.Done)
        }
        // Messages still queued were not read in this turn; Claude Code starts the next one with them (after Stop too).
        if (settled.queued.isEmpty()) return settled
        return settled.queued.fold(settled) { s, message -> s.append(message.copy(id = s.nextId())) }
            .copy(queued = emptyList(), status = SessionStatus.Working)
    }

    /**
     * A message sent while Claude works waits in [SessionState.queued], above the chat box, until Claude Code reads it,
     * so it doesn't land in the middle of the reply still streaming. History replayed from a transcript passes
     * [queue] false: those messages were all read long ago.
     */
    fun onUserMessage(
        state: SessionState, text: String, uuid: String? = null, attachments: List<Attachment> = emptyList(), queue: Boolean = true,
    ): SessionState {
        if (state.suggestion != null) return onUserMessage(state.copy(suggestion = null), text, uuid, attachments, queue)
        if (queue && state.busy && uuid != null) {
            return state.copy(queued = state.queued + ChatItem.User("${state.id}-queued-$uuid", text, uuid, attachments))
        }
        return state.append(ChatItem.User(state.nextId(), text, uuid, attachments)).copy(status = SessionStatus.Working)
    }

    /** Claude Code read a queued message: it joins the chat here, where the reply to it starts. */
    private fun onUserEcho(state: SessionState, uuid: String): SessionState {
        val message = state.queued.firstOrNull { it.uuid == uuid } ?: return state
        return state.append(message.copy(id = state.nextId())).copy(queued = state.queued - message).working()
    }

    /** The process ended before reading the queued messages: they go back in the chat box rather than get lost. */
    fun onQueueLost(state: SessionState): SessionState {
        if (state.queued.isEmpty()) return state
        val text = state.queued.joinToString("\n\n") { it.text }.trim()
        return state.copy(queued = emptyList(), draft = Draft(text, state.nextDraftKey(), state.queued.flatMap { it.attachments }))
    }

    fun onPermissionAnswered(state: SessionState, allow: Boolean, always: Boolean = false, byRocket: String? = null): SessionState {
        val asked = state.permission ?: return state
        val text = when {
            byRocket != null -> byRocket
            asked.isPlan && allow -> if (always) "Plan approved · accept edits" else "Plan approved"
            asked.isPlan -> "Kept planning"
            always && asked.alwaysAllow != null -> "Allowed ${asked.toolName} · always ${asked.alwaysAllow.what.replace("`", "")}"
            allow -> "Allowed ${asked.toolName}"
            else -> "Denied ${asked.toolName}"
        }
        val notice = ChatItem.Notice(state.nextId(), text, byRocket = byRocket != null)
        return state.append(notice).copy(permission = null, status = SessionStatus.Working)
    }

    /** A line in the chat recording something Rocket did for him. */
    fun onRocketNote(state: SessionState, text: String): SessionState = state.append(ChatItem.Notice(state.nextId(), text, byRocket = true))

    fun onReady(state: SessionState): SessionState =
        if (state.status == SessionStatus.Starting) state.copy(status = SessionStatus.WaitingForInput) else state

    fun onExit(state: SessionState, code: Int, message: String?): SessionState {
        val text = message ?: "Claude Code stopped (exit code $code)."
        return onQueueLost(state).settled().let { it.append(ChatItem.Error(it.nextId(), text)) }.copy(status = SessionStatus.Error, permission = null, compacting = false)
    }

    /** 160k, 1M, 1.5k, 950: how the terminal's status line writes token counts. */
    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> trim(n / 1_000_000.0) + "M"
        n >= 1_000 -> trim(n / 1_000.0) + "k"
        else -> n.toString()
    }

    private fun trim(v: Double): String =
        if (v >= 10 || v == Math.floor(v)) Math.round(v).toString() else String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    /** Claude is at it again: a suggestion from the last turn no longer fits. */
    private fun SessionState.working() =
        if (status == SessionStatus.WaitingForPermission) copy(suggestion = null) else copy(status = SessionStatus.Working, suggestion = null)

    private fun SessionState.append(item: ChatItem) = copy(items = items + item, seq = maxOf(seq, items.size) + 1)

    /** Never reused, even after a rewind drops items. */
    private fun SessionState.nextId() = "$id-${maxOf(seq, items.size)}"
}
