package com.pocketrocket.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityCodecTest {

    private val entry = ActivityEntry(5L, "Allowed api's request", "By voice", "curl -H 'token: ghp_secret' x", "api")

    @Test fun theCommandIsNeverWritten() {
        val text = ActivityCodec.encode(listOf(entry))
        assertFalse(text.contains("ghp_secret"))
        assertFalse(text.contains("curl"))
    }

    @Test fun theRestSurvivesARestart() {
        val back = ActivityCodec.decode(ActivityCodec.encode(listOf(entry))).single()
        assertEquals("Allowed api's request", back.text)
        assertEquals("By voice", back.how)
        assertEquals(5L, back.at)
        assertEquals("api", back.sessionId)
        assertNull(back.command)
    }

    @Test fun itKeepsTheLatestOnly() {
        val many = (1..ActivityCodec.MAX_ENTRIES + 5).map { ActivityEntry(it.toLong(), "t", "h") }
        val kept = ActivityCodec.decode(ActivityCodec.encode(many))
        assertEquals(ActivityCodec.MAX_ENTRIES, kept.size)
        assertEquals(6L, kept.first().at)
    }

    @Test fun whatCantBeReadIsDropped() {
        assertEquals(0, ActivityCodec.decode("nonsense").size)
        assertEquals(1, ActivityCodec.decode("""[{"at":1,"text":"a","how":"b","session":null},{"bad":1}]""").size)
    }
}
