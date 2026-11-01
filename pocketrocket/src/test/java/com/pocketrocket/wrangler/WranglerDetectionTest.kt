package com.pocketrocket.wrangler

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

/** The Wrangler tile reads npm's folder for it, not a flag the install saved. */
class WranglerDetectionTest {
    private val root = File.createTempFile("wrangler", "").apply { delete(); mkdirs() }
    private val dir = File(root, "lib/node_modules/wrangler")
    private val script = File("../setup/pocketrocket-setup.sh")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(relative: String, text: String = "") = File(dir, relative).apply { parentFile?.mkdirs(); writeText(text) }

    /** What `npm install -g wrangler` leaves: Wrangler and workerd's loader as published, which throws on Android. */
    private fun npmInstall(version: String = "4.160.0") {
        file("package.json", """{"name": "wrangler", "version": "$version", "dependencies": {"workerd": "1"}}""")
        file("${WranglerTool.WORKERD_PACKAGE_RELATIVE}/package.json", """{"name": "workerd", "version": "1.20261101.0"}""")
        file(WranglerTool.WORKERD_LOADER_RELATIVE, "var x = 1;\nfunction generateBinPath() {\n  throw new Error('Unsupported platform');\n}\n")
    }

    /** The tool's own steps on top: the loader line and wrapper (the script's own function), then the Android workerd. */
    private fun patch() {
        if (!script.exists()) {
            file(WranglerTool.WORKERD_LOADER_RELATIVE, "function generateBinPath() {\n  if (process.platform === \"android\") return { binPath: \"workerd-android\" };\n")
            file(WranglerTool.WORKERD_WRAPPER_RELATIVE)
            return
        }
        val code = "PREFIX=/tmp/pr-none POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; wrangler_patch_workerd \"$2\""
        val p = ProcessBuilder(listOf("bash", "-c", code, "_", script.path, File(dir, WranglerTool.WORKERD_LOADER_RELATIVE).path)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun notThereIsNotInstalled() {
        assertFalse(WranglerTool.presence(dir).present)
        assertNull(WranglerTool.packageVersion(dir))
    }

    @Test fun aPlainNpmInstallShowsButSaysItWontStart() {
        npmInstall()
        val p = WranglerTool.presence(dir)
        assertTrue(p.present)
        assertEquals("won't start until repaired", p.problem)
        val tile = ToolStates.wrangler(WranglerState(installed = true, problem = p.problem, version = "4.160.0", workerdVersion = "1.20261101.0", checked = true))
        assertEquals("Installed · won't start until repaired", ToolModel.status(Tool.Wrangler.spec, tile).line)
        assertEquals(listOf("Wrangler" to "4.160.0", "workerd" to "1.20261101.0"), tile.versions)
    }

    @Test fun patchedWithoutTheAndroidWorkerdHasNoLocalDev() {
        npmInstall(WranglerTool.VERSION)
        patch()
        assertEquals("local dev missing", WranglerTool.presence(dir).problem)
        file(WranglerTool.WORKERD_RELATIVE)
        assertTrue(WranglerTool.presence(dir).complete)
        assertEquals(WranglerTool.VERSION, WranglerTool.packageVersion(dir))
        // No flag is needed, and an old one alone means nothing.
        assertFalse(File(dir, ".pocketrocket-installed").exists())
        File(dir, WranglerTool.WORKERD_RELATIVE).delete()
        file(".pocketrocket-installed")
        assertFalse(WranglerTool.presence(dir).complete)
    }

    @Test fun npmUninstallShowsAsGone() {
        npmInstall()
        patch()
        file(WranglerTool.WORKERD_RELATIVE)
        dir.deleteRecursively() // `npm uninstall -g wrangler`
        assertFalse(WranglerTool.presence(dir).present)
    }

    @Test fun theScriptPatchesTheLineTheAppLooksFor() {
        if (!script.exists()) return
        assertTrue(script.readText().contains("grep -q '${WranglerTool.LOADER_PATCH}'"))
    }
}
