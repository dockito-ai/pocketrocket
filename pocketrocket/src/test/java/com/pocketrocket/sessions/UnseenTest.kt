package com.pocketrocket.sessions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnseenTest {

    private fun state(id: String, status: SessionStatus) = SessionState(
        id = id, folder = "/data/data/app.pocket/files/home", title = "home", demo = false,
        profile = ClaudeProfile.Default, conversationId = "c", status = status,
    )

    private fun move(id: String, from: SessionStatus, to: SessionStatus) = Unseen.onChange(state(id, from), state(id, to))

    @After fun reset() {
        Unseen.setOnScreen(null)
        Unseen.ids.value.forEach(Unseen::forget)
    }

    @Test fun finishingOffScreenIsMarkedUntilOpened() {
        move("a", SessionStatus.Working, SessionStatus.Done)
        assertEquals(setOf("a"), Unseen.ids.value)
        Unseen.setOnScreen("a")
        assertTrue(Unseen.ids.value.isEmpty())
    }

    @Test fun questionsAndErrorsAreMarked() {
        move("a", SessionStatus.Working, SessionStatus.WaitingForPermission)
        move("b", SessionStatus.Working, SessionStatus.Error)
        assertEquals(setOf("a", "b"), Unseen.ids.value)
    }

    @Test fun newsInTheSessionOnScreenIsSeenAlready() {
        Unseen.setOnScreen("a")
        move("a", SessionStatus.Working, SessionStatus.Done)
        assertTrue(Unseen.ids.value.isEmpty())
    }

    @Test fun startingWorkIsNotNews() {
        move("a", SessionStatus.Done, SessionStatus.Working)
        move("b", SessionStatus.Paused, SessionStatus.Starting)
        assertTrue(Unseen.ids.value.isEmpty())
    }
}
