package com.pocketrocket.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerTextTest {
    @Test fun intoAnEmptyBox() {
        assertEquals(Inserted("hello there", 0, 11), insertAtCursor("", 0, 0, "hello there"))
    }

    @Test fun appendedAfterTypedTextWithASpace() {
        assertEquals(Inserted("fix it now please", 7, 17), insertAtCursor("fix it", 6, 6, "now please"))
    }

    @Test fun noDoubleSpaceAfterASpaceOrNewline() {
        assertEquals("fix it now", insertAtCursor("fix it ", 7, 7, "now").text)
        assertEquals("fix it\nnow", insertAtCursor("fix it\n", 7, 7, "now").text)
    }

    @Test fun inTheMiddleGetsSpacesBothSides() {
        val out = insertAtCursor("fixtests", 3, 3, "the")
        assertEquals("fix the tests", out.text)
        assertEquals("the", out.text.substring(out.start, out.end))
    }

    @Test fun replacesASelection() {
        assertEquals("make it blue", insertAtCursor("make it red", 8, 11, "blue").text)
        assertEquals("make it blue", insertAtCursor("make it red", 11, 8, "blue").text)
    }

    @Test fun outOfRangeCursorIsClamped() {
        assertEquals("ab cd", insertAtCursor("ab", 9, 9, "cd").text)
    }

    @Test fun blankWordsChangeNothing() {
        assertEquals("ab", insertAtCursor("ab", 1, 1, "  ").text)
    }

    @Test fun chipShowsModelAndEffort() {
        assertEquals("Opus 5.5 · medium", modelChipText("Opus 5.5", "medium", listOf("low", "medium", "high")))
    }

    @Test fun chipLeavesEffortOutWhenTheModelHasNone() {
        assertEquals("Haiku 4.5", modelChipText("Haiku 4.5", "medium", emptyList()))
        assertEquals("Opus 5.5", modelChipText("Opus 5.5", null, listOf("low")))
        assertEquals("Opus 5.5", modelChipText("Opus 5.5", " ", listOf("low")))
    }

    @Test fun chipBeforeTheModelIsKnown() {
        assertEquals("Model", modelChipText(null, null, emptyList()))
    }
}
