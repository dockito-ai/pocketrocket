package com.pocketrocket.fluttertool

import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Rust reads installed, version and size from dpkg, and its uninstall works whoever installed it. */
class RustDetectionTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val fakeApt = File("src/test/resources/fake-apt.sh")

    private fun block(pkg: String, version: String, kb: Int, status: String = "install ok installed") =
        "Package: $pkg\nStatus: $status\nInstalled-Size: $kb\nVersion: $version\nDescription: x\n"

    /** This phone on 1 Nov 2026: rust and the wasm target installed with `pkg install`, no record from the tile. */
    private val phone = listOf(
        block("clang", "21.1.8-3", 200000),
        block("rust", "1.99.1", 578196),
        block("rust-std-aarch64-linux-android", "1.99.1", 24908),
        block("rust-std-wasm32-unknown-unknown", "1.99.1", 89892),
        block("rust-std-wasm32-wasip1", "1.99.1", 50000, status = "deinstall ok config-files"),
        block("rust-analyzer", "1.99.1", 40000, status = "purge ok not-installed"),
    ).joinToString("\n")

    private val pkgs = DpkgStatus.parse(phone, FlutterTool::isRustPackage)

    @Test fun rustIsWhatDpkgSaysNotAMarker() {
        assertEquals(setOf("rust", "rust-std-aarch64-linux-android", "rust-std-wasm32-unknown-unknown"), pkgs.keys)
        assertTrue(FlutterTool.rustInstalled(pkgs))
        assertEquals("1.99.1", FlutterTool.rustVersion(pkgs))
        assertEquals(677, FlutterTool.rustSizeMb(pkgs)) // rust, its own target and the wasm target
        val removed = pkgs - "rust" // `pkg uninstall rust` in a terminal; a target left behind doesn't count
        assertFalse(FlutterTool.rustInstalled(removed))
        assertNull(FlutterTool.rustVersion(removed))
        assertNull(FlutterTool.rustSizeMb(removed))
    }

    @Test fun theTileShowsDpkgsVersionAndSize() {
        val s = FlutterRustState(rustPackages = pkgs, checked = true)
        val rust = ToolStates.rust(s, null)
        assertTrue(rust.installed)
        assertEquals(listOf("Rust" to "1.99.1"), rust.versions)
        assertEquals("677 MB", ToolModel.status(Tool.Rust.spec, rust).line.substringAfter("· "))
        assertFalse(ToolStates.rust(FlutterRustState(checked = true), null).installed)
    }

    /** Runs the script's step_rust_remove against a pretend dpkg and apt; returns what is left installed. */
    private fun remove(installed: List<String>, deps: List<String>, record: List<String>?): Pair<List<String>, String> {
        val dir = File.createTempFile("rust", "").apply { delete(); mkdirs() }
        try {
            File(dir, "installed").writeText(installed.joinToString("\n", postfix = "\n"))
            File(dir, "deps").writeText(deps.joinToString("\n", postfix = "\n"))
            val home = File(dir, "home").apply { mkdirs() }
            File(home, ".cargo").mkdirs()
            File(home, ".pocketrocket").mkdirs() // the script keeps its logs there
            record?.let { File(home, ".pocketrocket/rust.packages").writeText(it.joinToString("\n", postfix = "\n")) }
            // HOME on its own line: an assignment before `.` lasts only while it is read, and the step removes ~/.cargo.
            val code = "export HOME=\"$1/home\"; PREFIX=/tmp/pr-none POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$2\"; . \"$3\"; " +
                "FAKE_APT=\"$1\" PR_LOG=\"$1/log\" TOTAL_STEPS=2; step_rust_remove"
            val p = ProcessBuilder(listOf("bash", "-c", code, "_", dir.path, script.path, fakeApt.path)).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
            assertFalse(File(home, ".cargo").exists())
            assertFalse(File(home, ".pocketrocket/rust.packages").exists())
            return File(dir, "installed").readLines().filter { it.isNotBlank() }.sorted() to out
        } finally {
            dir.deleteRecursively()
        }
    }

    private val rustDeps = listOf("rust clang", "rust lld", "rust libllvm", "rust rust-std-aarch64-linux-android", "clang libllvm")
    private val onPhone = listOf(
        "bash", "clang", "lld", "libllvm", "rust", "rust-std-aarch64-linux-android", "rust-std-wasm32-unknown-unknown", "rust-std-wasm32-wasip1",
    )

    @Test fun uninstallAfterATerminalInstallTakesRustAndEveryTarget() {
        if (!script.exists()) return
        val (left, out) = remove(onPhone, rustDeps, record = null)
        // No record of what came with it, so clang, lld and libllvm stay (another tool or the user may want them).
        assertEquals(out, listOf("bash", "clang", "libllvm", "lld"), left)
        assertTrue(out, out.contains("[ok] Rust removed"))
    }

    @Test fun uninstallAfterTheTilesInstallAlsoTakesWhatItBroughtIn() {
        if (!script.exists()) return
        val (left, out) = remove(onPhone, rustDeps, record = listOf("clang", "libllvm", "lld", "rust", "rust-std-aarch64-linux-android"))
        assertEquals(out, listOf("bash"), left)
    }

    @Test fun uninstallLeavesWhatOtherPackagesNeed() {
        if (!script.exists()) return
        // maturin needs rust; another tool's package needs clang.
        val deps = rustDeps + listOf("maturin rust", "chromium clang")
        val (left, out) = remove(onPhone + listOf("maturin", "chromium"), deps, record = listOf("clang", "libllvm", "lld", "rust", "rust-std-aarch64-linux-android"))
        assertTrue(out, "rust" in left && "rust-std-aarch64-linux-android" in left && "clang" in left && "maturin" in left)
        // Targets stay with the compiler that stays; the wasm target is its own add-on and goes.
        assertTrue(out, "rust-std-wasm32-wasip1" in left)
        assertFalse(out, "rust-std-wasm32-unknown-unknown" in left)
        assertTrue(out, out.contains("rust rust-std-aarch64-linux-android stayed"))
    }
}
