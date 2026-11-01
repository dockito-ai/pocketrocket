package com.pocketrocket.updates

import com.pocketrocket.clitools.NpmAction
import com.pocketrocket.clitools.NpmPresence
import com.pocketrocket.clitools.NpmTool
import com.pocketrocket.clitools.NpmToolsState
import com.pocketrocket.cpp.CppTool
import com.pocketrocket.node.NpmGlobal
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolJob
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import com.pocketrocket.ui.tools.tool
import com.pocketrocket.wrangler.WranglerLogin
import com.pocketrocket.wrangler.WranglerState
import com.pocketrocket.wrangler.WranglerTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Every tool that can get updates has an update flow (decision 103): the npm CLIs against `npm view`, Wrangler against
 * its pin, Node.js, Chromium, C and C++ and the Java JDKs through apt, one apt-get update per Check now; and the script's
 * modes for them, run with a pretend npm and a pretend apt.
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
        // Android and the NDK are the tools without an update flow: their pins move with the app. C and C++ goes through apt.
        assertEquals(setOf(Tool.Android, Tool.AndroidNdk), Tool.entries.filter { !it.spec.canUpdate }.toSet())
        assertEquals(Tool.Cpp, UpdateComponent.Cpp.tool)
        NpmTool.entries.forEach { assertEquals(it, UpdateComponent.of(it).npmTool) }
    }

    @Test fun theScriptHasEveryCheckAndUpdate() {
        if (!script.exists()) return
        val text = script.readText()
        val options = NpmTool.entries.flatMap { listOf(it.checkOption, it.updateOption) } +
            listOf("nodejs-lts-check", "nodejs-lts-update", "chromium-check", "chromium-update", "clang-check", "clang-update", "apt-refresh")
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

    /**
     * A pretend apt for [shell]: ~/apt/<package> holds its installed and candidate versions (no file: not installed);
     * apt-get only records its arguments, and an upgrade moves each listed package to its candidate.
     */
    private val apt = """
        apt-get() { printf 'apt-get %s\n' "§*" >>"§HOME/apt.calls"; }
        apt-cache() { shift; local p; for p in "§@"; do [ -f "§HOME/apt/§p" ] || continue
          printf '%s:\n  Installed: %s\n  Candidate: %s\n' "§p" "§(sed -n 1p "§HOME/apt/§p")" "§(sed -n 2p "§HOME/apt/§p")"; done; }
        apt_package_upgrade_progress() { printf 'upgrade %s\n' "§*" >>"§HOME/apt.calls"; local p w
          for p in "§@"; do [ -f "§HOME/apt/§p" ] || continue; w="§(sed -n 2p "§HOME/apt/§p")"; printf '%s\n%s\n' "§w" "§w" >"§HOME/apt/§p"; done; }
        pkg_installed() { [ -f "§HOME/apt/§1" ]; }
        dpkg-query() { sed -n 1p "§HOME/apt/§{!#}"; }
    """.trimIndent().replace('§', '$')

    private fun aptPackage(pkg: String, installed: String, candidate: String = installed) = file(home, "apt/$pkg", "$installed\n$candidate\n")

    private fun aptCalls() = File(home, "apt.calls").takeIf { it.exists() }?.readLines().orEmpty()

    @Test fun theJavaRowFollowsWhicheverJdksAreThereButNever17() {
        val java = PackageTool.Java.updated.map { it.first }
        assertEquals(setOf("openjdk-21", "openjdk-25", "kotlin"), java.toSet())
        assertFalse(PackageTool.JAVA_17 in java)
        // Only Java 25 installed: the row is there, and it moves.
        val only25 = listOf("openjdk-25:", "  Installed: 25.0.1", "  Candidate: 25.0.2", "openjdk-17:", "  Installed: 17.0.9", "  Candidate: 17.0.10")
        val p = AptPolicy.follow(only25, PackageTool.Java.updated)!!
        assertEquals("Java 25 25.0.1", p.shownInstalled)
        assertEquals("Java 25 25.0.2", p.shownCandidate)
        assertTrue(p.updateAvailable)
        // 21 up to date, 25 behind: 25 is what the row offers. Both current: the row shows 21.
        val both = listOf("openjdk-21:", "  Installed: 21.0.9", "  Candidate: 21.0.9") + only25
        assertEquals("Java 25 25.0.2", AptPolicy.follow(both, PackageTool.Java.updated)!!.shownCandidate)
        val current = listOf("openjdk-21:", "  Installed: 21.0.9", "  Candidate: 21.0.9", "openjdk-25:", "  Installed: 25.0.2", "  Candidate: 25.0.2")
        assertEquals("Java 21 21.0.9", AptPolicy.follow(current, PackageTool.Java.updated)!!.shownInstalled)
        assertFalse(AptPolicy.follow(current, PackageTool.Java.updated)!!.updateAvailable)
        // Java 17 alone is the Android tool's: no Java row.
        assertNull(AptPolicy.follow(only25.drop(3), PackageTool.Java.updated))
        // One package keeps the plain version, as before.
        assertEquals("2.51.0", AptPolicy.follow(listOf("git:", "  Installed: 1:2.51.0", "  Candidate: 1:2.51.0"), listOf("git" to "Git"))!!.shownInstalled)
    }

    @Test fun cAndCppHasAnAptRowOverEveryPackage() {
        assertTrue(UpdateComponent.Cpp.optional)
        val lines = listOf("clang:", "  Installed: 21.1.0", "  Candidate: 21.1.0", "cmake:", "  Installed: 4.1.2", "  Candidate: 4.2.0")
        val p = AptPolicy.follow(lines, CppTool.PACKAGES)!!
        assertTrue(p.updateAvailable)
        assertEquals("CMake 4.2.0", p.shownCandidate)
    }

    @Test fun oneAptGetUpdateServesEveryRowOfACheck() {
        val runs = AtomicInteger()
        val index = AptIndex { runs.incrementAndGet(); Thread.sleep(50); true }
        val threads = (1..12).map { thread { assertTrue(index.fresh()) } }
        threads.forEach { it.join() }
        assertEquals(1, runs.get())
        // A refresh that failed fails every row of that check, without trying again.
        val failed = AptIndex { runs.incrementAndGet(); false }
        assertFalse(failed.fresh())
        assertFalse(failed.fresh())
        assertEquals(2, runs.get())
    }

    @Test fun aFreshIndexIsOnlyReadAndTheRefreshIsItsOwnMode() {
        if (!script.exists()) return
        aptPackage("git", "2.51.0", "2.52.0")
        val (exit, out) = shell("$apt\nPOCKETROCKET_APT_FRESH=1 main --only git-check")
        assertEquals(out, 0, exit)
        assertEquals(AptPolicy("2.51.0", "2.52.0"), AptPolicy.follow(out.lines(), listOf("git" to "Git")))
        assertEquals(emptyList<String>(), aptCalls())
        assertEquals(0, shell("$apt\nmain --only git-check").first) // alone, it refreshes
        assertEquals(0, shell("$apt\nmain --only apt-refresh").first)
        assertEquals(2, aptCalls().count { it.startsWith("apt-get update") })
    }

    @Test fun theScriptChecksWhatTheRowsFollow() {
        if (!script.exists()) return
        val all = (PackageTool.Java.updated + CppTool.PACKAGES).map { it.first } + PackageTool.JAVA_17
        all.forEach { aptPackage(it, "1.0") }
        val java = shell("$apt\nPOCKETROCKET_APT_FRESH=1 main --only openjdk-21-check")
        assertEquals(PackageTool.Java.updated.map { it.first }.toSet(), AptPolicy.parseEach(java.second.lines()).keys)
        val cpp = shell("$apt\nPOCKETROCKET_APT_FRESH=1 main --only clang-check")
        assertEquals(CppTool.PACKAGES.map { it.first }.toSet(), AptPolicy.parseEach(cpp.second.lines()).keys)
    }

    @Test fun javaUpdatesWith25AloneAnd17NeverMoves() {
        if (!script.exists()) return
        aptPackage("openjdk-25", "25.0.1", "25.0.2")
        aptPackage(PackageTool.JAVA_17, "17.0.9", "17.0.10")
        val (exit, out) = shell("$apt\nmain --only openjdk-21-update")
        assertEquals(out, 0, exit)
        assertTrue(out, out.contains("[info] openjdk-25 25.0.1 -> 25.0.2"))
        assertTrue(out, out.trim().endsWith("[done]"))
        assertFalse(aptCalls().single { it.startsWith("upgrade ") }.split(" ").contains(PackageTool.JAVA_17))
        assertEquals("25.0.2", File(home, "apt/openjdk-25").readLines().first())
        assertEquals("17.0.9", File(home, "apt/${PackageTool.JAVA_17}").readLines().first())
    }

    @Test fun cAndCppUpdatesWhicheverPackageMoved() {
        if (!script.exists()) return
        CppTool.PACKAGES.forEach { aptPackage(it.first, "1.0") }
        aptPackage("cmake", "4.1.2", "4.2.0")
        val (exit, out) = shell("$apt\nmain --only clang-update")
        assertEquals(out, 0, exit)
        assertEquals(listOf("Checking the device", "Updating C and C++"), out.lines().filter { it.startsWith("[step ") }.map { it.substringAfter("] ") })
        assertEquals(CppUpdater.steps, listOf("Checking the device", "Updating C and C++"))
        assertEquals("4.2.0", File(home, "apt/cmake").readLines().first())
        // Nothing newer: no upgrade at all.
        File(home, "apt.calls").delete()
        assertEquals(0, shell("$apt\nmain --only clang-update").first)
        assertTrue(aptCalls().none { it.startsWith("upgrade ") })
        // Not installed: says so.
        File(home, "apt").deleteRecursively()
        assertTrue(shell("$apt\nmain --only clang-update").first != 0)
    }

    @Test fun aFailedNpmViewIsAFailedCheckThatKeepsTheLastAnswer() {
        val s = NpmToolsState(latest = mapOf(NpmTool.Vercel to "65.9.0"), checking = setOf(NpmTool.Vercel))
        val failed = s.checked(NpmTool.Vercel, null)
        assertEquals(setOf(NpmTool.Vercel), failed.checkFailed)
        assertEquals("65.9.0", failed.latest[NpmTool.Vercel])
        assertTrue(failed.checking.isEmpty())
        val ok = failed.checked(NpmTool.Vercel, "65.9.1")
        assertTrue(ok.checkFailed.isEmpty())
        assertEquals("65.9.1", ok.latest[NpmTool.Vercel])
    }

    @Test fun aToolSigningInCantStartItsUpdate() {
        assertEquals(WranglerLogin, UpdateComponent.Wrangler.login)
        assertEquals(NpmTool.Vercel.login, UpdateComponent.Vercel.login)
        assertEquals(PackageTool.Aws.login, UpdateComponent.Aws.login)
        assertNull(UpdateComponent.Go.login)
        val rows = listOf(
            UpdateRow(UpdateComponent.Vercel, "65.9.0", "65.9.1", available = true, checking = false),
            UpdateRow(UpdateComponent.Git, "2.51.0", "2.52.0", available = true, checking = false),
        )
        val held = holdSigningIn(rows, setOf(UpdateComponent.Vercel))
        assertTrue(held[0].signingIn)
        assertFalse(held[0].ready)
        assertTrue(held[1].ready)
        val s = UpdatesState(rows = held)
        assertEquals(2, s.available)
        assertEquals(1, s.ready)
    }

    @Test fun wranglerInstallsThroughTheSharedNpmInstall() {
        if (!script.exists()) return
        val (exit, out) = shell("preflight_checks; npm_global_install wrangler Wrangler 22 78 wrangler@4.156.0 --ignore-scripts")
        assertEquals(out, 0, exit)
        val call = File(home, "npm.calls").readText().trim()
        assertTrue(call, call.startsWith("install -g --no-fund --no-audit --cache "))
        assertTrue(call, call.endsWith("--ignore-scripts wrangler@4.156.0"))
        assertFalse(File(tmp, "pocketrocket-npm-wrangler").exists())
        val (old, said) = shell("preflight_checks; node() { echo 20; }; npm_global_install wrangler Wrangler 22 78 wrangler@4.156.0")
        assertTrue(old != 0)
        assertTrue(said, said.contains("Wrangler needs Node 22 or newer, found 20"))
    }

    /** Node's corepack and a `node` that records what it runs, for the corepack tests. */
    private fun corepack() {
        file(prefix, "lib/node_modules/corepack/dist/corepack.js")
        file(prefix, "bin/corepack")
        file(prefix, "bin/node", "#!/bin/sh\nprintf '%s\\n' \"\$*\" >>\"\$HOME/node.calls\"\n").setExecutable(true)
    }

    private fun nodeCalls() = File(home, "node.calls").takeIf { it.exists() }?.readLines().orEmpty()

    @Test fun aNodeUpdateEnablesCorepackForPnpmAndYarn() {
        if (!script.exists()) return
        corepack()
        aptPackage("nodejs-lts", "24.1.0", "24.2.0")
        val (exit, out) = shell("$apt\nmain --only nodejs-lts-update")
        assertEquals(out, 0, exit)
        assertEquals(listOf("${prefix.path}/bin/corepack enable --install-directory ${prefix.path}/bin pnpm yarn"), nodeCalls())
        assertTrue(out, out.contains("[ok] pnpm yarn ready through corepack"))
        assertTrue(out, out.trim().endsWith("[done]"))
    }

    @Test fun corepackLeavesAPnpmItDidntPutThere() {
        if (!script.exists()) return
        corepack()
        file(prefix, "bin/pnpm", "#!/bin/sh\n")
        val (exit, out) = shell("preflight_checks; node_corepack_enable")
        assertEquals(out, 0, exit)
        assertTrue(out, out.contains("[info] keeping the pnpm already installed"))
        assertEquals(listOf("${prefix.path}/bin/corepack enable --install-directory ${prefix.path}/bin yarn"), nodeCalls())
    }

    @Test fun aNodeWithoutCorepackOrAFailedEnableNeverFails() {
        if (!script.exists()) return
        val (exit, out) = shell("preflight_checks; node_corepack_enable")
        assertEquals(out, 0, exit)
        assertTrue(out, out.contains("this Node has no corepack"))
        corepack()
        file(prefix, "bin/node", "#!/bin/sh\nexit 1\n").setExecutable(true)
        val (failed, said) = shell("preflight_checks; node_corepack_enable")
        assertEquals(said, 0, failed)
        assertTrue(said, said.contains("[warn] could not enable corepack for pnpm yarn"))
    }
}
