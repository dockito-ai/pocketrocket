package com.pocketrocket.ui.tools

import com.pocketrocket.fluttertool.FlutterAction
import com.pocketrocket.fluttertool.FlutterRustState
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.fluttertool.FlutterVersion
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Flutter & Dart and Rust are two tools over one module (decision 87): each tile reads only its own part. */
class ToolStatesTest {
    private val running = SetupState(phase = SetupPhase.Running)
    private val rust = mapOf("rust" to DpkgStatus.Package("1.91.0", 578196), "rust-std-aarch64-linux-android" to DpkgStatus.Package("1.91.0", 24908))
    private val both = FlutterRustState(flutterInstalled = true, rustPackages = rust, checked = true, versions = listOf(FlutterVersion("3.47.9", true)))

    @Test fun eachToolIsInstalledOnItsOwn() {
        val flutterOnly = both.copy(rustPackages = emptyMap())
        assertTrue(ToolStates.flutter(flutterOnly).installed)
        assertFalse(ToolStates.rust(flutterOnly, null).installed)
        val rustOnly = both.copy(flutterInstalled = false, versions = emptyList())
        assertFalse(ToolStates.flutter(rustOnly).installed)
        assertNull(ToolStates.flutter(rustOnly).sizeMb)
        assertTrue(ToolStates.rust(rustOnly, null).installed)
    }

    @Test fun aRustJobShowsOnlyOnRust() {
        val s = both.copy(job = running, action = FlutterAction.RemoveRust)
        assertEquals(ToolJob.Uninstalling, ToolStates.rust(s, null).job)
        assertEquals(ToolJob.None, ToolStates.flutter(s).job)
        assertFalse(ToolStates.flutter(s).updating)
    }

    @Test fun flutterInstallIsItsJobAndVersionWorkIsAnUpdate() {
        val install = both.copy(flutterInstalled = false, versions = emptyList(), job = running, action = FlutterAction.InstallFlutter)
        assertEquals(ToolJob.Installing, ToolStates.flutter(install).job)
        assertEquals(ToolJob.None, ToolStates.rust(install, null).job)
        val add = both.copy(job = running, action = FlutterAction.AddVersion, target = "3.44.0")
        assertEquals(ToolJob.None, ToolStates.flutter(add).job)
        assertTrue(ToolStates.flutter(add).updating)
        assertFalse(ToolStates.rust(add, null).updating)
    }

    @Test fun sizesAndVersionsAreEachToolsOwn() {
        assertEquals(FlutterTool.FLUTTER_INSTALLED_MB, ToolStates.flutter(both).sizeMb)
        assertEquals(listOf("Default" to "3.47.9", "FVM" to FlutterTool.FVM_VERSION), ToolStates.flutter(both).versions)
        val row = UpdateRow(UpdateComponent.Rust, installed = "1.91.0", latest = "1.92.0", available = true, checking = false)
        val rust = ToolStates.rust(both, row)
        assertEquals(listOf("Rust" to "1.91.0"), rust.versions)
        assertTrue(rust.updateAvailable)
        assertEquals("Installed · Update to 1.92.0", ToolModel.status(Tool.Rust.spec, rust).line)
    }

    @Test fun includedToolsFromTheirUpdatesRow() {
        assertFalse(ToolStates.fromRow(null).checked)
        val git = ToolStates.fromRow(UpdateRow(UpdateComponent.Git, installed = "2.51.0", latest = "2.51.0", available = false, checking = false))
        assertEquals("Included", ToolModel.status(Tool.Git.spec, git).line)
        assertEquals(ToolKind.BuiltIn, Tool.ClaudeCode.spec.kind)
        assertEquals(ToolKind.Optional, Tool.Rust.spec.kind)
        assertEquals("Flutter & Dart", Tool.Flutter.spec.name)
    }

    @Test fun brandColoursStayLegibleOnBothThemes() {
        val darkTile = Color(0xFF1E2229)
        val lightTile = Color(0xFFEDEFF2)
        val rustOnDark = legible(Color(0xFF000000), Color.White, darkTile)
        assertTrue(rustOnDark.luminance() > 0.1f)
        assertEquals(Color(0xFF000000), legible(Color(0xFF000000), Color.Black, lightTile))
        assertEquals(Color(0xFFF38020), legible(Color(0xFFF38020), Color.White, darkTile))
    }
}
