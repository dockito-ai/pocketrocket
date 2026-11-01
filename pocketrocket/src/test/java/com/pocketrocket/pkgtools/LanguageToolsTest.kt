package com.pocketrocket.pkgtools

import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import com.pocketrocket.updates.UpdateComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The language tiles (decision 98): they read dpkg and the phone like the other package tools, Ruby needs its gems
 * setting, Java 17 stays the default java, and the script agrees with the app.
 */
class LanguageToolsTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val root = File.createTempFile("langs", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr")
    private val home = File(root, "home")

    @After fun cleanUp() {
        root.setWritableRecursively()
        root.deleteRecursively()
    }

    private fun File.setWritableRecursively() = walkTopDown().forEach { it.setWritable(true) }

    private fun block(pkg: String, version: String, kb: Int) =
        "Package: $pkg\nStatus: install ok installed\nInstalled-Size: $kb\nVersion: $version\n"

    private fun pkgs(vararg blocks: String) = DpkgStatus.parse(blocks.joinToString("\n"), PackageTool.allWatched)

    /** Runs [code] with the script sourced, its PREFIX and HOME in the temp folder and no package installed. */
    private fun shell(code: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("bash", "-c", ". \"$1\"; pkg_installed() { return 1; }; PR_LOG=/dev/null; $code", "_", script.path))
            .redirectErrorStream(true)
        pb.environment().putAll(mapOf("PREFIX" to prefix.path, "HOME" to home.path, "POCKETROCKET_SETUP_SOURCE_ONLY" to "1"))
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun aGoFromATerminalShowsInstalled() {
        val s = PackageToolsState(checked = true, packages = pkgs(block("golang", "3:1.27.3", 223000)))
        val tile = ToolStates.packageTool(PackageTool.Go, s, null)
        assertEquals("Installed · 218 MB", ToolModel.status(Tool.Go.spec, tile).line)
        assertEquals(listOf("Go" to "1.27.3"), tile.versions)
        assertEquals("Not installed · 13 MB download", ToolModel.status(Tool.Php.spec, ToolStates.packageTool(PackageTool.Php, s, null)).line)
    }

    @Test fun rubyWithoutItsGemsSettingOffersRepair() {
        val ruby = pkgs(block("ruby", "4.0.9", 40000))
        val bare = PackageToolsState(checked = true, packages = ruby)
        assertEquals("gems setting missing", bare.problem(PackageTool.Ruby))
        assertEquals("Installed · gems setting missing", ToolModel.status(Tool.Ruby.spec, ToolStates.packageTool(PackageTool.Ruby, bare, null)).line)
        val set = bare.copy(disk = PackageDisk(profiles = setOf("pocketrocket-ruby.sh")))
        assertNull(set.problem(PackageTool.Ruby))
        assertNull(PackageTool.Ruby.problem(emptyMap(), PackageDisk())) // not installed: nothing to repair
    }

    @Test fun javaKeepsSeventeenAsTheDefault() {
        val jdk17 = block(PackageTool.JAVA_17, "17.0.21-1", 300000)
        val both = pkgs(jdk17, block("openjdk-21", "21.0.13", 260000), block("kotlin", "2.4.21", 95000))
        val on21 = PackageDisk(javaDefault = "/data/data/app.pocket/files/usr/lib/jvm/java-21-openjdk/bin/java")
        val on17 = PackageDisk(javaDefault = "/data/data/app.pocket/files/usr/lib/jvm/java-17-openjdk/bin/java")
        assertEquals("java isn't Java 17", PackageTool.Java.problem(both, on21))
        assertNull(PackageTool.Java.problem(both, on17))
        assertNull(PackageTool.Java.problem(both - PackageTool.JAVA_17, on21)) // no 17: 21 is the default, as apt leaves it
        assertEquals(listOf("Java" to "21.0.13", "Kotlin" to "2.4.21"), PackageTool.Java.versions(both))
        // Java 21 from a terminal, without Kotlin: installed, and the page offers to add it.
        assertEquals(listOf("Kotlin"), PackageTool.Java.missing(both - "kotlin"))
        assertFalse(PackageTool.Java.installed(pkgs(jdk17))) // Java 17 alone is the Android tool's, not this tile
    }

    @Test fun eachLanguageHasItsUpdatesRow() {
        listOf(PackageTool.Go, PackageTool.Deno, PackageTool.Ruby, PackageTool.Php, PackageTool.Java).forEach { t ->
            val c = UpdateComponent.of(t)
            assertTrue("$t has no Updates row", c != null && c.optional)
            val tool = Tool.entries.single { it.pkg == t }
            assertEquals(c!!.label.substringBefore(" "), tool.spec.name.substringBefore(" "))
        }
        assertNull(UpdateComponent.of(PackageTool.RustWasm))
    }

    @Test fun theScriptChecksAndUpdatesEachByPackageName() {
        if (!script.exists()) return
        val text = script.readText()
        UpdateComponent.entries.mapNotNull { it.packageTool }.forEach { t ->
            assertTrue("--only ${t.main}-check", Regex("""[ |]${Regex.escape(t.main)}-check\|""").containsMatchIn(text))
            assertTrue("--only ${t.main}-update", Regex("""[ |]${Regex.escape(t.main)}-update[|)]""").containsMatchIn(text))
        }
        assertEquals("openjdk-21-x", shell("pkg_tool_companions java").second)
    }

    @Test fun rubySetupWritesWhatSessionsGetToo() {
        if (!script.exists()) return
        assertEquals(0, shell("pkg_tool_setup ruby").first)
        val profile = File(prefix, "etc/profile.d/${PackageTool.Ruby.shell!!.file}")
        val (name, value) = PackageTool.Ruby.shell!!.environment.entries.single()
        assertTrue(profile.readText().lines().contains("export $name=$value"))
    }

    @Test fun rubyUninstallTakesGemsAndTheirLaunchersOnly() {
        if (!script.exists()) return
        val bin = File(prefix, "bin").apply { mkdirs() }
        File(bin, "rainbow").writeText("#!$prefix/bin/ruby\n#\n# This file was generated by RubyGems.\n")
        File(bin, "mine").writeText("#!/bin/sh\necho mine\n")
        File(prefix, "lib/ruby/gems/4.0.0/gems/rainbow-3.1.1").mkdirs()
        File(prefix, "etc/profile.d").mkdirs()
        File(prefix, "etc/profile.d/pocketrocket-ruby.sh").writeText("export RUBYOPT=-rrubygems\n")
        assertEquals(0, shell("pkg_tool_cleanup ruby").first)
        assertFalse(File(bin, "rainbow").exists())
        assertTrue(File(bin, "mine").exists())
        assertFalse(File(prefix, "lib/ruby").exists())
        assertFalse(File(prefix, "etc/profile.d/pocketrocket-ruby.sh").exists())
    }

    @Test fun goUninstallClearsCachesAndKeepsGoBin() {
        if (!script.exists()) return
        val mod = File(home, "go/pkg/mod/github.com/google/uuid@v1.6.0").apply { mkdirs() }
        File(mod, "uuid.go").writeText("package uuid\n")
        mod.walkTopDown().forEach { it.setWritable(false) } // Go's module cache is read-only
        File(home, ".cache/go-build/00").mkdirs()
        File(home, "go/bin").mkdirs()
        File(home, "go/bin/hello").writeText("x")
        assertEquals(0, shell("pkg_tool_cleanup go").first)
        assertFalse(File(home, "go/pkg").exists())
        assertFalse(File(home, ".cache/go-build").exists())
        assertTrue(File(home, "go/bin/hello").exists())
    }

    @Test fun noPackageToolEverRemovesJavaSeventeen() {
        if (!script.exists()) return
        assertTrue(shell("printf %s \"\$PKG_TOOL_NEVER\"").second.split(" ").contains(PackageTool.JAVA_17))
        assertEquals("", shell("pkg_tool_packages java").second.split(" ").filter { it.startsWith(PackageTool.JAVA_17) }.joinToString())
    }
}
