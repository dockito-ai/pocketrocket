package com.pocketrocket.androidbuild

import com.pocketrocket.setup.ScriptToolState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.termux.DpkgStatus
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Android and Android NDK tiles read the SDK folders, dpkg and Gradle's files, not a flag the install saved. */
class AndroidDetectionTest {
    private val root = File.createTempFile("android", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr").apply { mkdirs() }
    private val home = File(root, "home").apply { mkdirs() }
    private val sdk = File(prefix, AndroidBuildTool.SDK_RELATIVE)
    private val gradleProperties = File(home, AndroidBuildTool.GRADLE_PROPERTIES_RELATIVE)
    private val script = File("../setup/pocketrocket-setup.sh")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun pkgs(vararg names: String) = names.associateWith { DpkgStatus.Package("1", 1) }
    private val androidPkgs = pkgs("openjdk-17", "gradle", "aapt2", "aapt", "aidl", "android-tools", "unzip")
    private val ndkPkgs = pkgs("clang", "lld", "cmake", "ninja", "make", "ndk-sysroot")

    private fun file(f: File, text: String = "") = f.apply { parentFile?.mkdirs(); writeText(text) }

    /** The SDK as this phone has it on 1 Nov 2026 (no flag needed): platform, build tools, license, aapt2 setting. */
    private fun sdkAsInstalled() {
        file(File(sdk, "platforms/android-37.0/android.jar"))
        file(File(sdk, "platforms/android-37.0/package.xml"))
        file(File(sdk, "build-tools/36.0.0/package.xml"))
        file(File(sdk, "licenses/android-sdk-license"), "\nabc")
        file(gradleProperties, "org.gradle.parallel=true\n# >>> PocketRocket\n${AndroidBuildTool.AAPT2_SETTING}/usr/bin/aapt2\n# <<<\n")
    }

    private fun android(p: Map<String, DpkgStatus.Package> = androidPkgs) = AndroidBuildTool.presence(sdk, gradleProperties, p)

    @Test fun androidIsWhatsOnThePhone() {
        assertEquals(ToolPresence.Absent, android())
        sdkAsInstalled()
        assertTrue(android().complete)
        // An old install's flag means nothing either way.
        file(File(sdk, ".pocketrocket-installed"))
        assertTrue(android().complete)
    }

    @Test fun androidSaysWhatATerminalTookAway() {
        sdkAsInstalled()
        assertEquals("Gradle missing", android(androidPkgs - "gradle").problem)
        assertEquals("Java 17, aapt2 missing", android(androidPkgs - "openjdk-17" - "aapt2").problem)
        File(sdk, "platforms").deleteRecursively()
        assertEquals("SDK platform missing", android().problem)
        gradleProperties.writeText("org.gradle.parallel=true\n")
        assertEquals("SDK platform, aapt2 setting missing", android().problem)
        // `rm -rf $PREFIX/opt/android-sdk`: gone, whatever packages stay.
        sdk.deleteRecursively()
        assertFalse(android().present)
    }

    @Test fun aFailedInstallsLeftoversStillOfferUninstallAndRepair() {
        File(sdk, "licenses").mkdirs() // the SDK step stopped before any download
        val p = android(pkgs("openjdk-17", "gradle", "aapt2", "aapt", "aidl"))
        assertTrue(p.present)
        assertEquals("SDK platform, SDK build tools, SDK license, aapt2 setting missing", p.problem)
        val state = ToolStates.scriptTool(ScriptToolState(installed = p.present, problem = p.problem, checked = true))
        assertTrue(state.installed)
        val status = ToolModel.status(Tool.Android.spec, state)
        assertEquals("Needs repair", status.short)
        assertTrue(status.line, status.line.startsWith("Installed · SDK platform"))
    }

    private fun ndkAsInstalled() {
        file(File(prefix, "${AndroidNdkTool.SHIM_RELATIVE}/${AndroidNdkTool.TOOLCHAIN_RELATIVE}"))
        file(File(prefix, "${AndroidNdkTool.SHIM_RELATIVE}/ndk-build"))
        file(File(prefix, "${AndroidNdkTool.VERSIONS_RELATIVE}/28.2.13676358/source.properties"), "Pkg.Revision = 28.2.13676358\n")
        file(File(home, AndroidNdkTool.INIT_SCRIPT_RELATIVE))
    }

    @Test fun ndkIsWhatsOnThePhone() {
        assertFalse(AndroidNdkTool.presence(prefix, home, ndkPkgs).present)
        ndkAsInstalled()
        assertTrue(AndroidNdkTool.presence(prefix, home, ndkPkgs).complete)
        assertEquals("clang missing", AndroidNdkTool.presence(prefix, home, ndkPkgs - "clang").problem)
        File(home, AndroidNdkTool.INIT_SCRIPT_RELATIVE).delete()
        File(prefix, AndroidNdkTool.VERSIONS_RELATIVE).deleteRecursively()
        assertEquals("NDK folders, Gradle setup missing", AndroidNdkTool.presence(prefix, home, ndkPkgs).problem)
        File(prefix, AndroidNdkTool.SHIM_RELATIVE).deleteRecursively()
        assertFalse(AndroidNdkTool.presence(prefix, home, ndkPkgs).present)
    }

    @Test fun theNdkInstallChecksTheSdkNotAFlag() {
        if (!script.exists()) return
        fun ready(): Int {
            val code = "PREFIX=\"$1\" POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$2\"; android_sdk_ready"
            val p = ProcessBuilder(listOf("bash", "-c", code, "_", prefix.path, script.path)).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            return p.waitFor()
        }
        file(File(sdk, ".pocketrocket-installed"))
        assertNotEquals(0, ready())
        sdkAsInstalled()
        assertNotEquals(0, ready()) // no Java yet
        file(File(prefix, "${AndroidBuildTool.JAVA_RELATIVE}/bin/java")).setExecutable(true)
        assertEquals(0, ready())
    }

    @Test fun theAppsPackageListsAreTheScripts() {
        if (!script.exists()) return
        fun scriptList(name: String): List<String> {
            val p = ProcessBuilder(listOf("bash", "-c", "HOME=/h PREFIX=/tmp/pr-none POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; printf %s \"\$$name\"", "_", script.path)).start()
            return p.inputStream.bufferedReader().readText().split(" ").also { p.waitFor() }
        }
        assertTrue(scriptList("ANDROID_BUILD_PACKAGES").containsAll(AndroidBuildTool.PACKAGES.map { it.first }))
        // The NDK runs on C and C++'s packages; ndk-sysroot comes with clang.
        assertTrue((scriptList("CPP_PACKAGES") + "ndk-sysroot").containsAll(AndroidNdkTool.PACKAGES.map { it.first }))
        assertEquals("/tmp/pr-none/${AndroidNdkTool.SHIM_RELATIVE}", scriptList("ANDROID_NDK_SHIM_DIR").single())
        assertEquals("/h/${AndroidNdkTool.INIT_SCRIPT_RELATIVE}", scriptList("ANDROID_NDK_INIT_SCRIPT").single())
    }
}
