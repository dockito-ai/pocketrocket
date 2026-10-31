package com.pocketrocket.ui.rocket

import com.pocketrocket.assistant.Reply
import com.pocketrocket.assistant.RocketCard
import com.pocketrocket.assistant.Voices
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.ui.chat.MarkdownBlocks
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTextTest {

    @Test fun aPlainReplyIsJustItsText() {
        assertEquals("All clear.", ReplyText.markdown(Reply("All clear.")))
    }

    @Test fun aCommandShowsInlineAsCode() {
        val reply = Reply("api wants to run a command.", card = RocketCard.Detail("Command", "git push origin main", "Reason: ship it", "s1", "api"))
        val blocks = MarkdownBlocks.parse(ReplyText.markdown(reply))
        assertEquals(MarkdownBlocks.Block.Paragraph("api wants to run a command."), blocks[0])
        assertEquals(MarkdownBlocks.Block.Code("git push origin main"), blocks[1])
        assertEquals(MarkdownBlocks.Block.Paragraph("Reason: ship it"), blocks[2])
    }

    @Test fun aFenceInsideOutputDoesntEndTheBlockEarly() {
        val reply = Reply("Here.", card = RocketCard.Detail("Last output", "a\n```\nb", null, "s1", "api"))
        val code = MarkdownBlocks.parse(ReplyText.markdown(reply)).filterIsInstance<MarkdownBlocks.Block.Code>()
        assertEquals(1, code.size)
        assertTrue(code.single().text.endsWith("b"))
    }

    @Test fun claudesLongAnswerIsShownWholeInPlaceOfItsLead() {
        val reply = Reply("First.", card = RocketCard.Detail("Rocket", "First. Then a `git` command.", null, "", ""))
        assertEquals("First. Then a `git` command.", ReplyText.markdown(reply))
    }

    @Test fun cardsLostToARestartSaySo() {
        val reply = Reply("api wants to run a command.", card = RocketCard.Detail("Command", "", null, "s1", "api"))
        assertTrue(ReplyText.markdown(reply).endsWith(ReplyText.NOT_KEPT))
    }

    @Test fun statusIsAListAndChangesAreDiffs() {
        val status = Reply("Two are working.", card = RocketCard.Status(listOf(RocketCard.Status.Row("api", SessionStatus.Working, "a"))))
        assertEquals("Two are working.\n\n- **api**: Working", ReplyText.markdown(status))
        val changes = Reply("One file changed.", card = RocketCard.Changes(listOf(RocketCard.Changes.File("src/a.kt", 1, 2, listOf("+x", "-y"))), "a", "api"))
        val blocks = MarkdownBlocks.parse(ReplyText.markdown(changes))
        assertEquals(MarkdownBlocks.Block.Code("+x\n-y"), blocks.last())
    }

    @Test fun theBestVoiceIsThePhonesLanguageAndAccentThenQuality() {
        data class V(val locale: Locale, val quality: Int)
        val usHigh = V(Locale.US, 400)
        val inNormal = V(Locale("en", "IN"), 300)
        val inHigh = V(Locale("en", "IN"), 400)
        val de = V(Locale.GERMANY, 500)
        val voices = listOf(usHigh, inNormal, inHigh, de)
        assertEquals(inHigh, Voices.best(voices, Locale("en", "IN"), { it.locale }, { it.quality }))
        assertEquals(usHigh, Voices.best(voices, Locale.UK, { it.locale }, { it.quality }))
        assertEquals(de, Voices.best(voices, Locale.JAPAN, { it.locale }, { it.quality }))
        assertNull(Voices.best(emptyList<V>(), Locale.US, { it.locale }, { it.quality }))
    }
}
