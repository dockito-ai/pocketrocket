package com.pocketrocket.fluttertool

import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Flutter & Dart tile reads FVM's versions, FVM and the launcher, not a flag the install saved. */
class FlutterDetectionTest {
    private val root = File.createTempFile("flutter", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr").apply { mkdirs() }
    private val home = File(root, "home").apply { mkdirs() }
    private val script = File("../setup/pocketrocket-setup.sh")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(f: File) = f.apply { parentFile?.mkdirs(); writeText("") }
    private fun version(v: String, dart: Boolean = true) {
        file(File(home, "fvm/versions/$v/${FlutterTool.VERSION_FLUTTER}"))
        if (dart) file(File(home, "fvm/versions/$v/${FlutterTool.VERSION_DART}"))
    }
    private fun presence() = FlutterTool.presence(prefix, home)

    /** This phone on 1 Nov 2026: FVM, the launcher, 3.47.9 and 3.44.1 (the second from `fvm install`). */
    private fun asInstalled() {
        file(File(prefix, FlutterTool.FVM_SNAPSHOT_RELATIVE))
        file(File(prefix, FlutterTool.LAUNCHER_RELATIVE))
        version("3.47.9")
        version("3.44.1")
    }

    @Test fun flutterIsWhatsOnThePhone() {
        assertFalse(presence().present)
        asInstalled()
        assertTrue(presence().complete)
    }

    @Test fun aVersionWithoutItsDartSdkDoesNotCountAsWorking() {
        file(File(prefix, FlutterTool.FVM_SNAPSHOT_RELATIVE))
        file(File(prefix, FlutterTool.LAUNCHER_RELATIVE))
        version("3.50.0", dart = false)
        assertEquals("Dart SDK missing", presence().problem)
        version("3.47.9")
        assertTrue(presence().complete)
    }

    @Test fun terminalRemovalsShow() {
        asInstalled()
        File(home, "fvm").deleteRecursively() // rm -rf ~/fvm: FVM and the launcher are still there
        assertEquals("Flutter SDK missing", presence().problem)
        File(prefix, FlutterTool.FVM_DIR_RELATIVE).deleteRecursively()
        File(prefix, FlutterTool.LAUNCHER_RELATIVE).delete()
        assertFalse(presence().present)
        // The install's list of the packages it added is a record, not Flutter.
        file(File(home, ".pocketrocket/flutter.packages"))
        assertFalse(presence().present)
        // An old flag alone means nothing either.
        file(File(home, "fvm/.pocketrocket-installed"))
        assertFalse(presence().present)
    }

    @Test fun aFailedInstallsLeftoversOfferRepairAndUninstall() {
        file(File(home, FlutterTool.BASELINE_RELATIVE)) // stopped in its apt step
        assertEquals("FVM, flutter command, Flutter SDK missing", presence().problem)
        File(home, FlutterTool.BASELINE_RELATIVE).delete()
        file(File(prefix, FlutterTool.FVM_SNAPSHOT_RELATIVE)) // stopped after FVM
        file(File(prefix, FlutterTool.LAUNCHER_RELATIVE))
        val p = presence()
        assertEquals("Flutter SDK missing", p.problem)
        val tile = ToolStates.flutter(FlutterRustState(flutterInstalled = p.present, flutterProblem = p.problem, checked = true))
        assertEquals("Installed · Flutter SDK missing", ToolModel.status(Tool.Flutter.spec, tile).line)
    }

    @Test fun clangWithoutTheExecHelperOffersRepairForApkBuilds() {
        asInstalled()
        assertTrue(presence().complete) // no clang: APK builds aren't expected
        file(File(prefix, FlutterTool.CLANG_RELATIVE)) // C and C++ added after Flutter
        assertEquals("APK builds missing", presence().problem)
        file(File(prefix, FlutterTool.EXEC_GLIBC_RELATIVE)) // Repair built it
        assertTrue(presence().complete)
    }

    @Test fun theScriptAndTheLauncherKeepTheExecHelperWhereTheAppLooks() {
        if (!script.exists()) return
        fun read(file: String, code: String): String {
            val p = ProcessBuilder(listOf("bash", "-c", "HOME=/h PREFIX=/p $code", "_", file)).start()
            return p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }
        val want = "/p/${FlutterTool.EXEC_RELATIVE}\n"
        assertEquals(want, read(script.path, "POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; printf '%s\\n' \"\$FLUTTER_EXEC_DIR\""))
        assertEquals(want, read("../setup/flutter", "POCKETROCKET_FLUTTER_SOURCE_ONLY=1 . \"$1\"; printf '%s\\n' \"\$PR_EXEC_DIR\""))
    }

    @Test fun theScriptAndTheAppNameTheSamePieces() {
        if (!script.exists()) return
        val code = "HOME=/h PREFIX=/p POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; printf '%s\\n' \"\$FVM_DIR\" \"\$FLUTTER_VERSIONS\" \"\$FLUTTER_BASELINE_FILE\""
        val p = ProcessBuilder(listOf("bash", "-c", code, "_", script.path)).start()
        val out = p.inputStream.bufferedReader().readText().lines()
        p.waitFor()
        assertEquals(listOf("/p/${FlutterTool.FVM_DIR_RELATIVE}", "/h/${FlutterTool.FVM_RELATIVE}/versions", "/h/${FlutterTool.BASELINE_RELATIVE}"), out.take(3))
    }

    @Test fun releaseBuildsShowWhenAddedAndOfferRepairWhenHalfThere() {
        asInstalled()
        fun release() = FlutterTool.releaseBuilds(prefix, home)
        assertEquals(ReleaseBuilds.Off, release())
        // qemu from a terminal is not this piece, and nothing is missing.
        file(File(prefix, FlutterTool.RELEASE_QEMU_RELATIVE))
        assertEquals(ReleaseBuilds.Off, release())
        assertTrue(presence().complete)
        // Added: the C library and the install's record.
        file(File(prefix, FlutterTool.RELEASE_LOADER_RELATIVE))
        file(File(home, FlutterTool.RELEASE_RECORD_RELATIVE))
        assertEquals(ReleaseBuilds.Installed, release())
        assertTrue(presence().complete)
        // The emulator taken away (pkg uninstall qemu-user-x86-64): Repair.
        File(prefix, FlutterTool.RELEASE_QEMU_RELATIVE).delete()
        assertEquals(ReleaseBuilds.Missing, release())
        assertEquals("Release builds missing", presence().problem)
        assertTrue(FlutterTool.repairsOnlyReleaseBuilds(presence().problem))
        // Or the C library gone while qemu stays.
        file(File(prefix, FlutterTool.RELEASE_QEMU_RELATIVE))
        File(prefix, FlutterTool.RELEASE_SYSROOT_RELATIVE).deleteRecursively()
        assertEquals(ReleaseBuilds.Missing, release())
        // Repair of more than Release builds runs Flutter's install.
        File(home, "fvm").deleteRecursively()
        assertEquals("Flutter SDK, Release builds missing", presence().problem)
        assertFalse(FlutterTool.repairsOnlyReleaseBuilds(presence().problem))
        assertFalse(FlutterTool.repairsOnlyReleaseBuilds(null))
    }

    @Test fun theScriptTheLauncherAndTheAppPlaceReleaseBuildsAlike() {
        if (!script.exists()) return
        fun read(file: String, code: String): List<String> {
            val p = ProcessBuilder(listOf("bash", "-c", "HOME=/h PREFIX=/p $code", "_", file)).start()
            return p.inputStream.bufferedReader().readText().lines().filter { it.isNotEmpty() }.also { p.waitFor() }
        }
        val want = listOf("/p/${FlutterTool.RELEASE_QEMU_RELATIVE}", "/p/${FlutterTool.RELEASE_SYSROOT_RELATIVE}")
        assertEquals(
            want + "/h/${FlutterTool.RELEASE_RECORD_RELATIVE}",
            read(script.path, "POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; printf '%s\\n' \"\$FLUTTER_RELEASE_QEMU\" \"\$FLUTTER_RELEASE_SYSROOT\" \"\$FLUTTER_RELEASE_ADDED_FILE\""),
        )
        assertEquals(want, read("../setup/flutter", "POCKETROCKET_FLUTTER_SOURCE_ONLY=1 . \"$1\"; printf '%s\\n' \"\$PR_QEMU\" \"\$PR_X64_SYSROOT\""))
        // The setup script finds the launcher's wrappers by its mark.
        val marks = read(script.path, "POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; printf '%s\\n' \"\$FLUTTER_RELEASE_MARK\"; POCKETROCKET_FLUTTER_SOURCE_ONLY=1 . ../setup/flutter; printf '%s\\n' \"\$PR_AOT_MARK\"")
        assertTrue(marks.toString(), marks[1].startsWith(marks[0]))
    }

    /** A baseline left by an install from before the record deleted it must not count later packages as the tool's. */
    @Test fun aStaleBaselineIsDropped() {
        if (!script.exists()) return
        val base = File(root, "x.baseline").apply { writeText("bash\n"); setLastModified(System.currentTimeMillis() - 60_000) }
        val added = File(root, "x.packages").apply { writeText("which\n") }
        fun drop() = ProcessBuilder(
            listOf("bash", "-c", "PREFIX=/p POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; packages_drop_stale \"$2\" \"$3\"", "_", script.path, base.path, added.path),
        ).start().waitFor()
        added.setLastModified(System.currentTimeMillis() - 120_000) // a run that stopped after an earlier one's record: kept
        assertEquals(0, drop())
        assertTrue(base.exists())
        added.setLastModified(System.currentTimeMillis())
        assertEquals(0, drop())
        assertFalse(base.exists())
    }
}
