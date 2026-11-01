package com.pocketrocket.apk

import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.ShellRun
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Decision 99: which call in the chat gets the card for an APK. */
class ApkWatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L

    private fun bash(id: String, command: String, from: Long?, to: Long?, result: String? = "ok", name: String = "Bash") =
        ChatItem.Tool(
            id, name, command, JSONObject().put("command", command).toString(), result = result,
            shell = ShellRun("task-$id", status = if (to != null) "completed" else null, startedAt = from, endedAt = to),
        )

    private fun built(root: File, modified: Long) =
        File(root, "app/build/outputs/apk/debug/app-debug.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)); setLastModified(modified) }

    @Test fun theBuildGetsTheCardNotTheLsAfterIt() {
        val repo = tmp.newFolder("repo")
        built(repo, t0 + 50_000)
        val watch = ApkWatch(repo, tmp.root) { "" }
        val cards = watch.cards(
            listOf(
                bash("1", "./gradlew assembleDebug", t0, t0 + 60_000),
                bash("2", "ls -la app/build/outputs/apk/debug/app-debug.apk", t0 + 50_500, t0 + 51_000),
            ),
        )
        assertEquals(setOf("1"), cards.keys)
        assertEquals("app-debug.apk", cards.getValue("1").single().name)
        assertEquals(10L, cards.getValue("1").single().size)
    }

    @Test fun aRebuildGetsANewCard() {
        val repo = tmp.newFolder("repo")
        val watch = ApkWatch(repo, tmp.root) { "" }
        val first = bash("1", "./gradlew assembleDebug", t0, t0 + 60_000)
        built(repo, t0 + 50_000)
        assertEquals(setOf("1"), watch.cards(listOf(first)).keys)
        built(repo, t0 + 500_000)
        val second = bash("2", "./gradlew assembleDebug", t0 + 450_000, t0 + 510_000)
        assertEquals(setOf("1", "2"), watch.cards(listOf(first, second)).keys)
    }

    @Test fun onlyFinishedCallsWithKnownTimes() {
        val repo = tmp.newFolder("repo")
        built(repo, t0 + 50_000)
        val watch = ApkWatch(repo, tmp.root) { "" }
        val running = bash("1", "./gradlew assembleDebug", t0, null, result = null)
        val fromTranscript = ChatItem.Tool("2", "Bash", "build", JSONObject().put("command", "./gradlew assembleDebug").toString(), result = "ok")
        val notBash = bash("3", "./gradlew assembleDebug", t0, t0 + 60_000, name = "Read")
        assertEquals(emptyMap<String, List<BuiltApk>>(), watch.cards(listOf(running, fromTranscript, notBash)))
        // Once it has finished, it is looked at.
        assertEquals(setOf("1"), watch.cards(listOf(bash("1", "./gradlew assembleDebug", t0, t0 + 60_000))).keys)
    }

    @Test fun aBackgroundBuildIsReadFromItsOutputFile() {
        val repo = tmp.newFolder("repo")
        val out = File(repo, "dist/app.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1)); setLastModified(t0 + 10_000) }
        val log = File(tmp.root, "task.output").apply { writeText("…\nWrote ${out.path}\n") }
        val tool = bash("1", "./release.sh", t0, t0 + 20_000, result = "Command running in background").let {
            it.copy(shell = it.shell!!.copy(background = true, outputFile = log.path))
        }
        assertEquals(setOf("1"), ApkWatch(repo, tmp.root).cards(listOf(tool)).keys)
    }

    @Test fun freeNamesInAFolder() {
        val dir = tmp.newFolder("downloads")
        assertEquals("app.apk", ApkActions.freeName(dir, "app.apk").name)
        File(dir, "app.apk").createNewFile()
        File(dir, "app (1).apk").createNewFile()
        assertEquals("app (2).apk", ApkActions.freeName(dir, "app.apk").name)
    }
}
