package com.pocketrocket.setup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** setup/claude and setup/pocketrocket-bash, run with bash against a fake Termux prefix (docs/tool-sweep.md, Phase 0). */
class ClaudeLauncherTest {
    private val claude = File("../setup/claude")
    private val shell = File("../setup/pocketrocket-bash")

    private fun script(file: File, body: String) {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n$body\n")
        file.setExecutable(true)
    }

    /** A prefix whose bash prints the helper it got, a home whose "Claude Code" prints what it got. */
    private fun fake(withShell: Boolean, block: (prefix: File, home: File) -> Unit) {
        val root = Files.createTempDirectory("pr-launcher").toFile()
        try {
            val prefix = File(root, "usr")
            script(File(prefix, "bin/bash"), "echo \"preload=\$LD_PRELOAD args=\$*\"")
            // Loaded for real by the fake bash: desktop loaders skip or ignore an empty file, Android's needs the real one.
            val helper = File(prefix, "lib/libtermux-exec.so").apply { parentFile.mkdirs() }
            val termux = File("/data/data/app.pocket/files/usr/lib/libtermux-exec.so")
            if (termux.exists()) Files.createSymbolicLink(helper.toPath(), termux.toPath()) else helper.writeText("")
            if (withShell) shell.copyTo(File(prefix, "bin/pocketrocket-bash")).setExecutable(true)
            val home = File(root, "pr")
            script(File(home, "claude/current/claude"), "echo \"shell=\$CLAUDE_CODE_SHELL preload=\$LD_PRELOAD\"")
            block(prefix, home)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun run(file: File, env: Map<String, String>, vararg args: String): String {
        // stdout only: a desktop loader may warn about the empty helper on stderr.
        val pb = ProcessBuilder(listOf("bash", file.path) + args).redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.environment().apply { remove("CLAUDE_CODE_SHELL"); remove("LD_PRELOAD"); putAll(env) }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    @Test fun claudesShellsGetPocketRocketBash() = fake(withShell = true) { prefix, home ->
        val env = mapOf("PREFIX" to prefix.path, "POCKETROCKET_HOME" to home.path)
        assertEquals("shell=${prefix.path}/bin/pocketrocket-bash preload=", run(claude, env))
    }

    @Test fun aShellTheUserSetStays() = fake(withShell = true) { prefix, home ->
        val env = mapOf("PREFIX" to prefix.path, "POCKETROCKET_HOME" to home.path, "CLAUDE_CODE_SHELL" to "/my/zsh")
        assertEquals("shell=/my/zsh preload=", run(claude, env))
    }

    @Test fun withoutTheShellClaudeKeepsItsOwnChoice() = fake(withShell = false) { prefix, home ->
        assertEquals("shell= preload=", run(claude, mapOf("PREFIX" to prefix.path, "POCKETROCKET_HOME" to home.path)))
    }

    @Test fun pocketRocketBashStartsBashWithTheHelper() = fake(withShell = true) { prefix, _ ->
        val out = run(File(prefix, "bin/pocketrocket-bash"), mapOf("PREFIX" to prefix.path), "-c", "true")
        assertEquals("preload=${prefix.path}/lib/libtermux-exec.so args=-c true", out)
    }
}
