package com.pocketrocket.pkgtools

import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Several JDKs on the Java page (decision 101): Java 17, 21 and 25 and Kotlin each on their own, Java 17 the default
 * java and never removed, and Gradle told about every JDK in ~/.gradle/gradle.properties without touching other lines.
 */
class JavaJdksTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val fakeApt = File("src/test/resources/fake-apt.sh")
    private val root = File.createTempFile("jdks", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr")
    private val home = File(root, "home").apply { mkdirs() }
    private val props = File(home, ".gradle/gradle.properties")
    private val alternatives = File(root, "alternatives")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun block(pkg: String, version: String, kb: Int) =
        "Package: $pkg\nStatus: install ok installed\nInstalled-Size: $kb\nVersion: $version\n"

    private fun pkgs(vararg blocks: String) = DpkgStatus.parse(blocks.joinToString("\n"), PackageTool.allWatched)

    private val jdk17 = block("openjdk-17", "17.0.21-1", 217996)
    private val jdk21 = block("openjdk-21", "21.0.13-1", 249292)
    private val jdk25 = block("openjdk-25", "25.0.5-1", 259116)
    private val kotlin = block("kotlin", "2.4.21", 95436)

    /** A JDK folder as Termux's package lays it out, with a java that runs. */
    private fun jdk(major: Int): String {
        val java = File(prefix, "lib/jvm/java-$major-openjdk/bin/java")
        java.parentFile!!.mkdirs()
        java.writeText("#!/bin/sh\necho \"openjdk version \\\"$major\\\"\"\n")
        java.setExecutable(true)
        return java.parentFile!!.parentFile!!.path
    }

    /**
     * Runs [code] with the script sourced, PREFIX and HOME in the temp folder, update-alternatives recorded instead of
     * run, and (with [apt]) the pretend dpkg and apt over [root].
     */
    private fun shell(code: String, apt: Boolean = false): Pair<Int, String> {
        val fake = if (apt) ". \"$3\"; FAKE_APT=\"$4\"; " else "pkg_installed() { return 1; }; "
        val full = "export HOME=\"$2\"; . \"$1\"; $fake PR_LOG=/dev/null; TOTAL_STEPS=2; " +
            "ALT=\"$4/alternatives\"; update-alternatives() { printf '%s\\n' \"\$*\" >>\"\$ALT\"; }; $code"
        val pb = ProcessBuilder(listOf("bash", "-c", full, "_", script.path, home.path, fakeApt.path, root.path)).redirectErrorStream(true)
        pb.environment().putAll(mapOf("PREFIX" to prefix.path, "POCKETROCKET_SETUP_SOURCE_ONLY" to "1"))
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun mergeKeepsOtherLinesAndReplacesOnlyTheTwoKeys() {
        if (!script.exists()) return
        val a = jdk(17)
        val b = jdk(21)
        props.parentFile!!.mkdirs()
        val android = listOf(
            "# >>> PocketRocket Android build (removed on uninstall)",
            "android.aapt2FromMavenOverride=/data/data/app.pocket/files/usr/bin/aapt2",
            "org.gradle.daemon.idletimeout=600000",
            "# <<< PocketRocket Android build",
        )
        props.writeText(
            (android + listOf("org.gradle.jvmargs=-Xmx2g", "  org.gradle.java.installations.paths = /old/jdk", "org.gradle.caching=true",
                "org.gradle.java.installations.paths:/older", "# org.gradle.java.installations.paths=/a comment stays",
                "org.gradle.java.installations.paths.extra=not ours")).joinToString("\n"), // no newline at the end
        )
        assertEquals(0, shell("java_gradle_jdks").first)
        assertEquals(
            android + listOf(
                "org.gradle.jvmargs=-Xmx2g", "org.gradle.java.installations.paths=$a,$b", "org.gradle.caching=true",
                "# org.gradle.java.installations.paths=/a comment stays", "org.gradle.java.installations.paths.extra=not ours",
                "org.gradle.java.installations.auto-download=false",
            ),
            props.readLines(),
        )
        // Again with nothing changed: the same file.
        val before = props.readText()
        shell("java_gradle_jdks")
        assertEquals(before, props.readText())
    }

    @Test fun aMissingFileIsWrittenAndTheLastJdkTakesTheKeysOut() {
        if (!script.exists()) return
        assertEquals(0, shell("java_gradle_jdks").first)
        assertFalse(props.exists()) // no JDK, no file
        val a = jdk(25)
        shell("java_gradle_jdks")
        assertEquals(listOf("${GradleJdks.PATHS}=$a", "${GradleJdks.AUTO_DOWNLOAD}=false"), props.readLines())
        File(prefix, "lib/jvm").deleteRecursively()
        shell("java_gradle_jdks")
        assertFalse(props.exists()) // nothing else was in it
        props.writeText("org.gradle.jvmargs=-Xmx2g\n${GradleJdks.PATHS}=$a\n${GradleJdks.AUTO_DOWNLOAD}=false\n")
        shell("java_gradle_jdks")
        assertEquals("org.gradle.jvmargs=-Xmx2g\n", props.readText())
    }

    @Test fun theJdkListIsEveryJdkOldestFirstAndTheAppReadsItTheSame() {
        if (!script.exists()) return
        val j25 = jdk(25)
        val j17 = jdk(17)
        val j21 = jdk(21)
        File(prefix, "lib/jvm/java-11-openjdk").mkdirs() // purged, folder left: no java in it
        File(prefix, "lib/jvm/java-x-openjdk/bin").mkdirs()
        val list = "$j17,$j21,$j25"
        assertEquals(list, shell("java_jdk_dirs").second.trim())
        assertEquals(listOf(j17, j21, j25), GradleJdks.dirs(prefix))

        assertFalse(GradleJdks.read(prefix, props).current) // missing file
        shell("java_gradle_jdks")
        val read = GradleJdks.read(prefix, props)
        assertEquals(list, read.paths)
        assertTrue(read.current) // what the script writes is what the app expects
        jdk(26) // a `pkg install` from a terminal, nothing written since
        assertFalse(GradleJdks.read(prefix, props).current)
        assertTrue(GradleJdks().current) // no JDK at all: nothing to tell Gradle
    }

    @Test fun theJavaHookIsThereExactlyWhileAJdkIs() {
        if (!script.exists()) return
        val template = File("../setup/${GradleJdks.HOOK}")
        val hook = File(home, GradleJdks.HOOK_RELATIVE)
        File(home, ".gradle/init.d/pocketrocket-build-slots.gradle").apply { parentFile!!.mkdirs(); writeText("other hook") }
        shell("java_gradle_jdks")
        assertFalse(hook.exists()) // no JDK
        jdk(17)
        jdk(21)
        assertEquals(0, shell("java_gradle_jdks").first)
        assertEquals(template.readText(), hook.readText()) // the script copies the template as it is
        hook.writeText("an older version")
        GradleJdks.syncHook(prefix, home) { template.readText() }
        assertEquals(template.readText(), hook.readText()) // app start writes the same file
        File(prefix, "lib/jvm").deleteRecursively()
        shell("java_gradle_jdks")
        assertFalse(hook.exists())
        hook.writeText("left over")
        GradleJdks.syncHook(prefix, home) { error("not read when there is no JDK") }
        assertFalse(hook.exists())
        assertEquals("other hook", File(home, ".gradle/init.d/pocketrocket-build-slots.gradle").readText())
    }

    @Test fun theJavaHookOnlyActsOnAndroidModulesWithoutAToolchainAndNeverFails() {
        val hook = File("../setup/${GradleJdks.HOOK}")
        if (!hook.exists()) return
        val text = hook.readText()
        assertFalse(text.contains("@POCKETROCKET")) // copied as is: no placeholders
        assertTrue(text.contains("findByName(\"android\")"))
        assertTrue(text.contains("toolchain.languageVersion.present")) // a module's own toolchain wins
        assertTrue(text.contains("java-\${asks}-openjdk/bin/java")) // only a JDK that is on the phone
        assertTrue(text.contains("asks > running"))
        assertFalse(text.contains("throw ")) // warnings only
    }

    @Test fun seventeenStaysTheDefaultAfterEveryJavaJob() {
        if (!script.exists()) return
        jdk(25)
        assertEquals(0, shell("pkg_tool_setup java-25").first)
        assertFalse(alternatives.exists()) // no 17: apt's choice stands
        val j17 = jdk(17)
        assertEquals(0, shell("pkg_tool_setup java-25; pkg_tool_setup kotlin").first)
        assertEquals(List(2) { "--set java $j17/bin/java" }, alternatives.readLines())
        assertTrue(props.readText().contains("$j17,"))

        val on25 = PackageDisk(javaDefault = "/data/data/app.pocket/files/usr/lib/jvm/java-25-openjdk/bin/java")
        val on17 = PackageDisk(javaDefault = "/data/data/app.pocket/files/usr/lib/jvm/java-17-openjdk/bin/java")
        assertEquals("java isn't Java 17", PackageTool.Java.problem(pkgs(jdk17, jdk25), on25))
        assertNull(PackageTool.Java.problem(pkgs(jdk17, jdk25), on17))
        assertNull(PackageTool.Java.problem(pkgs(jdk25), on25)) // no 17: 25 is the default
        assertEquals("25", PackageTool.javaDefault(on25))
    }

    @Test fun javaSeventeenIsNeverRemovedHere() {
        if (!script.exists()) return
        assertFalse(PackageTool.Java17.canRemove)
        assertTrue(PackageTool.Java25.canRemove && PackageTool.Java21.canRemove && PackageTool.Kotlin.canRemove)
        assertEquals(PackageTool.NEVER_REMOVED, shell("printf %s \"\$PKG_TOOL_NEVER\"").second.split(" ").toSet())

        val deps = listOf("openjdk-17-x openjdk-17", "openjdk-21-x openjdk-21", "kotlin openjdk-21", "openjdk-25-x openjdk-25")
        val all = listOf("bash", "openjdk-17", "openjdk-17-x", "openjdk-21", "openjdk-21-x", "kotlin", "openjdk-25", "openjdk-25-x")
        File(root, "deps").writeText(deps.joinToString("\n", postfix = "\n"))
        File(root, "installed").writeText(all.joinToString("\n", postfix = "\n"))
        File(home, ".pocketrocket").mkdirs()

        // Java 21 takes Kotlin and its -x with it; nothing else.
        var r = shell("step_pkg_tool_remove java-21", apt = true)
        assertEquals(r.second, 0, r.first)
        assertEquals(listOf("bash", "openjdk-17", "openjdk-17-x", "openjdk-25", "openjdk-25-x"), File(root, "installed").readLines().sorted())
        // java-17-remove leaves it, and so does the tile's Uninstall, which takes 25.
        r = shell("step_pkg_tool_remove java-17; step_pkg_tool_remove java", apt = true)
        assertEquals(r.second, 0, r.first)
        assertEquals(listOf("bash", "openjdk-17", "openjdk-17-x"), File(root, "installed").readLines().sorted())
    }

    @Test fun eachJdkIsReadFromDpkgSoTerminalInstallsShow() {
        // Java 25 alone, from `pkg install openjdk-25`: the tile is installed and lists it; Uninstall would take it.
        val only25 = pkgs(jdk17, jdk25)
        assertTrue(PackageTool.Java.installed(only25))
        assertEquals(listOf("Java 25" to "25.0.5-1"), PackageTool.Java.versions(only25))
        assertEquals(listOf("Java 25"), PackageTool.Java.removable(only25))
        assertFalse(PackageTool.Java.installed(pkgs(jdk17))) // the Android tool's 17 alone isn't this tile
        PackageTool.JAVA_PARTS.forEach { assertEquals(it.main in only25, it.installed(only25)) }
        assertTrue(PackageTool.Java17.installed(only25) && PackageTool.Java25.installed(only25) && !PackageTool.Kotlin.installed(only25))
        assertEquals(
            listOf("Java 21", "Kotlin", "Java 25"),
            PackageTool.Java.removable(pkgs(jdk17, jdk21, kotlin, jdk25)),
        )

        // The Gradle list behind the phone: Installed · Gradle JDK list out of date, with Repair (java-setup).
        val stale = PackageToolsState(
            checked = true, packages = only25,
            disk = PackageDisk(gradleJdks = GradleJdks(listOf("/p/java-17-openjdk", "/p/java-25-openjdk"), "/p/java-17-openjdk", "false")),
        )
        assertEquals("Installed · Gradle JDK list out of date", ToolModel.status(Tool.Java.spec, ToolStates.packageTool(PackageTool.Java, stale, null)).line)
        assertEquals("java-setup", PackageTool.Java.repair?.option)
        assertEquals(listOf("Checking the device", "Setting up Java"), PackageTool.Java.repairSteps)
        val fixed = stale.copy(disk = PackageDisk(gradleJdks = stale.disk.gradleJdks.copy(paths = "/p/java-17-openjdk,/p/java-25-openjdk")))
        assertNull(fixed.problem(PackageTool.Java))
        assertNull(PackageTool.Go.problem(pkgs(block("golang", "1", 1)), stale.disk)) // only Java's tile cares
    }

    @Test fun theScriptTakesEveryJavaOption() {
        if (!script.exists()) return
        val text = script.readText()
        (PackageTool.JAVA_PARTS.flatMap { listOf(it.option, it.removeOption) } + PackageTool.Java.repair!!.option).forEach {
            assertTrue("--only $it", Regex("""[ |]${Regex.escape(it)}[|)]""").containsMatchIn(text))
        }
        assertTrue(text.contains("${PackageTool.Java.repair!!.option}) main_java_setup"))
        assertTrue(text.contains("step \"${PackageTool.Java.repair!!.step}\""))
    }
}
