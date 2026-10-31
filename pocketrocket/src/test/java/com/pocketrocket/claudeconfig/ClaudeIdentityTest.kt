package com.pocketrocket.claudeconfig

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.protocol.StreamJsonParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ClaudeIdentityTest {

    @Test fun readsAccountAndVersion() {
        val identity = ClaudeIdentity.fromInitialize(
            JSONObject("""{"account":{"email":"a@b.c","organization":"Org","subscriptionType":"max","tokenSource":"SECRETSOURCE"},"claude_code_version":"2.1.314"}"""),
        )!!
        assertEquals("a@b.c", identity.account?.email)
        assertEquals("Org", identity.account?.organization)
        assertEquals("Claude Max", identity.account?.planLabel)
        assertEquals("2.1.314", identity.version)
        assertFalse(identity.toString().contains("SECRETSOURCE"))
    }

    @Test fun aPlanGivenWholeIsNotPrefixedTwice() {
        // Measured on a real account, 2.1.312: initialize says "subscription":"Claude Max".
        val identity = ClaudeIdentity.fromInitialize(JSONObject("""{"account":{"email":"a@b.c","subscription":"Claude Max"}}"""))!!
        assertEquals("Claude Max", identity.account?.planLabel)
    }

    @Test fun aPersonalOrganizationIsNotATeam() {
        // Measured on a real account: the Status sheet shows teamName, which hides this default.
        val identity = ClaudeIdentity.fromInitialize(JSONObject("""{"account":{"email":"a@b.c","organization":"a@b.c's Organization"}}"""))!!
        assertNull(identity.account?.teamName)
    }

    @Test fun noLoginHasAVersionAndNoAccount() {
        // Measured with an empty config dir.
        val identity = ClaudeIdentity.fromInitialize(JSONObject("""{"account":{"tokenSource":"none","apiProvider":"firstParty"},"claude_code_version":"2.1.314"}"""))!!
        assertNull(identity.account)
        assertEquals("2.1.314", identity.version)
    }

    @Test fun nothingUsefulIsNull() = assertNull(ClaudeIdentity.fromInitialize(JSONObject("{}")))

    private fun start() = SessionState(id = "s", folder = "/f", title = "f", demo = false, profile = ClaudeProfile.Default, conversationId = "c")

    @Test fun sessionKeepsItFromTheHandshake() {
        val line = """{"type":"control_response","response":{"subtype":"success","request_id":"pr-init","response":{"account":{"email":"a@b.c"},"claude_code_version":"2.1.314"}}}"""
        val state = StreamJsonParser.parse(line).fold(start(), SessionReducer::onEvent)
        assertEquals("a@b.c", state.identity?.account?.email)
    }

    @Test fun configRepliesLeaveTheSessionAlone() {
        val before = start()
        val line = """{"type":"control_response","response":{"subtype":"error","request_id":"${SessionReducer.CONFIG_ID}1","error":"nope"}}"""
        assertSame(before, StreamJsonParser.parse(line).fold(before, SessionReducer::onEvent))
    }
}
