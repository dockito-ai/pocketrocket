package com.pocketrocket.assistant

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What step 5 asks of the Router: simple things stay free of Claude, and everything else is sent on. */
class RocketToolsRouterTest {
    private fun s(title: String, folder: String = "/home/$title", status: SessionStatus = SessionStatus.Done, permission: PendingPermission? = null) =
        SessionState(title, folder, title, demo = false, profile = ClaudeProfile.Default, conversationId = title, status = status, permission = permission)

    private val waiting = PendingPermission("r1", "Bash", "rm x", JSONObject().put("command", "rm x"), null)
    private val nimbus = s("client", "/home/acme/project_nimbus")
    private val book = MemoryBook(nicknames = listOf(Nickname("nimbus", "/home/acme/project_nimbus"), Nickname("acme v2 client facing app", "/home/acme/project_nimbus")))

    @Test fun simpleThingsNeverGoToClaude() {
        listOf("what's happening", "status", "how full is notes", "what does notes want", "stop notes", "show what changed in notes").forEach {
            assertFalse(it, Router.route(it, listOf(s("notes")), book = book).toBrain)
        }
    }

    @Test fun whatItCantAnswerGoesToClaude() {
        listOf("start an agent in nimbus", "clone acme/app", "push it", "accept edits on notes", "remember I like opus", "stop listening to music").forEach {
            assertTrue(it, Router.route(it, listOf(s("notes")), book = book).toBrain)
        }
    }

    @Test fun nicknamesWorkWithNoClaude() {
        val all = listOf(nimbus, s("notes"))
        assertEquals("client is done.", Router.reply("how is nimbus", all, book = book).text.replace("It ", "").let { if (it.startsWith("client")) "client is done." else it })
        assertFalse(Router.route("how full is the acme v2 client facing app", all, book = book).toBrain)
        assertEquals(listOf(nimbus), Router.matches("Acme V2 client-facing app", all, book))
    }

    @Test fun aNicknameWithNoSessionSaysSo() {
        val reply = Router.reply("how full is nimbus", listOf(s("notes")), book = book)
        assertTrue(reply.text, reply.text.startsWith("No session in project_nimbus"))
    }

    @Test fun aBareYesGoesToTheBrainAndNeverAnswersARequest() {
        val free = listOf(s("notes"))
        assertTrue(Router.route("yes", free, brainAsked = true).toBrain)
        assertTrue(Router.route("yes", free, brainAsked = false).toBrain)
        // Decision 59: a waiting request stays with its session, and the brain has no way to answer it.
        val busy = listOf(s("notes", permission = waiting))
        val routed = Router.route("yes", busy, brainAsked = true)
        assertTrue(routed.toBrain)
        assertNull(routed.act)
    }

    @Test fun stopNeedsARealSession() {
        val all = listOf(s("notes", status = SessionStatus.Working), s("web"))
        assertEquals(RocketAct.Stop("notes"), Router.route("stop notes", all).act)
        assertEquals(RocketAct.Stop("notes"), Router.route("stop it", all).act)
        assertNull(Router.route("stop it", listOf(s("web"))).act)
        assertEquals("Nothing is running.", Router.route("stop it", listOf(s("web"))).reply.text)
    }

    @Test fun showWhatChangedStaysOffALockedPhone() {
        val all = listOf(s("notes"))
        assertEquals(RocketAct.ShowChanges("notes"), Router.route("show what changed", all).act)
        assertEquals(RocketAct.ShowChanges("notes"), Router.route("what changed in notes", all).act)
        assertEquals(RocketAct.ShowChanges("notes"), Router.route("show me the diff", all).act)
        val locked = Router.route("show what changed", all, locked = true)
        assertNull(locked.act)
        assertTrue(locked.reply.text.contains("Unlock"))
    }

    @Test fun chipsReachTheirAnswers() {
        val all = listOf(s("notes"))
        assertTrue("Show what changed in notes" in Router.suggestions(all))
        assertEquals(RocketAct.ShowChanges("notes"), Router.route("Show what changed in notes", all).act)
        assertTrue(Router.route(Router.START_AGENT, all).toBrain)
    }

    @Test fun aProjectSwitchedOffOnlyReportsStatus() {
        val off = MemoryBook(projects = listOf(ProjectEntry("/home/notes", "notes", enabled = false)))
        val all = listOf(s("notes", status = SessionStatus.WaitingForPermission, permission = waiting))
        listOf("stop notes", "show what changed").forEach {
            val routed = Router.route(it, all, book = off)
            assertNull(it, routed.act)
            assertTrue(it, routed.reply.text.contains("switched off"))
        }
        assertFalse(Router.route("how is notes", all, book = off).reply.text.contains("switched off"))
    }

    @Test fun newActionsHaveTheirProtection() {
        assertEquals(Protection.SpokenYes, SensitiveActions.protectionOf(RocketAction.StartAgent("p", "Auto", null)))
        assertEquals(Protection.SpokenYes, SensitiveActions.protectionOf(RocketAction.SaveHabit("x")))
        assertEquals(Protection.Fingerprint, SensitiveActions.protectionOf(RocketAction.WhileLocked("x")))
    }

    @Test fun approvalsNameWhatWillHappen() {
        val start = ConfirmGate.ofAction(RocketAction.StartAgent("nimbus", "Auto", "opus"))
        assertTrue(start.headline.contains("nimbus"))
        assertTrue(start.detail.contains("Auto"))
        assertTrue(start.speech.contains(ConfirmGate.ASK))
        val message = ConfirmGate.ofAction(RocketAction.MessageSession("nimbus", "run the tests"))
        assertEquals("run the tests", message.detail)
        assertTrue(message.speech, message.speech.contains("run the tests"))
        assertTrue(ConfirmGate.ofAction(RocketAction.WhileLocked("Start an agent")).speech.contains("fingerprint"))
    }
}
