package com.pocketrocket.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureTextTest {
    @Test fun adds_a_full_stop_after_a_bare_detail() {
        assertEquals("Couldn't back up: /data/data/x/locked/f.", endSentence("Couldn't back up: /data/data/x/locked/f"))
    }

    @Test fun keeps_existing_punctuation() {
        assertEquals("Cancelled.", endSentence("Cancelled."))
        assertEquals("Why?", endSentence("Why?"))
    }
}
