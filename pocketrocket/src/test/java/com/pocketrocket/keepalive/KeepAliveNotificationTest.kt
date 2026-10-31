package com.pocketrocket.keepalive

import com.pocketrocket.keepalive.KeepAliveNotification.Group
import com.pocketrocket.sessions.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAliveNotificationTest {

    private fun live(title: String, status: SessionStatus, alive: Boolean = true, demo: Boolean = false) =
        SessionKeepAlive.Live(title, title, alive && !demo, status, title, demo)

    @Test
    fun `most urgent first, paused and demo left out, stopped errors kept`() {
        val shown = KeepAliveNotification.shown(
            listOf(
                live("done", SessionStatus.Done),
                live("work", SessionStatus.Working),
                live("paused", SessionStatus.Paused, alive = false),
                live("demo", SessionStatus.Error, demo = true),
                live("gone", SessionStatus.Done, alive = false),
                live("crash", SessionStatus.Error, alive = false),
                live("ask", SessionStatus.WaitingForPermission),
            ),
        )
        assertEquals(listOf("ask", "crash", "work", "done"), shown.map { it.title })
    }

    @Test
    fun `headline names a lone session and counts several`() {
        fun headline(vararg sessions: SessionKeepAlive.Live) = KeepAliveNotification.headline(KeepAliveNotification.shown(sessions.toList()))
        assertEquals("api needs you", headline(live("api", SessionStatus.WaitingForPermission), live("blog", SessionStatus.Working)))
        assertEquals("2 sessions need you", headline(live("a", SessionStatus.WaitingForPermission), live("b", SessionStatus.WaitingForPermission)))
        assertEquals("blog hit an error", headline(live("blog", SessionStatus.Error), live("a", SessionStatus.Working)))
        assertEquals("api is working", headline(live("api", SessionStatus.Starting), live("b", SessionStatus.Done)))
        assertEquals("3 sessions working", headline(live("a", SessionStatus.Working), live("b", SessionStatus.Working), live("c", SessionStatus.Starting)))
        assertEquals("All done", headline(live("a", SessionStatus.Done), live("b", SessionStatus.WaitingForInput)))
    }

    @Test
    fun `counts per state skip empty ones`() {
        val counts = KeepAliveNotification.counts(
            KeepAliveNotification.shown(
                listOf(live("a", SessionStatus.Working), live("b", SessionStatus.Error), live("c", SessionStatus.Working), live("d", SessionStatus.Done)),
            ),
        )
        assertEquals(listOf(Group.Error to 1, Group.Working to 2, Group.Done to 1), counts)
        assertEquals("needs you", Group.NeedsYou.words(1))
        assertEquals("need you", Group.NeedsYou.words(2))
        assertEquals("errors", Group.Error.words(2))
    }

    @Test
    fun `a finished turn with agents or shells running counts as in progress`() {
        val agents = SessionKeepAlive.Live("api", "api", true, SessionStatus.Done, "api", agents = 1)
        val both = SessionKeepAlive.Live("blog", "blog", true, SessionStatus.Done, "blog", shells = 2, agents = 1)
        val done = live("docs", SessionStatus.Done)
        assertEquals(SessionStatus.Working, agents.shown)
        assertEquals(SessionStatus.Done, done.shown)
        val shown = KeepAliveNotification.shown(listOf(done, agents))
        assertEquals(listOf("api", "docs"), shown.map { it.title })
        assertEquals("api is working", KeepAliveNotification.headline(shown))
        assertEquals(listOf(Group.Working to 1, Group.Done to 1), KeepAliveNotification.counts(shown))
        assertEquals("1 agent running", KeepAliveNotification.running(shown))
        assertEquals("2 agents · 2 shells running", KeepAliveNotification.running(listOf(agents, both)))
        assertEquals(null, KeepAliveNotification.running(listOf(done)))
        assertEquals("working · 1 agent", KeepAliveNotification.detail(agents))
        assertEquals("working · 1 agent · 2 shells", KeepAliveNotification.detail(both))
        assertEquals("done", KeepAliveNotification.detail(done))
        // A question still comes first and reads as one.
        val asking = SessionKeepAlive.Live("ask", "ask", true, SessionStatus.WaitingForPermission, "ask", agents = 1)
        assertEquals("ask needs you", KeepAliveNotification.headline(KeepAliveNotification.shown(listOf(agents, asking))))
    }
}
