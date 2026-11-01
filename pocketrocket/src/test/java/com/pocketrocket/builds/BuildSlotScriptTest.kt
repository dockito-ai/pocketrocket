package com.pocketrocket.builds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** setup/pocketrocket-build-slot, run with bash in a scratch home (decision 97). Needs `flock`, as Termux and CI have. */
class BuildSlotScriptTest {
    private val script = File("../setup/pocketrocket-build-slot")

    private fun hasFlock() = ProcessBuilder("bash", "-c", "command -v flock").start().waitFor() == 0

    private fun scratch(block: (home: File) -> Unit) {
        assumeTrue(script.exists() && hasFlock())
        val home = Files.createTempDirectory("pr-slots").toFile()
        try {
            block(home)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun start(home: File, slots: String, vararg args: String, env: Map<String, String> = emptyMap()): Process {
        val pb = ProcessBuilder(listOf("bash", script.path) + args)
        pb.environment().apply {
            remove("POCKETROCKET_BUILD_SLOT")
            put("POCKETROCKET_HOME", home.path)
            put("POCKETROCKET_BUILD_SLOTS", slots)
            put("POCKETROCKET_BUILD_POLL", "0.1")
            putAll(env)
        }
        return pb.start()
    }

    private fun Process.finish(): Triple<Int, String, String> {
        val out = inputStream.bufferedReader().readText()
        val err = errorStream.bufferedReader().readText()
        assertTrue(waitFor(30, TimeUnit.SECONDS))
        return Triple(exitValue(), out, err)
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) error("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun builds(home: File) = File(home, "builds").listFiles()?.filter { it.name.endsWith(".build") }.orEmpty()

    @Test fun aBuildRunsAndPassesItsExitStatusOn() = scratch { home ->
        val (code, out, _) = start(home, "2", "run", "test build", "--", "bash", "-c", "echo built; exit 3").finish()
        assertEquals(3, code)
        assertEquals("built\n", out)
        assertTrue(builds(home).isEmpty())
        assertEquals("2", File(home, "builds/slots").readText().trim())
    }

    @Test fun aSecondBuildWaitsItsTurnWithOneLine() = scratch { home ->
        val gate = File(home, "gate")
        val first = start(home, "1", "run", "first", "--", "bash", "-c", "while [ ! -f '${gate.path}' ]; do sleep 0.05; done; echo first")
        eventually("the first build to take the slot") { builds(home).any { it.readText().contains("state=running") } }
        val second = start(home, "1", "run", "second", "--", "echo", "second")
        eventually("the second build to queue") { builds(home).any { it.readText().contains("state=waiting") } }
        val waiting = builds(home).single { it.readText().contains("state=waiting") }.readText()
        assertTrue(waiting.contains("command=second\n"))
        assertTrue(waiting.contains("slot=-1\n"))
        gate.writeText("")
        assertEquals("first\n", first.finish().second)
        val (code, out, err) = second.finish()
        assertEquals(0, code)
        assertEquals("second\n", out)
        assertTrue(err, err.startsWith("PocketRocket: waiting for another build to finish (1 running)…\n"))
        assertTrue(err, err.contains("in the background"))
        assertTrue(builds(home).isEmpty())
    }

    @Test fun aBuildInsideABuildSharesItsSlot() = scratch { home ->
        // With one slot, a nested build that waited would never finish.
        val (code, out, err) = start(home, "1", "run", "outer", "--", "bash", script.path, "run", "inner", "--", "echo", "inner").finish()
        assertEquals(0, code)
        assertEquals("inner\n", out)
        assertFalse(err, err.contains("waiting"))
    }

    @Test fun offRunsEverythingAtOnce() = scratch { home ->
        val (code, out, _) = start(home, "0", "run", "x", "--", "echo", "free").finish()
        assertEquals(0, code)
        assertEquals("free\n", out)
        assertFalse(File(home, "builds").exists())
    }

    @Test fun gradlesHelperHoldsUntilItsInputCloses() = scratch { home ->
        // A stand-in for the Gradle daemon: any live process.
        val daemon = ProcessBuilder("bash", "-c", "echo \$\$; exec sleep 60").start()
        val owner = daemon.inputStream.bufferedReader().readLine()!!
        val hold = start(home, "1", "hold", owner, "gradle assembleDebug")
        val line = hold.inputStream.bufferedReader().readLine()
        assertEquals("PR_SLOT held 0", line)
        val status = builds(home).single()
        assertEquals("$owner.build", status.name)
        assertTrue(status.readText().contains("command=gradle assembleDebug\n"))
        // Another hold for the same daemon (an included build) shares the slot.
        val again = start(home, "1", "hold", owner, "gradle :lib:assemble")
        assertEquals("PR_SLOT nested", again.inputStream.bufferedReader().readLine())
        again.waitFor(10, TimeUnit.SECONDS)
        hold.outputStream.close()
        assertTrue(hold.waitFor(10, TimeUnit.SECONDS))
        assertTrue(builds(home).isEmpty())
        val off = start(home, "0", "hold", owner, "gradle x")
        assertEquals("PR_SLOT off", off.inputStream.bufferedReader().readLine())
        off.waitFor(10, TimeUnit.SECONDS)
        daemon.destroy()
    }

    @Test fun onlyCompilingSubcommandsTakeASlot() = scratch { _ ->
        fun heavy(vararg args: String): Boolean {
            val p = ProcessBuilder(
                listOf("bash", "-c", "POCKETROCKET_BUILD_SLOT_SOURCE_ONLY=1 . \"$1\"; shift; bs_heavy \"$@\"", "_", script.path) + args,
            ).start()
            return p.waitFor() == 0
        }
        assertTrue(heavy("cargo", "build", "--release"))
        assertTrue(heavy("cargo", "+nightly", "test"))
        assertTrue(heavy("cargo", "check"))
        assertFalse(heavy("cargo", "fmt"))
        assertFalse(heavy("cargo", "--version"))
        assertTrue(heavy("make", "-j4"))
        assertTrue(heavy("ninja"))
        assertFalse(heavy("make", "--version"))
        assertFalse(heavy("ninja", "-t", "clean"))
    }

    @Test fun ramGivesTheSlotsAndGradlesHeap() = scratch { home ->
        fun config(memKb: Long): String {
            val meminfo = File(home, "meminfo").apply { writeText("MemTotal:       $memKb kB\nMemFree: 1 kB\n") }
            return start(home, "", "config", env = mapOf("POCKETROCKET_MEMINFO" to meminfo.path)).finish().second
        }
        assertEquals("slots=2\nheap_mb=1536\nmem_mb=11233\n", config(11_502_936)) // Shankar's 12 GB phone
        assertTrue(config(5_600_000).startsWith("slots=1\nheap_mb=1536\n")) // a 6 GB phone
        assertTrue(config(7_600_000).startsWith("slots=2\nheap_mb=1024\n")) // 8 GB
        assertTrue(config(15_500_000).startsWith("slots=3\nheap_mb=1280\n")) // 16 GB
    }
}
