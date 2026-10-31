package com.pocketrocket.sessions

import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.protocol.StreamJsonParser
import com.pocketrocket.ui.chat.agentNumbers
import com.pocketrocket.ui.chat.agentClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines are trimmed copies of what Claude Code 2.1.308 printed in stream-json mode (29 Oct 2026). */
class SessionReducerTest {

    private fun start() = SessionState(
        id = "s", folder = "/data/data/app.pocket/files/home", title = "home", demo = false,
        profile = ClaudeProfile.Default, conversationId = "c",
    )

    private fun SessionState.feed(vararg lines: String): SessionState =
        lines.fold(this) { state, line -> StreamJsonParser.parse(line).fold(state, SessionReducer::onEvent) }

    // --- what was there before: streaming, tools, results ---

    @Test fun streamedTextIsReplacedByTheFinalBlock() {
        val state = start().feed(
            """{"type":"stream_event","api_message_id":"m1","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}""",
            """{"type":"stream_event","api_message_id":"m1","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}""",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Hello"}]}}""",
        )
        val only = state.items.single() as ChatItem.Assistant
        assertEquals("Hello", only.text)
        assertFalse(only.streaming)
    }

    @Test fun toolUseThenResult() {
        val state = start().feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a.txt"}]}}""",
            """{"type":"result","is_error":false,"result":"done","session_id":"c2"}""",
        )
        val tool = state.items[0] as ChatItem.Tool
        assertEquals("a.txt", tool.result)
        assertNull(tool.agent)
        assertEquals(SessionStatus.Done, state.status)
        assertEquals("c2", state.conversationId)
    }

    // --- init and the initialize reply ---

    @Test fun initGivesModeModelAndCommands() {
        val state = start().feed(
            """{"type":"system","subtype":"init","session_id":"c","model":"claude-haiku-5-5","permissionMode":"plan","slash_commands":["compact","review","plugin:git"]}""",
        )
        assertEquals(PermissionMode.Plan, state.mode)
        assertEquals("Haiku 5.5", state.modelName)
        assertEquals(listOf("compact", "review", "plugin:git"), state.commands.map { it.name })
    }

    @Test fun initializeReplyGivesModelsAndDescribedCommands() {
        val state = start().feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-init","response":{
              "commands":[{"name":"review","description":"Review a PR","argumentHint":"<pr>"},{"name":"statusline","description":"Terminal only"}],
              "terminal_slash_commands":["statusline"],
              "models":[{"value":"default","resolvedModel":"claude-opus-5-5","displayName":"Default (recommended)","description":"Opus 5.5"},
                        {"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet 5.5","description":"Efficient"}],
              "current_permission_mode":"acceptEdits"}}}""".replace("\n", ""),
        )
        assertEquals(listOf("default", "sonnet"), state.models.map { it.value })
        assertEquals(listOf(SlashCommand("review", "Review a PR", "<pr>")), state.commands)
        assertEquals(PermissionMode.AcceptEdits, state.mode)
        assertEquals("claude-opus-5-5", state.model)
        // A later init keeps the descriptions and adds names that appeared since.
        val later = state.feed("""{"type":"system","subtype":"init","session_id":"c","slash_commands":["review","new-skill"]}""")
        assertEquals("Review a PR", later.commands.first().description)
        assertEquals("new-skill", later.commands.last().name)
    }

    // --- model and mode switches ---

    @Test fun modelSwitchReplyUpdatesModelAndSaysSo() {
        val state = start().copy(models = listOf(ModelOption("haiku", "Haiku 5.5", "", "claude-haiku-5-5"))).feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-model-3","response":{"model":"claude-haiku-5-5"}}}""",
        )
        assertEquals("claude-haiku-5-5", state.model)
        assertEquals("Model · Haiku 5.5", (state.items.single() as ChatItem.Notice).text)
    }

    @Test fun modelSwitchErrorIsANotice() {
        val state = start().feed(
            """{"type":"control_response","response":{"subtype":"error","request_id":"pr-model-3","error":"Unknown model"}}""",
        )
        assertTrue((state.items.single() as ChatItem.Notice).text.contains("Unknown model"))
    }

    @Test fun modeFollowsTheCliReplyAndItsStatusLines() {
        val switched = start().feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-mode-1","response":{"mode":"plan"}}}""",
        )
        assertEquals(PermissionMode.Plan, switched.mode)
        // Approving a plan makes the CLI drop back to default on its own.
        val after = switched.feed("""{"type":"system","subtype":"status","status":null,"permissionMode":"default","session_id":"c"}""")
        assertEquals(PermissionMode.Manual, after.mode)
        assertEquals(PermissionMode.Auto, PermissionMode.of("bypassPermissions"))
        assertEquals(PermissionMode.AcceptEdits, PermissionMode.Manual.next)
        assertEquals(PermissionMode.Manual, PermissionMode.Auto.next)
    }

    // --- context ---

    @Test fun contextFromUsageAndFromTheCli() {
        val state = start().feed(
            """{"type":"assistant","message":{"model":"claude-haiku-5-5","id":"m1","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":2,"cache_creation_input_tokens":12210,"cache_read_input_tokens":16398,"output_tokens":4}},"parent_tool_use_id":null}""",
        )
        assertEquals(ContextUsage(28610), state.context)
        val ended = state.feed("""{"type":"result","is_error":false,"result":"ok","modelUsage":{"claude-haiku-5-5":{"contextWindow":1000000}}}""")
        assertEquals(ContextUsage(28610, 1_000_000), ended.context)
        val asked = ended.feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-context-4","response":{"totalTokens":30111,"maxTokens":200000,"percentage":15}}}""",
        )
        assertEquals(ContextUsage(30111, 200_000), asked.context)
        assertEquals("30k/200k", "${SessionReducer.tokens(30111)}/${SessionReducer.tokens(200_000)}")
        assertEquals("1M", SessionReducer.tokens(1_000_000))
        assertEquals("1.5k", SessionReducer.tokens(1_499))
    }

    @Test fun subagentUsageDoesNotMoveTheMeter() {
        val state = start().feed(
            """{"type":"assistant","message":{"id":"m9","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/x/a.txt"}}],"usage":{"input_tokens":9000}},"parent_tool_use_id":"t1"}""",
        )
        assertNull(state.context)
    }

    // --- compact ---

    @Test fun compactShowsProgressThenTheResult() {
        val running = SessionReducer.onUserMessage(start(), "/compact", "u1")
            .feed("""{"type":"system","subtype":"status","status":"compacting","session_id":"c"}""")
        assertTrue(running.compacting)
        val done = running.feed(
            """{"type":"system","subtype":"status","status":null,"compact_result":"success","session_id":"c"}""",
            """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":32626,"post_tokens":10497}}""",
            """{"type":"user","message":{"role":"user","content":"<local-command-stdout>Compacted </local-command-stdout>"},"isReplay":true}""",
            """{"type":"result","subtype":"success","is_error":false,"num_turns":0,"result":""}""",
        )
        assertFalse(done.compacting)
        assertEquals(SessionStatus.Done, done.status)
        assertEquals(listOf("/compact", "Compacted · 33k → 10k tokens"), done.items.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Notice).text })
        assertEquals(10497L, done.context?.used)
    }

    @Test fun localCommandOutputIsShown() {
        val state = start().feed("""{"type":"user","message":{"role":"user","content":"<local-command-stdout>Total cost: ${'$'}0.01</local-command-stdout>"}}""")
        assertEquals("Total cost: \$0.01", (state.items.single() as ChatItem.Notice).text)
    }

    // --- messages sent while Claude works (lines from Claude Code 2.1.312 with --replay-user-messages) ---

    private val echo2 = """{"type":"user","message":{"role":"user","content":"Also say banana."},"uuid":"u2","isReplay":true}"""

    @Test fun messageSentMidTurnWaitsUntilClaudeReadsIt() {
        val working = SessionReducer.onUserMessage(start(), "Run sleep", "u1").feed(
            """{"type":"user","message":{"role":"user","content":"Run sleep"},"uuid":"u1","isReplay":true}""",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sleep 6"}}]}}""",
        )
        val queued = SessionReducer.onUserMessage(working, "Also say banana.", "u2")
        assertEquals(listOf("Also say banana."), queued.queued.map { it.text })
        assertEquals(2, queued.items.size)
        val read = queued.feed(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}}""",
            echo2,
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"ok banana"}]}}""",
        )
        assertTrue(read.queued.isEmpty())
        assertEquals(listOf("Run sleep", "Bash", "Also say banana.", "ok banana"), read.items.map(::label))
        assertEquals(read.items.size, read.items.map { it.id }.toSet().size)
    }

    @Test fun messageStillQueuedWhenTheTurnEndsStartsTheNextOne() {
        val queued = SessionReducer.onUserMessage(SessionReducer.onUserMessage(start(), "hi", "u1"), "Also say banana.", "u2")
        val ended = queued.feed("""{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Interrupted"}""")
        assertEquals(listOf("hi", "Interrupted", "Also say banana."), ended.items.map(::label))
        assertEquals(SessionStatus.Working, ended.status)
        // Its echo then comes with the next turn and changes nothing.
        assertEquals(ended.items, ended.feed(echo2).items)
    }

    @Test fun queuedMessageGoesBackToTheChatBoxIfTheProcessEnds() {
        val queued = SessionReducer.onUserMessage(SessionReducer.onUserMessage(start(), "hi", "u1"), "Also say banana.", "u2")
        val ended = SessionReducer.onExit(queued, 1, null)
        assertTrue(ended.queued.isEmpty())
        assertEquals("Also say banana.", ended.draft?.text)
    }

    private fun label(item: ChatItem) = when (item) {
        is ChatItem.User -> item.text
        is ChatItem.Assistant -> item.text
        is ChatItem.Tool -> item.name
        is ChatItem.Notice -> item.text
        is ChatItem.Error -> item.text
    }

    // --- stop ---

    @Test fun interruptedTurnEndsDoneNotError() {
        val state = SessionReducer.onUserMessage(start(), "count", "u1").feed(
            """{"type":"stream_event","api_message_id":"m1","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"1\n2\n"}}}""",
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-interrupt-1","response":{"still_queued":[]}}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"terminal_reason":"aborted_streaming","errors":["x"]}""",
        )
        assertEquals(SessionStatus.Done, state.status)
        assertEquals("Interrupted", (state.items.last() as ChatItem.Notice).text)
        assertEquals("1\n2\n", (state.items[1] as ChatItem.Assistant).text)
    }

    // --- plan approval ---

    @Test fun exitPlanModeIsAPlanToApprove() {
        val asked = start().feed(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","input":{"plan":"1. Create hello.txt"},"tool_use_id":"t1","requires_user_interaction":true}}""",
        )
        assertTrue(asked.permission!!.isPlan)
        assertEquals(SessionStatus.WaitingForPermission, asked.status)
        assertEquals("Plan approved · accept edits", (SessionReducer.onPermissionAnswered(asked, allow = true, always = true).items.last() as ChatItem.Notice).text)
        assertEquals("Kept planning", (SessionReducer.onPermissionAnswered(asked, allow = false).items.last() as ChatItem.Notice).text)
    }

    // --- subagents ---

    @Test fun subagentCardFollowsItsWork() {
        var state = start().feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_A","name":"Agent","input":{"description":"Read a.txt and report","subagent_type":"general-purpose","prompt":"Read a.txt"}}]},"parent_tool_use_id":null}""",
            """{"type":"system","subtype":"task_started","task_id":"k","tool_use_id":"toolu_A","description":"Read a.txt and report","subagent_type":"general-purpose"}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Read a.txt"}]},"parent_tool_use_id":"toolu_A"}""",
            """{"type":"system","subtype":"task_progress","tool_use_id":"toolu_A","description":"Reading a.txt"}""",
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"Let me read it"},{"type":"tool_use","id":"toolu_B","name":"Read","input":{"file_path":"/w/a.txt"}}]},"parent_tool_use_id":"toolu_A"}""",
        )
        var agent = (state.items.single() as ChatItem.Tool).agent!!
        assertEquals("Read a.txt and report", agent.description)
        assertEquals("Reading a.txt", agent.activity)
        assertEquals(listOf("toolu_B" to "a.txt"), agent.steps.map { it.id to it.summary })
        assertEquals("k", agent.taskId)
        assertEquals("Read a.txt", agent.prompt)
        state = state.feed(
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_B","type":"tool_result","content":"the secret is 42"}]},"parent_tool_use_id":"toolu_A"}""",
            """{"type":"system","subtype":"task_notification","tool_use_id":"toolu_A","status":"completed","summary":"a.txt says: the secret is 42"}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_A","type":"tool_result","content":[{"type":"text","text":"report"}]}]},"parent_tool_use_id":null}""",
        )
        val tool = state.items.single() as ChatItem.Tool
        agent = tool.agent!!
        assertEquals("the secret is 42", agent.steps.single().result)
        assertEquals("completed", agent.status)
        assertEquals("a.txt says: the secret is 42", agent.summary)
        assertEquals("report", tool.result)
    }

    private val agentCall = """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_A","name":"Agent","input":{"description":"Read a.txt","prompt":"Read a.txt"}}]},"parent_tool_use_id":null}"""

    private fun SessionState.agent() = (items.single() as ChatItem.Tool).agent!!

    @Test fun subagentNumbersTickThenFreeze() {
        // Session stamps the clock after each line; the reducer itself stays pure.
        var state = SessionReducer.timeAgents(start().feed(agentCall), 1_000)
        assertEquals(1, state.runningAgents)
        assertEquals(1_000L, state.agent().startedAt)
        state = state.feed("""{"type":"system","subtype":"task_progress","tool_use_id":"toolu_A","description":"Reading a.txt","usage":{"total_tokens":8100,"tool_uses":5,"duration_ms":30000}}""")
        assertEquals(8_100L, state.agent().tokens)
        assertEquals(5, state.agent().tools)
        // While it runs the card counts from the stamp, not from a progress line.
        assertEquals(41_000L, state.agent().elapsedMs(running = true, now = 42_000))
        assertEquals("0:41 · 8.1k tokens · 5 tools", agentNumbers(41_000, 8_100, 5))
        state = state.feed(
            """{"type":"system","subtype":"task_notification","tool_use_id":"toolu_A","status":"completed","summary":"done","usage":{"total_tokens":12200,"tool_uses":7,"duration_ms":72000}}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_A","type":"tool_result","content":"report"}]},"parent_tool_use_id":null}""",
        )
        assertEquals(0, state.runningAgents)
        state = SessionReducer.timeAgents(state, 90_000)
        val agent = state.agent()
        assertEquals(90_000L, agent.endedAt)
        // The CLI's own duration wins over the stamps once it ends.
        assertEquals(72_000L, agent.elapsedMs(running = false, now = 500_000))
        assertEquals("1:12 · 12.2k tokens · 7 tools", agentNumbers(agent.elapsedMs(false, 0), agent.tokens, agent.tools))
        assertEquals("1:02:05", agentClock(3_725_000))
    }

    @Test fun subagentNumbersOnTheLineItselfAndStepsAsToolCount() {
        var state = SessionReducer.timeAgents(start().feed(agentCall), 1_000).feed(
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"toolu_B","name":"Read","input":{"file_path":"/w/a.txt"}}]},"parent_tool_use_id":"toolu_A"}""",
            """{"type":"system","subtype":"task_notification","tool_use_id":"toolu_A","status":"completed","total_tokens":950}""",
        )
        state = SessionReducer.timeAgents(state, 5_000)
        val agent = state.agent()
        assertEquals(950L, agent.tokens)
        assertNull(agent.toolUses)
        assertEquals(1, agent.tools)
        // No duration from the CLI: the stamps say how long it took.
        assertEquals(4_000L, agent.elapsedMs(running = false, now = 99_000))
    }

    @Test fun replayedAgentHasFinalNumbersAndNoClock() {
        // A transcript's result line carries the totals in toolUseResult (2.1.312).
        val state = start().feed(
            agentCall,
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_A","type":"tool_result","content":[{"type":"text","text":"report"}]}]},"toolUseResult":{"status":"completed","totalDurationMs":26862,"totalTokens":21308,"totalToolUseCount":4}}""",
        )
        val agent = state.agent()
        assertEquals(0, state.runningAgents)
        assertNull(agent.startedAt)
        assertEquals(21_308L, agent.tokens)
        assertEquals(4, agent.tools)
        assertEquals(26_862L, agent.elapsedMs(running = false, now = 1_000_000))
        // Cut off with no result and no numbers: stopped, nothing to show.
        val cut = SessionReducer.stopAgents(start().feed(agentCall), 9_000)
        assertEquals(0, cut.runningAgents)
        assertEquals("stopped", cut.agent().status)
        assertNull(cut.agent().elapsedMs(running = false, now = 9_000))
    }

    @Test fun backgroundAgentRunsUntilItsNotification() {
        val launched = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_A","type":"tool_result","content":[{"type":"text","text":"Async agent launched successfully.\nagentId: a1"}]}]},"parent_tool_use_id":null}"""
        var state = SessionReducer.timeAgents(start().feed(agentCall), 1_000).feed(launched)
        state = SessionReducer.timeAgents(state, 2_000)
        assertTrue(state.agent().background)
        assertEquals(1, state.runningAgents)
        assertNull(state.agent().endedAt)
        state = SessionReducer.timeAgents(
            state.feed("""{"type":"system","subtype":"task_notification","tool_use_id":"toolu_A","status":"completed","usage":{"total_tokens":12195,"tool_uses":3,"duration_ms":16057}}"""),
            20_000,
        )
        assertEquals(0, state.runningAgents)
        assertEquals(16_057L, state.agent().elapsedMs(running = false, now = 20_000))
        // Replayed (never stamped), the same launch is not counted as running.
        assertEquals(0, start().feed(agentCall, launched).runningAgents)
        // The process ending stops a running one at that moment.
        val stopped = SessionReducer.stopAgents(SessionReducer.timeAgents(start().feed(agentCall), 1_000).feed(launched), 7_000)
        assertEquals(0, stopped.runningAgents)
        assertEquals(6_000L, stopped.agent().elapsedMs(running = false, now = 50_000))
    }

    // --- rewind ---

    @Test fun rewindDropsTheMessageAndAfterAndRefillsTheBox() {
        var state = SessionReducer.onUserMessage(start(), "Remember APPLE", "u1")
        state = state.feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"ok"}]}}""",
            """{"type":"result","is_error":false,"result":"ok"}""",
        )
        state = SessionReducer.onUserMessage(state, "Remember PEAR", "u2")
        state = state.feed(
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"ok"}]}}""",
            """{"type":"result","is_error":false,"result":"ok"}""",
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-rewind-7","response":{"rewound":true,"targetMessageUuid":"u2","prefillText":"Remember PEAR","precedingAssistantUuid":"x"}}}""",
        )
        assertEquals(listOf("Remember APPLE", "ok", "Rewound · files unchanged"), state.items.map {
            when (it) { is ChatItem.User -> it.text; is ChatItem.Assistant -> it.text; is ChatItem.Notice -> it.text; else -> "" }
        })
        assertEquals("Remember PEAR", state.draft?.text)
        assertEquals(state.items.size, state.items.map { it.id }.toSet().size)
        // New items after a rewind never reuse an id still on screen.
        val next = SessionReducer.onUserMessage(state, "again", "u3")
        assertEquals(next.items.size, next.items.map { it.id }.toSet().size)
    }

    private val photo = Attachment("/data/data/app.pocket/files/home/.pocketrocket/attachments/s/1-shot.png", "shot.png", "image/png", 42)

    @Test fun rewindPutsTheAttachmentsBackInTheBoxToo() {
        val state = SessionReducer.onUserMessage(start(), "What is this?", "u1", listOf(photo)).feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-rewind-1","response":{"rewound":true,"targetMessageUuid":"u1","prefillText":"What is this?"}}}""",
        )
        assertEquals(Draft("What is this?", 1, listOf(photo)), state.draft)
        assertEquals("Rewound · files unchanged", (state.items.single() as ChatItem.Notice).text)
    }

    @Test fun editRefillsTheBoxAtOnceAndUndoPutsItAllBack() {
        var state = SessionReducer.onUserMessage(start(), "Remember APPLE", "u1")
        state = state.feed("""{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"ok"}]}}""")
        state = SessionReducer.onUserMessage(state, "What is this?", "u2", listOf(photo))
        state = state.feed("""{"type":"result","is_error":false,"result":"A cat"}""")
        val before = state.items
        val edited = SessionReducer.onEdit(state, before[2] as ChatItem.User)
        assertEquals(before.take(2), edited.items)
        assertEquals(Draft("What is this?", 1, listOf(photo)), edited.draft)
        assertEquals("u2", edited.rewind?.uuid)

        val undone = SessionReducer.onEditUndone(edited)
        assertEquals(before, undone.items)
        assertNull(undone.rewind)
        assertTrue(undone.draft!!.undo)
        assertEquals(2, undone.draft!!.key)
    }

    @Test fun editSentThenAnsweredSaysRewoundOnce() {
        var state = SessionReducer.onUserMessage(start(), "Remember APPLE", "u1")
        state = state.feed("""{"type":"result","is_error":false,"result":"ok"}""")
        state = SessionReducer.onEdit(state, state.items[0] as ChatItem.User)
        state = SessionReducer.onRewindSent(state)
        assertNull(state.rewind)
        state = state.feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-rewind-2","response":{"rewound":true,"targetMessageUuid":"u1","prefillText":"Remember APPLE"}}}""",
        )
        assertEquals(listOf("Rewound · files unchanged"), state.items.map { (it as ChatItem.Notice).text })
        // The answer leaves the box alone: the user may already be typing in it.
        assertEquals(Draft("Remember APPLE", 1), state.draft)
    }

    @Test fun retryDropsTheMessageWithoutTouchingTheBox() {
        var state = SessionReducer.onUserMessage(start(), "Remember APPLE", "u1", listOf(photo))
        state = state.feed("""{"type":"result","is_error":false,"result":"ok"}""")
        state = SessionReducer.onRetry(state, state.items[0] as ChatItem.User)
        assertEquals(listOf("Rewound · files unchanged"), state.items.map { (it as ChatItem.Notice).text })
        assertNull(state.draft)
        assertNull(state.rewind)
    }

    @Test fun refusedRewindSaysWhy() {
        val state = start().feed(
            """{"type":"control_response","response":{"subtype":"error","request_id":"pr-rewind-1","error":"Message not found"}}""",
        )
        assertEquals("Couldn't rewind: Message not found", (state.items.single() as ChatItem.Notice).text)
    }

    @Test fun modelNames() {
        assertEquals("Haiku 4.5", prettyModel("claude-haiku-4-5-20251001"))
        assertEquals("Opus 5", prettyModel("claude-opus-5"))
        assertEquals("Sonnet 5.5", prettyModel("claude-sonnet-5-5[1m]"))
        assertEquals("custom-model", prettyModel("custom-model"))
    }

    @Test fun anAnswerFromRocketSaysSoInTheChat() {
        val asked = SessionState(
            id = "s", folder = "~/a", title = "a", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
            status = SessionStatus.WaitingForPermission,
            permission = PendingPermission("r", "Bash", "npm test", org.json.JSONObject(), null),
        )
        val notice = SessionReducer.onPermissionAnswered(asked, allow = true, byRocket = "Allowed by voice · 09:14").items.last() as ChatItem.Notice
        assertEquals("Allowed by voice · 09:14", notice.text)
        assertTrue(notice.byRocket)
        assertFalse((SessionReducer.onPermissionAnswered(asked, allow = true).items.last() as ChatItem.Notice).byRocket)
    }

    @Test fun aStaleAnswerCantHitALaterRequest() {
        val later = SessionState(
            id = "s", folder = "~/a", title = "a", demo = false, profile = ClaudeProfile.Default, conversationId = "c",
            status = SessionStatus.WaitingForPermission,
            permission = PendingPermission("later", "Bash", "rm x", org.json.JSONObject(), null),
        )
        val session = Session(later, connect = { throw AssertionError("not started") })
        assertFalse(session.answerPermissionIfOpen("earlier", true))
        assertEquals("later", session.state.value.permission?.requestId)
        assertTrue(session.answerPermissionIfOpen("later", false, "Denied by voice · 09:14"))
        assertEquals(null, session.state.value.permission)
    }
}
