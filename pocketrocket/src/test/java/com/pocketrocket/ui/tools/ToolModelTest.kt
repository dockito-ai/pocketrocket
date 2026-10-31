package com.pocketrocket.ui.tools

import com.pocketrocket.node.NodeJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolModelTest {
    private val node = ToolSpec("Node.js and npm", ToolKind.BuiltIn, hasVersion = true)
    private val dockito = ToolSpec("Dockito", ToolKind.BuiltIn, hasVersion = true, canUpdate = true, needsLogin = true)
    private val chrome = ToolSpec("Headless Chrome", ToolKind.Optional, hasVersion = true, downloadMb = 310, installedMb = 840)
    private val android = ToolSpec("Android", ToolKind.Optional, downloadMb = 390, installedMb = 880)
    private val wrangler = ToolSpec("Wrangler", ToolKind.Optional, hasVersion = true, needsLogin = true, downloadMb = 78, installedMb = 62)

    private val installed = ToolState(installed = true)
    private val missing = ToolState(installed = false)

    @Test fun builtInPageHasNoInstallSection() {
        assertEquals(
            listOf(ToolSection.Header, ToolSection.Version, ToolSection.Accounts, ToolSection.Storage),
            ToolModel.sections(dockito, installed.copy(sizeMb = 30)),
        )
        assertEquals(listOf(ToolSection.Header, ToolSection.Version), ToolModel.sections(node, installed))
    }

    @Test fun optionalPageAlwaysHasInstallAndStorage() {
        assertEquals(listOf(ToolSection.Header, ToolSection.Storage, ToolSection.Install), ToolModel.sections(android, installed))
        assertEquals(listOf(ToolSection.Header, ToolSection.Storage, ToolSection.Install), ToolModel.sections(android, missing))
    }

    @Test fun versionAndAccountsOnlyWhileInstalled() {
        assertEquals(
            listOf(ToolSection.Header, ToolSection.Version, ToolSection.Accounts, ToolSection.Storage, ToolSection.Install),
            ToolModel.sections(wrangler, installed),
        )
        assertEquals(listOf(ToolSection.Header, ToolSection.Storage, ToolSection.Install), ToolModel.sections(wrangler, missing))
    }

    @Test fun tileLinePutsUpdateFirstThenSignedInThenSize() {
        val all = installed.copy(latest = "0.0.2", updateAvailable = true, signedIn = "me@example.com", sizeMb = 30)
        assertEquals("Included · Update to 0.0.2", ToolModel.status(dockito, all).line)
        assertEquals(ToolTone.Update, ToolModel.status(dockito, all).tone)
        assertEquals("Included · me@example.com", ToolModel.status(dockito, all.copy(updateAvailable = false)).line)
        assertEquals("Included · 30 MB", ToolModel.status(dockito, all.copy(updateAvailable = false, signedIn = null)).line)
        assertEquals(ToolTone.Installed, ToolModel.status(dockito, all.copy(updateAvailable = false)).tone)
    }

    @Test fun tileLineForOptionalTools() {
        assertEquals("Installed · 840 MB", ToolModel.status(chrome, installed).line)
        assertEquals("Not installed · 310 MB download", ToolModel.status(chrome, missing).line)
        assertEquals("Installed · me@example.com", ToolModel.status(wrangler, installed.copy(signedIn = "me@example.com")).line)
        assertEquals("Needs the Android tool", ToolModel.status(android, missing.copy(blockedBy = "Needs the Android tool")).line)
    }

    @Test fun signedInIgnoredForToolsWithoutLogins() {
        assertEquals("Installed · 840 MB", ToolModel.status(chrome, installed.copy(signedIn = "x")).line)
    }

    @Test fun jobsComeBeforeEverything() {
        val busy = installed.copy(latest = "1", updateAvailable = true)
        assertEquals("Installing… 40%", ToolModel.status(chrome, busy.copy(job = ToolJob.Installing, progress = 40)).line)
        assertEquals("Uninstalling…", ToolModel.status(chrome, busy.copy(job = ToolJob.Uninstalling)).line)
        assertEquals("Install failed", ToolModel.status(chrome, busy.copy(job = ToolJob.InstallFailed)).line)
        assertEquals("Updating…", ToolModel.status(dockito, busy.copy(updating = true)).line)
        assertEquals("Checking…", ToolModel.status(chrome, missing.copy(checked = false)).line)
    }

    @Test fun missingBuiltInAsksForSetup() {
        val s = ToolModel.status(node, missing)
        assertEquals("Not installed · run setup again", s.line)
        assertEquals(ToolTone.Failed, s.tone)
    }

    @Test fun pillSaysBuiltInInstalledOrNotInstalled() {
        assertEquals("Included", ToolModel.status(node, installed).short)
        assertEquals("Installed", ToolModel.status(chrome, installed).short)
        assertEquals("Not installed", ToolModel.status(chrome, missing).short)
    }

    @Test fun jobFromModuleFlags() {
        assertEquals(ToolJob.Installing, ToolModel.job(running = true, failed = false, removing = false))
        assertEquals(ToolJob.Uninstalling, ToolModel.job(running = true, failed = false, removing = true))
        assertEquals(ToolJob.UninstallFailed, ToolModel.job(running = false, failed = true, removing = true))
        assertEquals(ToolJob.None, ToolModel.job(running = false, failed = false, removing = true))
    }

    @Test fun addOnsTabComesFirst() {
        assertEquals(listOf("Add-ons", "Included"), ToolKind.entries.map { it.tab })
    }

    @Test fun summaryCountsIncludedAndInstalledAddOns() {
        val tools = listOf(node to installed, dockito to installed, chrome to installed, android to missing, wrangler to installed)
        assertEquals("2 add-ons · 2 included", ToolModel.summary(tools))
        assertEquals("1 add-on · 2 included", ToolModel.summary(listOf(node to installed, dockito to missing, chrome to installed)))
        assertEquals("no add-ons · 2 included", ToolModel.summary(listOf(node to installed, dockito to missing, chrome to missing)))
    }

    @Test fun tabsSplitByKindAndSortByName() {
        val git = ToolSpec("Git", ToolKind.BuiltIn)
        val claude = ToolSpec("Claude Code", ToolKind.BuiltIn)
        val flutter = ToolSpec("Flutter & Dart", ToolKind.Optional)
        val rust = ToolSpec("Rust", ToolKind.Optional)
        val all = listOf(wrangler, node, rust, git, chrome, flutter, dockito, android, claude)
        assertEquals(listOf("Claude Code", "Dockito", "Git", "Node.js and npm"), ToolModel.tab(all, ToolKind.BuiltIn, "") { it }.map { it.name })
        assertEquals(
            listOf("Android", "Flutter & Dart", "Headless Chrome", "Rust", "Wrangler"),
            ToolModel.tab(all, ToolKind.Optional, "  ") { it }.map { it.name },
        )
    }

    @Test fun searchFiltersBothTabsByName() {
        val flutter = ToolSpec("Flutter & Dart", ToolKind.Optional)
        val rust = ToolSpec("Rust", ToolKind.Optional)
        val all = listOf(node, dockito, flutter, rust, chrome)
        assertEquals(listOf("Flutter & Dart"), ToolModel.tab(all, ToolKind.Optional, "dart") { it }.map { it.name })
        assertEquals(emptyList<String>(), ToolModel.tab(all, ToolKind.BuiltIn, "dart") { it }.map { it.name })
        assertEquals(listOf("Node.js and npm"), ToolModel.tab(all, ToolKind.BuiltIn, " NPM ") { it }.map { it.name })
        assertEquals(listOf("Rust"), ToolModel.tab(all, ToolKind.Optional, "rUs") { it }.map { it.name })
    }

    @Test fun nodeSizeFromDpkgStatus() {
        val status = "Package: git\nStatus: install ok installed\nInstalled-Size: 10\n\nPackage: nodejs-lts\nStatus: install ok installed\nInstalled-Size: 61234\n"
        assertEquals(61234, NodeJs.installedKb(status, "nodejs-lts"))
        assertNull(NodeJs.installedKb(status, "nodejs"))
        assertNull(NodeJs.installedKb(null, "nodejs-lts"))
    }
}
