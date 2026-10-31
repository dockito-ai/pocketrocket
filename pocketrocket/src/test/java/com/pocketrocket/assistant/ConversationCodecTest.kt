package com.pocketrocket.assistant

import com.pocketrocket.sessions.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCodecTest {

    private val command = RocketCard.Detail("Command", "curl -H 'Authorization: secret' x", "Reason: because", "s1", "api")

    @Test fun keepsQuestionsRepliesNotesAndPlainCards() {
        val turns = listOf(
            Turn("what's happening", Reply("One is working.", "One is working", RocketCard.Status(listOf(RocketCard.Status.Row("api", SessionStatus.Working, "s1")))), "Not spoken"),
            Turn("how full is api", Reply("api is 25% full.", card = RocketCard.Context(25, "50k of 200k tokens", "s1", "api"))),
        )
        assertEquals(turns, ConversationCodec.decode(ConversationCodec.encode(turns)))
    }

    @Test fun neverWritesWhatASessionRanOrPrinted() {
        val saved = ConversationCodec.encode(listOf(Turn("what does api want", Reply("api wants to run a command.", card = command))))
        assertFalse(saved.contains("secret"))
        assertFalse(saved.contains("because"))
        val card = ConversationCodec.decode(saved).single().reply.card as RocketCard.Detail
        assertEquals("", card.body)
        assertEquals("s1", card.sessionId)
    }

    @Test fun keepsOnlyTheNewestTurns() {
        val many = (1..ConversationCodec.MAX_TURNS + 5).map { Turn("q$it", Reply("a$it")) }
        val back = ConversationCodec.decode(ConversationCodec.encode(many))
        assertEquals(ConversationCodec.MAX_TURNS, back.size)
        assertEquals("q${ConversationCodec.MAX_TURNS + 5}", back.last().question)
    }

    @Test fun aDamagedFileIsAnEmptyConversation() {
        assertTrue(ConversationCodec.decode("not json").isEmpty())
        assertEquals(1, ConversationCodec.decode("""[{"bad":1},{"question":"q","text":"a"}]""").size)
    }

    @Test fun aLockedPhoneGetsNoCodeAndNoCommandInTheVoice() {
        val locked = Reply("api wants to run a command.", "api wants to push main", command).forLockedPhone()
        assertEquals("api wants to run a command. Unlock to see it.", locked.text)
        assertEquals(null, locked.card)
        assertFalse(locked.speech.contains("push"))
    }

    @Test fun aLockedPhoneStillGetsStatus() {
        val status = Reply("One is working.", card = RocketCard.Status(emptyList()))
        assertEquals(status, status.forLockedPhone())
    }

    @Test fun aCommandInWordsIsNotWrittenEither() {
        val reply = Reply("api wants to run a command.", "api wants to push feat auth to origin.", RocketCard.Detail("Command", "git push origin feat/auth", null, "api", "api"))
        val text = ConversationCodec.encode(listOf(Turn("what does api want", reply)))
        assertFalse(text.contains("feat"))
    }
}
