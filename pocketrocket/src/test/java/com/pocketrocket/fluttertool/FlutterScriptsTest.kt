package com.pocketrocket.fluttertool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** App start brings the Flutter and Rust add-ons' scripts up to this APK's, only while each add-on owns them (decision 105). */
class FlutterScriptsTest {
    private val setup = File("../setup")
    private val dir = Files.createTempDirectory("flutter-scripts").toFile()
    private val prefix = File(dir, "usr").apply { File(this, "bin").mkdirs() }
    private val launcher = File(prefix, FlutterTool.LAUNCHER_RELATIVE)
    private val shasum = File(prefix, FlutterScripts.SHASUM_RELATIVE)
    private val rustup = File(prefix, FlutterScripts.RUSTUP_RELATIVE)

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun asset(name: String) = File(setup, name).readBytes()
    private fun sync(rust: Boolean) = FlutterScripts.sync(prefix, rust, ::asset)

    @Test fun theMarksAreInTheScripts() {
        assertTrue(File(setup, "flutter").readText().contains(FlutterScripts.LAUNCHER_MARK))
        assertTrue(File(setup, "rustup").readText().contains(FlutterScripts.RUSTUP_MARK))
        val script = File(setup, "pocketrocket-setup.sh").readText()
        assertTrue(script.contains("RUST_RUSTUP_MARK=\"${FlutterScripts.RUSTUP_MARK}\""))
        assertTrue(script.contains("FLUTTER_BIN_DIR=\"\${PREFIX:-}/${FlutterScripts.BIN_DIR_RELATIVE}\""))
        assertTrue(File(setup, "flutter").readText().contains("PR_BIN_DIR=\"\$PREFIX/${FlutterScripts.BIN_DIR_RELATIVE}\""))
    }

    @Test fun nothingIsWrittenWithoutTheAddOns() {
        assertEquals(emptyList<String>(), sync(rust = false))
        assertFalse(launcher.exists() || shasum.exists() || rustup.exists())
    }

    @Test fun anOldLauncherAndItsShasumComeUpToDateOnce() {
        launcher.writeText("#!/bin/sh\n# ${FlutterScripts.LAUNCHER_MARK}, an older one\n")
        Files.createSymbolicLink(File(prefix, "bin/dart").toPath(), File("flutter").toPath())
        assertEquals(listOf("flutter", "shasum"), sync(rust = false))
        assertTrue(launcher.readBytes().contentEquals(asset("flutter")))
        assertTrue(shasum.readBytes().contentEquals(asset("shasum")))
        assertTrue(launcher.canExecute() && shasum.canExecute())
        assertTrue(Files.isSymbolicLink(File(prefix, "bin/dart").toPath()))
        assertEquals(emptyList<String>(), sync(rust = false)) // the same content is not written again
        assertFalse(File(prefix, "bin/.flutter.tmp").exists())
    }

    @Test fun someoneElsesFlutterIsLeftAlone() {
        launcher.writeText("#!/bin/sh\nexec /opt/flutter/bin/flutter \"$@\"\n")
        assertEquals(emptyList<String>(), sync(rust = false))
        assertTrue(launcher.readText().contains("/opt/flutter"))
        assertFalse(shasum.exists())
    }

    @Test fun rustupFollowsTheRustPackageAndNeverReplacesAnotherOne() {
        assertEquals(listOf("rustup"), sync(rust = true))
        assertTrue(rustup.readBytes().contentEquals(asset("rustup")) && rustup.canExecute())

        rustup.writeText("#!/bin/sh\n# ${FlutterScripts.RUSTUP_MARK}, an older one\n")
        assertEquals(emptyList<String>(), sync(rust = false)) // Rust gone: nothing written
        assertEquals(listOf("rustup"), sync(rust = true))

        rustup.writeText("#!/bin/sh\n# the real rustup\n")
        assertEquals(emptyList<String>(), sync(rust = true))
        assertTrue(rustup.readText().contains("the real rustup"))
    }

    @Test fun aScriptTheBuildLacksIsSkipped() {
        launcher.writeText("# ${FlutterScripts.LAUNCHER_MARK}\n")
        val written = FlutterScripts.sync(prefix, true) { name -> if (name == "shasum") error("stub build") else asset(name) }
        assertEquals(listOf("flutter", "rustup"), written)
        assertFalse(shasum.exists())
    }
}
