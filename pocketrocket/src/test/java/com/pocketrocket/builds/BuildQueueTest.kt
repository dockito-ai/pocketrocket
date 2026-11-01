package com.pocketrocket.builds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildQueueTest {
    private fun status(pid: Int, helper: Int = pid, state: String = "running", queued: Long = 100, started: String = "110", command: String = "gradle assembleDebug") =
        "pid=$pid\nhelper=$helper\nstate=$state\nslot=0\nqueued=$queued\nstarted=$started\n" +
            "folder=/data/data/app.pocket/files/home/projects/my-app\nsession=/data/data/app.pocket/files/home/projects/my-app\ncommand=$command\n"

    @Test fun aRunningBuildIsRead() {
        val e = BuildQueue.parse(status(4242, helper = 4250))!!
        assertEquals(4242, e.pid)
        assertEquals(4250, e.helper)
        assertTrue(e.running)
        assertEquals(100L, e.queuedAt)
        assertEquals(110L, e.startedAt)
        assertEquals("gradle assembleDebug", e.command)
        assertEquals("my-app", e.folderName)
        assertEquals("/data/data/app.pocket/files/home/projects/my-app", e.session)
    }

    @Test fun aWaitingBuildHasNoStartYet() {
        val e = BuildQueue.parse(status(7, state = "waiting", started = ""))!!
        assertFalse(e.running)
        assertNull(e.startedAt)
    }

    @Test fun commandsKeepTheirEqualsSigns() {
        assertEquals("make CC=clang -j2", BuildQueue.parse(status(7, command = "make CC=clang -j2"))!!.command)
    }

    @Test fun brokenFilesAreNotBuilds() {
        assertNull(BuildQueue.parse(""))
        assertNull(BuildQueue.parse("pid=12\nstate=running\n")) // no helper
        assertNull(BuildQueue.parse("pid=x\nhelper=1\nstate=running\n"))
        assertNull(BuildQueue.parse("pid=1\nhelper=1\nstate=done\n"))
        // A half-written file can't happen (the script renames), but a truncated one still reads as far as it goes.
        assertEquals("", BuildQueue.parse("pid=1\nhelper=1\nstate=waiting\n")!!.command)
    }

    @Test fun slotsAreReadLeniently() {
        assertEquals(2, BuildQueue.parseSlots("2\n"))
        assertEquals(0, BuildQueue.parseSlots(null))
        assertEquals(0, BuildQueue.parseSlots("two"))
        assertEquals(0, BuildQueue.parseSlots("-1"))
    }

    @Test fun waitingBuildsAreInTheOrderTheyGetASlot() {
        val entries = listOf(
            BuildQueue.parse(status(30, state = "waiting", queued = 300, started = ""))!!,
            BuildQueue.parse(status(21, state = "waiting", queued = 200, started = ""))!!,
            BuildQueue.parse(status(20, state = "waiting", queued = 200, started = ""))!!,
            BuildQueue.parse(status(2, queued = 50, started = "90"))!!,
            BuildQueue.parse(status(1, queued = 60, started = "70"))!!,
        )
        val q = BuildQueue.order(entries, 2)
        assertEquals(listOf(1, 2), q.running.map { it.pid })
        assertEquals(listOf(20, 21, 30), q.waiting.map { it.pid })
        assertEquals(2, q.slots)
        assertFalse(q.isEmpty)
        assertTrue(BuildQueueState.EMPTY.isEmpty)
    }

    @Test fun deadHelpersAndOtherFilesAreDropped() {
        val dir = Files.createTempDirectory("pr-builds").toFile()
        try {
            File(dir, "10.build").writeText(status(10))
            File(dir, "11.build").writeText(status(11, state = "waiting", started = ""))
            File(dir, "12.build").writeText(status(12, helper = 99)) // its helper is gone
            File(dir, "slot-0.lock").writeText("")
            File(dir, "13.build.tmp.13").writeText(status(13))
            File(dir, "slots").writeText("2\n")
            val q = BuildQueue.read(dir) { it != 99 }
            assertEquals(listOf(10), q.running.map { it.pid })
            assertEquals(listOf(11), q.waiting.map { it.pid })
            assertEquals(2, q.slots)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun noFolderMeansNoBuilds() {
        assertTrue(BuildQueue.read(File("/nonexistent/pr-builds")) { true }.isEmpty)
    }

    @Test fun wrappersHandTheCommandToTheScript() {
        val text = BuildSlots.wrapperScript("/p/usr", "cargo")
        assertTrue(text.startsWith("#!/p/usr/bin/sh\n"))
        assertTrue(text.contains("exec /p/usr/bin/pocketrocket-build-slot wrap cargo \"\$@\"\n"))
    }

    @Test fun terminalsGetTheWrappersFirstOnce() {
        val text = BuildSlots.profileScript("/p/usr/libexec/pocketrocket/build-wrappers")
        assertTrue(text.contains("*\":/p/usr/libexec/pocketrocket/build-wrappers:\"*) ;;"))
        assertTrue(text.contains("export PATH=\"/p/usr/libexec/pocketrocket/build-wrappers:\$PATH\""))
    }

    @Test fun sessionsGetTheWrappersFirstOnlyWhenThere() {
        assertEquals(mapOf("PATH" to "/p/usr/libexec/pocketrocket/build-wrappers:/p/usr/bin"), BuildSlots.environment("/p/usr", true))
        assertEquals(emptyMap<String, String>(), BuildSlots.environment("/p/usr", false))
    }

    @Test fun theGradleHookPointsAtTheInstalledScript() {
        val template = File("../setup/${BuildSlots.GRADLE_HOOK}")
        if (!template.exists()) return
        val hook = BuildSlots.gradleHook(template.readText(), "/p/usr")
        assertFalse(hook.contains("@POCKETROCKET"))
        assertTrue(hook.contains("new File(\"/p/usr/bin/pocketrocket-build-slot\")"))
    }
}
