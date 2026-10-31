package com.pocketrocket.sessions

import com.pocketrocket.sessions.history.Transcript
import com.pocketrocket.sessions.protocol.StreamJsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ControlsHistoryTest {

    private fun state(configDir: String? = null) = SessionState(
        id = "s", folder = "/w/ctl", title = "ctl", demo = false,
        profile = ClaudeProfile("p", configDir), conversationId = "c",
    )

    private fun SessionState.feed(vararg lines: String): SessionState =
        lines.fold(this) { s, line -> StreamJsonParser.parse(line).fold(s, SessionReducer::onEvent) }

    private fun texts(items: List<ChatItem>) = items.map {
        when (it) { is ChatItem.User -> "U:" + it.text; is ChatItem.Assistant -> "A:" + it.text; is ChatItem.Notice -> "N:" + it.text; else -> it.toString() }
    }

    @Test fun historySkipsARewoundBranchAndTheCompactSummary() {
        val dir = Files.createTempDirectory("claude").toFile()
        val project = dir.resolve("projects/-w-ctl").apply { mkdirs() }
        project.resolve("c.jsonl").writeText(
            listOf(
                """{"parentUuid":null,"type":"user","uuid":"u1","message":{"role":"user","content":"Remember APPLE"}}""",
                """{"parentUuid":"u1","type":"assistant","uuid":"a1","message":{"id":"m1","content":[{"type":"text","text":"ok"}]}}""",
                """{"parentUuid":"a1","type":"user","uuid":"u2","message":{"role":"user","content":"Remember PEAR"}}""",
                """{"parentUuid":"u2","type":"assistant","uuid":"a2","message":{"id":"m2","content":[{"type":"text","text":"ok pear"}]}}""",
                // Rewound to u2: the next prompt hangs off a1.
                """{"parentUuid":"a1","type":"user","uuid":"u3","message":{"role":"user","content":"List the words"}}""",
                """{"parentUuid":"u3","type":"assistant","uuid":"a3","message":{"id":"m3","content":[{"type":"text","text":"APPLE"}]}}""",
                """{"parentUuid":null,"logicalParentUuid":"a3","type":"system","subtype":"compact_boundary","uuid":"b1"}""",
                """{"parentUuid":"b1","type":"user","uuid":"u4","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued..."}}""",
                """{"parentUuid":"u4","type":"user","uuid":"u5","message":{"role":"user","content":"And now?"}}""",
            ).joinToString("\n"),
        )
        val items = Transcript.load(state(dir.path))
        assertEquals(listOf("U:Remember APPLE", "A:ok", "U:List the words", "A:APPLE", "U:And now?"), texts(items))
        assertEquals("u5", (items.last() as ChatItem.User).uuid)
        dir.deleteRecursively()
    }

    @Test fun afterACompactionTheStaleSummaryCountIsIgnoredUntilTheNextReply() {
        val compacted = state().copy(context = ContextUsage(28000, 1_000_000)).feed(
            """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":28000,"post_tokens":5800}}""",
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-context-9","response":{"totalTokens":28000,"maxTokens":1000000}}}""",
        )
        assertEquals(5800L, compacted.context?.used)
        val replied = compacted.feed(
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":6100}}}""",
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-context-10","response":{"totalTokens":6150,"maxTokens":1000000}}}""",
        )
        assertEquals(6150L, replied.context?.used)
    }

    @Test fun keepPlanningEndsTheTurnWithoutSayingInterrupted() {
        val asked = state().feed(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","input":{"plan":"x"}}}""",
        )
        val kept = SessionReducer.onPermissionAnswered(asked, allow = false).feed(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"terminal_reason":"aborted_tools"}""",
        )
        assertEquals(listOf("N:Kept planning"), texts(kept.items))
        assertEquals(SessionStatus.Done, kept.status)
    }

    @Test fun rewindingASlashCommandRefillsWhatWasTyped() {
        val state = SessionReducer.onUserMessage(state(), "/compact", "u9").feed(
            """{"type":"control_response","response":{"subtype":"success","request_id":"pr-rewind-2","response":{"rewound":true,"targetMessageUuid":"u9","prefillText":"<command-name>/compact</command-name>"}}}""",
        )
        assertEquals("/compact", state.draft?.text)
    }
}
