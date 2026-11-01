package com.pocketrocket.updates

import com.pocketrocket.clitools.NpmAction
import com.pocketrocket.clitools.NpmPresence
import com.pocketrocket.clitools.NpmTool
import com.pocketrocket.clitools.NpmToolsState
import com.pocketrocket.node.NpmGlobal
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolJob
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import com.pocketrocket.ui.tools.tool
import com.pocketrocket.wrangler.WranglerState
import com.pocketrocket.wrangler.WranglerTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every tool that can get updates has an update flow (decision 102): the npm CLIs against `npm view`, Wrangler against
 * its pin, Node.js and Chromium through apt; and the script's modes for them, run with a pretend npm.
 */
class ToolUpdatesTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val root = File.createTempFile("toolupdates", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr")
    private val home = File(root, "home")
    private val tmp = File(root, "tmp").apply { mkdirs() }

    init {
        // main_npm_tool preloads $PREFIX/lib/libtermux-exec.so; on the phone the pretend prefix needs the real one.
        val exec = File(System.getenv("PREFIX") ?: "/data/data/app.pocket/files/usr", "lib/libtermux-exec.so")
        if (exec.isFile) java.nio.file.Files.createSymbolicLink(File(prefix, "lib").apply { mkdirs() }.toPath().resolve(exec.name), exec.toPath())
    }

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    /**
     * Runs [code] with the script sourced, its PREFIX, HOME and TMPDIR in the temp folder, no device checks, and an npm
     * that records its arguments: `npm view` prints a warning and 65.9.1; `npm install -g` writes vercel 65.9.1.
     */
    private fun shell(code: String): Pair<Int, String> {
        val fakes = """
            preflight_checks() { mkdir -p "${'$'}PR_HOME"; : >"${'$'}PR_LOG"; }
            node() { echo 22; }
            npm() {
              printf '%s\n' "${'$'}*" >>"${'$'}HOME/npm.calls"
              case "${'$'}1" in
                view) echo 'npm warn config cache-min is deprecated' >&2; echo 65.9.1 ;;
                install) sleep 1  # the bar polls before npm has made its cache, as on the phone
                  mkdir -p "${'$'}PREFIX/lib/node_modules/vercel" "${'$'}PREFIX/bin"
                  echo '{"name": "vercel", "version": "65.9.1"}' >"${'$'}PREFIX/lib/node_modules/vercel/package.json"
                  printf '#!/bin/sh\necho "Vercel CLI 65.9.1"\n' >"${'$'}PREFIX/bin/vercel"; chmod 755 "${'$'}PREFIX/bin/vercel" ;;
              esac
            }
        """.trimIndent()
        val pb = ProcessBuilder(listOf("bash", "-c", ". \"$1\"; $fakes\n$code", "_", script.path)).redirectErrorStream(true)
        pb.environment().putAll(
            mapOf("PREFIX" to prefix.path, "HOME" to home.path, "TMPDIR" to tmp.path, "POCKETROCKET_SETUP_SOURCE_ONLY" to "1"),
        )
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun file(base: File, relative: String, text: String = "") = File(base, relative).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun npmViewsVersionIsItsLastVersionLine() {
        assertEquals("65.9.1", NpmGlobal.viewedVersion(listOf("npm warn config cache-min is deprecated", "65.9.1", "")))
        assertEquals("15.0.0-beta.2", NpmGlobal.viewedVersion(listOf("'15.0.0-beta.2'")))
        assertNull(NpmGlobal.viewedVersion(listOf("npm error code E404", "npm error 404 Not Found - GET https://registry.npmjs.org/nope")))
        assertNull(NpmGlobal.viewedVersion(emptyList()))
    }

    @Test fun onlyANewerReleaseIsAnUpdate() {
        assertTrue(NpmGlobal.newer("65.9.0", "65.9.1"))
        assertTrue(NpmGlobal.newer("65.9.1", "66.0.0"))
        assertTrue(NpmGlobal.newer("9.10.0", "10.0.0")) // numbers, not text
        assertFalse(NpmGlobal.newer("65.9.1", "65.9.1"))
        assertFalse(NpmGlobal.newer("66.0.0", "65.9.1")) // a newer one from a terminal is never downgraded
        assertTrue(NpmGlobal.newer("15.0.0-beta.2", "15.0.0"))
        assertTrue(NpmGlobal.newer("15.0.0-beta.2", "15.0.0-beta.10"))
        assertFalse(NpmGlobal.newer("15.0.0", "15.0.0-beta.2"))
        assertFalse(NpmGlobal.newer(null, "1.0.0"))
        assertFalse(NpmGlobal.newer("1.0.0", null))
        assertFalse(NpmGlobal.newer("1.0.0", "not a version"))
    }

    @Test fun anNpmToolOffersItsUpdateOnTheTileAndPage() {
        val s = NpmToolsState(
            checked = true,
            present = mapOf(NpmTool.Vercel to NpmPresence("65.9.0"), NpmTool.Netlify to NpmPresence("23.0.0", "netlify command missing")),
            latest = mapOf(NpmTool.Vercel to "65.9.1", NpmTool.Netlify to "24.0.0", NpmTool.Firebase to "15.0.0"),
        )
        assertTrue(s.updateAvailable(NpmTool.Vercel))
        assertFalse(s.updateAvailable(NpmTool.Netlify)) // Repair comes first
        assertFalse(s.updateAvailable(NpmTool.Firebase)) // not installed
        val tile = ToolStates.npmTool(NpmTool.Vercel, s)
        assertEquals("Installed · Update to 65.9.1", ToolModel.status(Tool.Vercel.spec, tile).line)
        assertTrue(Tool.Vercel.spec.canUpdate)
    }

    @Test fun anNpmUpdateShowsAsUpdatingNotAsAnInstall() {
        val running = NpmToolsState(
            checked = true, present = mapOf(NpmTool.Vercel to NpmPresence("65.9.0")), latest = mapOf(NpmTool.Vercel to "65.9.1"),
            job = SetupState(phase = SetupPhase.Running), tool = NpmTool.Vercel, action = NpmAction.Update,
        )
        val tile = ToolStates.npmTool(NpmTool.Vercel, running)
        assertEquals(ToolJob.None, tile.job)
        assertEquals("Updating…", ToolModel.status(Tool.Vercel.spec, tile).line)
        assertEquals(running.job, running.updateJob(NpmTool.Vercel))
        assertNull(running.updateJob(NpmTool.Netlify))
        // A failed update stays on the Version section with Retry; the tile isn't "Install failed".
        val failed = running.copy(job = SetupState(phase = SetupPhase.Failed), failed = true)
        assertEquals(ToolJob.None, ToolStates.npmTool(NpmTool.Vercel, failed).job)
        assertEquals(ToolJob.InstallFailed, ToolStates.npmTool(NpmTool.Vercel, failed.copy(action = NpmAction.Install)).job)
    }

    @Test fun wranglerHasAnUpdateOnlyWhenThePinMovesPastIt() {
        val older = WranglerState(installed = true, version = "4.100.0", checked = true)
        assertTrue(older.updateAvailable)
        assertFalse(older.copy(version = WranglerTool.VERSION).updateAvailable)
        assertFalse(older.copy(version = "99.0.0").updateAvailable)
        assertFalse(older.copy(problem = "won't start until repaired").updateAvailable)
        assertFalse(WranglerState(installed = false).updateAvailable)
        val updating = older.copy(job = SetupState(phase = SetupPhase.Running), updating = true)
        val tile = ToolStates.wrangler(updating)
        assertEquals(ToolJob.None, tile.job)
        assertEquals("Updating…", ToolModel.status(Tool.Wrangler.spec, tile).line)
    }

    @Test fun everyToolThatCanUpdateHasARowAndEveryRowATool() {
        UpdateComponent.entries.filter { it != UpdateComponent.PocketRocket }.forEach { c ->
            val t = c.tool
            assertTrue("$c has no tool", t != null)
            assertTrue("${t!!.name} has a row but no Update", t.spec.canUpdate)
        }
        val withRows = UpdateComponent.entries.mapNotNull { it.tool }.toSet()
        Tool.entries.filter { it.spec.canUpdate }.forEach { assertTrue("${it.name} says it updates but has no row", it in withRows) }
        // Android and the NDK are the only installed tools without an update flow (their pins move with the app).
        assertEquals(setOf(Tool.Android, Tool.AndroidNdk), Tool.entries.filter { !it.spec.canUpdate }.toSet())
        NpmTool.entries.forEach { assertEquals(it, UpdateComponent.of(it).npmTool) }
    }

    @Test fun theScriptHasEveryCheckAndUpdate() {
        if (!script.exists()) return
        val text = script.readText()
        val options = NpmTool.entries.flatMap { listOf(it.checkOption, it.updateOption) } +
            listOf("nodejs-lts-check", "nodejs-lts-update", "chromium-check", "chromium-update")
        options.forEach { assertTrue("--only $it", Regex("""[ |]${Regex.escape(it)}[|)]""").containsMatchIn(text)) }
        assertEquals("Node.js and npm", NodeUpdater.steps.last().removePrefix("Updating "))
    }

    @Test fun anNpmCheckPrintsNpmsVersionAndNothingElse() {
        if (!script.exists()) return
        val (exit, out) = shell("main_npm_tool vercel-check")
        assertEquals(out, 0, exit)
        assertEquals("65.9.1", NpmGlobal.viewedVersion(out.lines()))
        assertFalse(out, out.contains("[step"))
        assertTrue(File(home, "npm.calls").readText().startsWith("view --cache "))
        assertTrue(File(home, "npm.calls").readText().trim().endsWith("vercel version"))
    }

    @Test fun anNpmUpdateInstallsTheLatestWithTheInstallsSteps() {
        if (!script.exists()) return
        file(prefix, "lib/node_modules/vercel/package.json", """{"name": "vercel", "version": "65.9.0"}""")
        val (exit, out) = shell("main_npm_tool vercel-update")
        assertEquals(out, 0, exit)
        val steps = out.lines().filter { it.startsWith("[step ") }.map { it.substringAfter("] ") }
        assertEquals(NpmTool.Vercel.updateSteps, steps)
        assertTrue(out, out.contains("[info] vercel 65.9.0 -> latest"))
        assertTrue(out, out.trim().endsWith("[done]"))
        assertTrue(File(home, "npm.calls").readText().contains(" vercel@latest"))
        assertEquals("65.9.1", NpmGlobal.version(File(prefix, "lib/node_modules/vercel")))
    }

    @Test fun anNpmUpdateNeedsItInstalled() {
        if (!script.exists()) return
        val (exit, out) = shell("main_npm_tool netlify-update")
        assertTrue(exit != 0)
        assertTrue(out, out.contains("[error] the Netlify CLI is not installed"))
        assertFalse(File(home, "npm.calls").exists())
    }
}
