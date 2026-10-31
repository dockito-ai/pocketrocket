package com.pocketrocket.pkgtools

import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolJob
import com.pocketrocket.ui.tools.ToolKind
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Package tools (decision 92): what dpkg says is installed, what Uninstall takes, and the script agreeing. */
class PackageToolTest {
    private val script = File("../setup/pocketrocket-setup.sh")

    private fun block(pkg: String, version: String, kb: Int, status: String = "install ok installed") =
        "Package: $pkg\nStatus: $status\nPriority: optional\nInstalled-Size: $kb\nVersion: $version\nDescription: x\n continued line\n"

    /** This phone on 31 Oct 2026: Headless Chrome brought python and pip; no uv, no bun; bun once installed and purged. */
    private val phone = listOf(
        block("chromium", "156.0.8078.47", 300000),
        block("python", "3.14.8", 26324),
        block("python-pip", "26.2.2-2", 7168),
        block("git", "1:2.51.0", 40000),
        block("bun", "1.4.4-2", 86904, status = "deinstall ok config-files"),
        block("rust-std-wasm32-unknown-unknown", "1.99.1", 89892),
    ).joinToString("\n")

    private val pkgs = DpkgStatus.parse(phone, PackageTool.allWatched)

    private fun shell(code: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("bash", "-c", "PREFIX=/tmp/pr-none POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; $code", "_", script.path))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun dpkgStatusKeepsOnlyInstalledPackages() {
        val all = DpkgStatus.parse(phone)
        assertEquals(setOf("chromium", "python", "python-pip", "git", "rust-std-wasm32-unknown-unknown"), all.keys)
        assertEquals(DpkgStatus.Package("1:2.51.0", 40000), all["git"])
        assertEquals("2.51.0", DpkgStatus.display(all.getValue("git").version))
        assertFalse("git" in pkgs) // only the watched ones
        assertTrue(DpkgStatus.parse(null).isEmpty())
    }

    @Test fun installedIsWhatDpkgSaysNotAFlag() {
        assertTrue(PackageTool.Python.installed(pkgs))
        assertFalse(PackageTool.Bun.installed(pkgs)) // purged, config left behind
        assertTrue(PackageTool.RustWasm.installed(pkgs))
        val bunFromATerminal = DpkgStatus.parse(phone + "\n" + block("bun", "1.4.4-2", 86904), PackageTool.allWatched)
        assertTrue(PackageTool.Bun.installed(bunFromATerminal))
        val pythonUninstalled = DpkgStatus.parse(phone.replace("Package: python\n", "Package: python-gone\n"), PackageTool.allWatched)
        assertFalse(PackageTool.Python.installed(pythonUninstalled))
    }

    @Test fun pythonWithoutUvIsInstalledAndOffersTheRest() {
        assertEquals(listOf("uv"), PackageTool.Python.missing(pkgs))
        assertEquals(listOf("Python" to "3.14.8", "pip" to "26.2.2-2"), PackageTool.Python.versions(pkgs))
        assertEquals(33, PackageTool.Python.sizeMb(pkgs))
        assertEquals(emptyList<String>(), PackageTool.Bun.missing(pkgs))
        assertNull(PackageTool.Bun.sizeMb(pkgs))
    }

    @Test fun headlessChromeKeepsPythonAndPip() {
        assertEquals("Headless Chrome", PackageTool.Python.held(pkgs)?.name)
        assertEquals(listOf("Python", "pip"), PackageTool.Python.kept(pkgs))
        assertEquals(emptyList<String>(), PackageTool.Python.removable(pkgs))
        val withUv = DpkgStatus.parse(phone + "\n" + block("uv", "0.13.6", 33936), PackageTool.allWatched)
        assertEquals(listOf("uv"), PackageTool.Python.removable(withUv))
        assertEquals("Headless Chrome needs Python and pip, so they stay. uv is removed. Note.", PackageTool.Python.removeLine(withUv, "Note."))
        val noChrome = withUv - "chromium"
        assertNull(PackageTool.Python.held(noChrome))
        assertEquals("Python, pip and uv are removed. Note.", PackageTool.Python.removeLine(noChrome, "Note."))
    }

    @Test fun tilesReadThePackages() {
        val s = PackageToolsState(checked = true, packages = pkgs)
        assertEquals("Installed · 33 MB", ToolModel.status(Tool.Python.spec, ToolStates.packageTool(PackageTool.Python, s, null)).line)
        assertEquals("Not installed · 25 MB download", ToolModel.status(Tool.Bun.spec, ToolStates.packageTool(PackageTool.Bun, s, null)).line)
        assertEquals(ToolKind.Optional, Tool.Bun.spec.kind)
        val wasm = s.copy(job = SetupState(phase = SetupPhase.Running), tool = PackageTool.RustWasm)
        assertEquals(ToolJob.None, ToolStates.packageTool(PackageTool.Bun, wasm, null).job)
        assertEquals(ToolJob.Installing, ToolStates.packageTool(PackageTool.RustWasm, wasm, null).job)
    }

    @Test fun theAppAndTheScriptAgree() {
        if (!script.exists()) return
        PackageTool.entries.forEach { t ->
            assertEquals(t.packages.joinToString(" ") { it.first }, shell("pkg_tool_packages ${t.option}").second)
            assertEquals("Installing ${t.title}", t.installSteps.last())
            assertEquals(t.title, shell("pkg_tool_title ${t.option}").second)
            assertTrue(Regex("""[ |]${t.option}\|${t.removeOption}\|""").containsMatchIn(script.readText())) // --only takes both
        }
    }

    @Test fun uvConfigBlockGoesOnTopAndComesOffCleanly() {
        if (!script.exists()) return
        val dir = File.createTempFile("uvconfig", "").apply { delete(); mkdirs() }
        val toml = File(dir, "uv/uv.toml")
        assertEquals(0, shell("uv_config_add '${toml.path}' && uv_config_add '${toml.path}'").first)
        assertEquals(1, Regex("python-downloads = \"never\"").findAll(toml.readText()).count())
        assertEquals(0, shell("uv_config_remove '${toml.path}'").first)
        assertFalse(toml.exists()) // nothing of the user's was there, so the file goes

        toml.parentFile!!.mkdirs()
        toml.writeText("[pip]\nindex-url = \"https://example.org/simple\"\n")
        shell("uv_config_add '${toml.path}'")
        assertTrue(toml.readText().startsWith("# >>> PocketRocket")) // before any table, so it stays top-level
        shell("uv_config_remove '${toml.path}'")
        assertEquals("[pip]\nindex-url = \"https://example.org/simple\"\n", toml.readText())
        dir.deleteRecursively()
    }
}
