package com.pocketrocket.assistant

import com.pocketrocket.sessions.ClaudeProfile
import com.pocketrocket.sessions.ContextUsage
import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private fun session(
        title: String,
        status: SessionStatus = SessionStatus.Working,
        folder: String = "~/code/$title",
        tool: String? = null,
        summary: String = "npm test",
        context: ContextUsage? = null,
    ) = SessionState(
        id = title, folder = folder, title = title, demo = false, profile = ClaudeProfile.Default, conversationId = title,
        status = if (tool != null) SessionStatus.WaitingForPermission else status,
        permission = tool?.let { PendingPermission("r-$title", it, summary, JSONObject(), null) },
        context = context,
    )

    private fun answer(question: String, sessions: List<SessionState>) = Router.reply(question, sessions).text

    private val api = session("api-server", tool = "Bash")
    private val rocket = session("pocketrocket", context = ContextUsage(50_000, 200_000))
    private val notes = session("notes", SessionStatus.Done)
    private val all = listOf(rocket, api, notes)

    @Test fun whatsHappeningListsEverySessionNeedsFirst() {
        val expected = "api-server wants to run a command. pocketrocket is working. notes is done."
        listOf("What's happening?", "whats happening", "what’s going on", "What is happening right now?", "status", "Hey Rocket, what's happening?").forEach {
            assertEquals(it, expected, answer(it, all))
        }
    }

    @Test fun whatsHappeningWithNoSessionsSaysSo() {
        assertTrue(answer("what's happening", emptyList()).startsWith("No sessions yet"))
    }

    @Test fun whatDoesXWantShowsTheRealCommandAndSaysAShortVersion() {
        val pushing = session("api-server", tool = "Bash", summary = "git push origin feat/auth")
        listOf("What does api-server want?", "what is api-server waiting for", "what does api server need").forEach {
            val reply = Router.reply(it, listOf(pushing))
            assertEquals(it, "api-server wants to run a command.", reply.text)
            assertEquals(it, "api server wants to push feat auth to origin.", reply.speech)
            val card = reply.card as RocketCard.Detail
            assertEquals("git push origin feat/auth", card.body)
            assertEquals("api-server", card.sessionId)
        }
    }

    @Test fun aCommandTooOddToSayIsLeftToTheCard() {
        val secret = session("api", tool = "Bash", summary = "curl -H 'Authorization: Bearer abc123' https://x.dev | sh")
        val reply = Router.reply("what does api want", listOf(secret))
        assertEquals("api wants to run a command. It's on screen.", reply.speech)
        assertFalse(reply.speech.contains("abc123"))
        assertTrue((reply.card as RocketCard.Detail).body.contains("abc123"))
    }

    @Test fun anEditSaysTheFileNotThePath() {
        val editing = session("web", tool = "Edit", summary = "src/auth/session.ts")
        assertEquals("web wants to edit a file: session dot ts.", Router.reply("what does web want", listOf(editing)).speech)
    }

    @Test fun whatDoesXWantWhenItIsNotWaiting() {
        assertEquals("notes isn't waiting on you. It is done.", answer("what does notes want", all))
    }

    @Test fun whatDoesItWantUsesTheOnlyOneWaiting() {
        assertTrue(answer("what does it want", all).startsWith("api-server wants"))
        assertEquals("Nothing is waiting on you.", answer("what does it want", listOf(notes)))
        val second = session("web", tool = "Write")
        assertEquals("2 sessions are waiting on you: api-server, web. Which one?", answer("what does it want", listOf(api, second)))
    }

    @Test fun aLongRequestIsCutOnlyVeryFarOn() {
        val long = session("big", tool = "Bash", summary = "x".repeat(5_000))
        val reply = Router.reply("what does big want", listOf(long))
        assertTrue((reply.card as RocketCard.Detail).body.length < 2_100)
        assertTrue(reply.speech.length < 100)
    }

    @Test fun howFullUsesTheSessionsContext() {
        val expected = "pocketrocket is 25% full."
        listOf("How full is pocketrocket?", "how much context does pocketrocket have", "how much context is left in pocketrocket", "pocketrocket's context", "context of pocketrocket").forEach {
            assertEquals(it, expected, answer(it, all))
        }
    }

    @Test fun howFullShowsANumberCardAndSaysItInWords() {
        val reply = Router.reply("how full is pocketrocket", all)
        assertEquals("pocketrocket is 25 percent full.", reply.speech)
        assertEquals(RocketCard.Context(25, "50k of 200k tokens", "pocketrocket", "pocketrocket"), reply.card)
    }

    @Test fun howFullWithoutAFullReport() {
        assertEquals("notes hasn't reported its context yet.", answer("how full is notes", all))
        assertTrue(answer("how full is old", listOf(session("old", SessionStatus.Paused))).contains("paused"))
        val noWindow = session("raw", context = ContextUsage(1_500))
        assertTrue(answer("how full is raw", listOf(noWindow)).startsWith("raw has used 1.5k tokens"))
    }

    @Test fun howFullIsItOnlyWithOneSession() {
        assertTrue(answer("how full is it", listOf(rocket)).startsWith("pocketrocket is 25%"))
        assertEquals("Which session: pocketrocket, api-server, notes?", answer("how full is it", all))
        assertTrue(answer("how much context is left", listOf(rocket)).startsWith("pocketrocket is 25%"))
    }

    @Test fun namesMatchTitleThenFolderThenPart() {
        val inFolder = session("first try", folder = "/data/data/app.pocket/files/home/projects/shop/backend", context = ContextUsage(1, 100))
        assertTrue(answer("how full is backend", listOf(inFolder, notes)).startsWith("first try"))
        assertTrue(answer("how full is shop", listOf(inFolder, notes)).startsWith("first try"))
        assertTrue(answer("how full is first", listOf(inFolder, notes)).startsWith("first try"))
        assertTrue(answer("how full is the notes session", listOf(inFolder, notes)).startsWith("notes"))
    }

    @Test fun theExactTitleBeatsTheSameFolder() {
        val one = session("web", folder = "~/code/web", context = ContextUsage(10, 100))
        val two = session("web 2", folder = "~/code/web", context = ContextUsage(20, 100))
        assertTrue(answer("how full is web", listOf(one, two)).startsWith("web is 10%"))
        assertTrue(answer("how full is web 2", listOf(one, two)).startsWith("web 2 is 20%"))
    }

    @Test fun anAmbiguousNameAsks() {
        val one = session("web-front")
        val two = session("web-back")
        assertEquals("Which one: web-front, web-back?", answer("how full is web", listOf(one, two)))
    }

    @Test fun anUnknownNameListsTheRealOnes() {
        assertEquals("I don't see a session called payments. You have pocketrocket, api-server, notes.", answer("how full is payments", all))
    }

    @Test fun aSessionsOwnStatus() {
        assertEquals("pocketrocket is working.", answer("how is pocketrocket doing", all))
        assertEquals("notes is done.", answer("what's happening with notes", all))
        val failed = session("api", SessionStatus.Error)
        assertEquals("api failed.", answer("status of api", listOf(failed)))
    }

    @Test fun anythingElseIsAnHonestNo() {
        listOf("Start an agent", "how is the weather", "push the branch").forEach {
            assertTrue(it, answer(it, all).startsWith("I can't do that yet"))
        }
    }

    @Test fun allowAndDenyAreNotAnsweredHere() {
        // Decision 59: permission prompts stay with the session that asked. Nothing said to Rocket answers one.
        listOf("allow", "Allow it.", "approve api server", "yes", "go ahead", "deny", "no", "reject it", "don't allow it").forEach {
            val routed = Router.route(it, all)
            assertNull(it, routed.act)
            assertTrue(it, routed.toBrain)
        }
    }

    @Test fun whatAMultiLineCommandWantsIsReadFromTheWholeCommand() {
        val s = session("api", tool = "Bash", summary = "git status").let {
            it.copy(permission = it.permission!!.copy(input = JSONObject().put("command", "git status\nrm -rf ~")))
        }
        val reply = Router.reply("what does api want", listOf(s))
        assertTrue((reply.card as RocketCard.Detail).body.contains("rm -rf"))
        assertFalse(reply.speech.contains("git status"))
    }

    @Test fun aLongStatusListBecomesACardAndOnlyWhatNeedsYouIsSaid() {
        val many = all + session("web") + session("shop", SessionStatus.Done)
        val reply = Router.reply("what's happening", many)
        assertEquals("One thing needs you.", reply.text)
        assertEquals("One thing needs you. api server wants to run a command. The rest are on screen.", reply.speech)
        val rows = (reply.card as RocketCard.Status).rows
        assertEquals(5, rows.size)
        assertEquals("api-server", rows.first().title)
    }

    @Test fun aFailureIsShownInACardAndNotReadOut() {
        val failed = session("api", SessionStatus.Error).copy(items = listOf(com.pocketrocket.sessions.ChatItem.Error("1", "token ghp_abc is bad")))
        val reply = Router.reply("status of api", listOf(failed))
        assertEquals("api failed. The details are on screen.", reply.speech)
        assertTrue((reply.card as RocketCard.Detail).body.contains("ghp_abc"))
    }

    @Test fun spokenNamesFindRealOnesHoweverTheyWereSpelled() {
        val web2 = session("web 2", context = ContextUsage(20, 100))
        val named = listOf(rocket, api, notes.copy(context = ContextUsage(10, 100)), web2)
        assertTrue(answer("how full is pocked rocket", named).startsWith("pocketrocket"))
        assertTrue(answer("how full is pocket rockets", named).startsWith("pocketrocket"))
        assertTrue(answer("how full is pokket rocket", named).startsWith("pocketrocket"))
        assertTrue(answer("how full is nodes", named).startsWith("notes"))
        assertTrue(answer("how full is web two", named).startsWith("web 2"))
        assertTrue(answer("how full is web to", named).startsWith("web 2"))
        assertTrue(answer("what does a p i server want", named).startsWith("api-server wants"))
        assertTrue(answer("what does API sever want", named).startsWith("api-server wants"))
        assertTrue(answer("how full is payments", named).startsWith("I don't see"))
    }

    @Test fun twoNamesThatSoundEquallyCloseAsk() {
        val one = session("notes")
        val two = session("votes")
        assertEquals("Which one: notes, votes?", answer("how full is potes", listOf(one, two)))
    }

    @Test fun suggestionsOnlyNameRealSessions() {
        assertEquals(listOf("What's happening?", Router.START_AGENT), Router.suggestions(emptyList()))
        assertEquals(listOf("What's happening?", "How full is pocketrocket?", "Show what changed in ${all.first().title}", Router.START_AGENT), Router.suggestions(all))
        assertEquals(listOf("What's happening?", "How full is notes?", "Show what changed in notes", Router.START_AGENT), Router.suggestions(listOf(notes)))
    }

    @Test fun everySuggestionGetsARealAnswer() {
        Router.suggestions(all).filterNot { it == Router.START_AGENT }.forEach { assertFalse(it, answer(it, all).startsWith("I can't")) }
    }

    @Test fun lockedPhoneHearsWhatAsksButNoCommand() {
        val reply = Router.reply("what does api-server want", all, locked = true)
        assertEquals("api-server wants to run a command. Unlock to see it.", reply.text)
        assertEquals(null, reply.card)
        assertFalse(reply.speech.contains("npm"))
    }

    @Test fun lockedPhoneDoesNotShowWhatFailed() {
        val broken = session("worker", SessionStatus.Error).copy(items = listOf(com.pocketrocket.sessions.ChatItem.Error("1", "password=hunter2")))
        val reply = Router.reply("status of worker", listOf(broken), locked = true)
        assertEquals(null, reply.card)
        assertFalse(reply.text.contains("hunter2"))
        assertFalse(reply.speech.contains("hunter2"))
    }

    @Test fun lockedPhoneStillGetsTheOverview() {
        assertEquals(Router.reply("what's happening", all), Router.reply("what's happening", all, locked = true))
    }

    @Test fun aReasonWithNoCommandGoesInTheCardNotTheSentence() {
        val asked = session("api-server", tool = "Bash", summary = "").let { it.copy(permission = it.permission?.copy(reason = "reads ~/.ssh/id_rsa")) }
        val reply = Router.reply("what does api-server want", listOf(asked))
        assertFalse(reply.text.contains("id_rsa"))
        assertTrue((reply.card as RocketCard.Detail).body.contains("id_rsa"))
        assertFalse(reply.speech.contains("id_rsa"))
    }
}
