package com.pocketrocket.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test fun guessesReplaceEachOtherWithinARun() {
        val t = Transcript()
        assertTrue(t.guess("make"))
        assertTrue(t.guess("make the cards"))
        assertEquals("", t.settled)
        assertEquals("make the cards", t.partial)
        assertEquals("make the cards", t.text)
    }

    @Test fun sameGuessAgainIsNotNewSpeech() {
        val t = Transcript()
        t.guess("hello")
        assertFalse(t.guess("hello "))
    }

    @Test fun settledRunsJoinAcrossRestarts() {
        val t = Transcript()
        t.guess("make the")
        t.settle("make the cards")
        t.guess("in settings")
        t.settle("in settings bigger")
        assertEquals("make the cards in settings bigger", t.settled)
        assertEquals("", t.partial)
    }

    @Test fun aRunEndingWithoutResultKeepsItsLastGuess() {
        val t = Transcript()
        t.settle("first part")
        t.guess("second part")
        t.endRun()
        assertEquals("first part second part", t.text)
    }

    @Test fun emptyFinalKeepsTheGuess() {
        val t = Transcript()
        t.guess("words")
        t.settle("  ")
        assertEquals("words", t.settled)
    }

    @Test fun aSilentRunAddsNothing() {
        val t = Transcript()
        t.settle("one")
        t.endRun()
        t.endRun()
        assertEquals("one", t.text)
    }

    @Test fun textShowsSettledThenGuess() {
        val t = Transcript()
        t.settle("one two")
        t.guess("three")
        assertEquals("one two three", t.text)
        t.clear()
        assertTrue(t.isEmpty)
    }

    @Test fun asksBeforeDiscardingMoreThanASentence() {
        assertFalse(Transcript.worthAsking(""))
        assertFalse(Transcript.worthAsking("fix the button"))
        assertFalse(Transcript.worthAsking("Fix the button."))
        assertTrue(Transcript.worthAsking("Fix the button. Then run the tests"))
        assertTrue(Transcript.worthAsking("one two three four five six seven eight nine ten eleven twelve thirteen"))
    }
}
