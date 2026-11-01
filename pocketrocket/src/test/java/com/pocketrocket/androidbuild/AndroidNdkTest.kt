package com.pocketrocket.androidbuild

import com.pocketrocket.cpp.CppTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The Android NDK and C and C++ tools (decisions 50 and 96): the setup script's side, and the facts the pages use. */
class AndroidNdkTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val library = File("../setup/android-ndk.sh")
    private val dir = Files.createTempDirectory("ndk").toFile()
    private val home = File(dir, "home").apply { mkdirs() }
    private val sdk = File(dir, "android-sdk")
    private val hook = File(home, ".gradle/init.d/pocketrocket-android-ndk.gradle")

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Runs a function from the real setup script with its home, SDK and package lists in a temp folder. */
    private fun shell(code: String, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("bash", "-c", ". \"$1\"; shift; $code", "_", script.path) + args).redirectErrorStream(true).apply {
            environment().putAll(
                mapOf(
                    "PREFIX" to File(dir, "usr").path, "HOME" to home.path, "POCKETROCKET_HOME" to File(home, ".pocketrocket").path,
                    "POCKETROCKET_ANDROID_SDK_DIR" to sdk.path, "POCKETROCKET_SETUP_SOURCE_ONLY" to "1", "TMPDIR" to dir.path, "PR_LOG" to "/dev/null",
                ),
            )
        }.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun body(function: String) = (script.readText() + library.readText()).substringAfter("\n$function() {\n").substringBefore("\n}\n")

    @Test fun eachNdkVersionFolderClaimsItsOwnRevisionOverOneShim() {
        val (code, out) = shell("android_ndk_version_folders; printf %s \"\$ANDROID_NDK_VERSIONS\"")
        assertEquals(out, 0, code)
        val versions = out.trim().split(" ")
        assertTrue(versions.toString(), AndroidNdkTool.REVISION in versions && versions.size >= 2)
        versions.forEach { v ->
            val folder = File(sdk, "ndk/$v")
            assertTrue(File(folder, "source.properties").readText().contains("Pkg.Revision = $v\n"))
            listOf("meta", "build", "toolchains", "ndk-build").forEach { link ->
                assertEquals(File(sdk, "ndk-shim/$link").path, Files.readSymbolicLink(File(folder, link).toPath()).toString())
            }
        }
        assertTrue(File(sdk, "cmake/3.22.1/source.properties").readText().contains("Pkg.Revision = 3.22.1\n"))
    }

    @Test fun theShimClaimsTheReleaseItsNdkBuildComesFrom() {
        assertEquals(AndroidNdkTool.REVISION, shell("printf %s \"\$NDK_REVISION\"").second)
        assertEquals(sdk.path + "/ndk-shim", shell("printf %s \"\$ANDROID_NDK_SHIM_DIR\"").second)
        assertTrue(AndroidNdkTool.SHIM_RELATIVE.startsWith(AndroidBuildTool.SDK_RELATIVE))
    }

    @Test fun removingTheNdkKeepsTheHookWhileAndroidOwnsIt() {
        File(sdk, "platforms").mkdirs()
        val (code, out) = shell("android_ndk_write_hook; android_ndk_version_folders; mkdir -p \"\$ANDROID_NDK_SHIM_DIR\"; android_ndk_remove")
        assertEquals(out, 0, code)
        assertTrue(hook.isFile)
        assertEquals(setOf("platforms"), sdk.list()!!.toSet())

        sdk.deleteRecursively()
        assertEquals(0, shell("android_ndk_remove").first)
        assertFalse(hook.exists())
    }

    @Test fun androidWritesTheHookAndItsRemovalTakesTheNdkAndTheHook() {
        assertTrue(body("step_android_wire").contains("android_ndk_write_hook"))
        val remove = body("step_android_remove_files")
        assertTrue(remove, remove.indexOf("android_ndk_remove\n") in 0 until remove.indexOf("rm -rf \"\$ANDROID_SDK_DIR\""))
        assertTrue(remove.contains("android_ndk_remove_hook"))
    }

    @Test fun removingCppTakesTheNdkFirst() {
        val remove = body("step_cpp_remove")
        assertTrue(remove, remove.indexOf("android_ndk_remove") in 0 until remove.indexOf("step_pkg_tool_remove c-cpp"))
    }

    @Test fun theHookNeverThrowsWhileGradleConfigures() {
        val (code, out) = shell("android_ndk_write_hook")
        assertEquals(out, 0, code)
        val text = hook.readText()
        assertFalse(text, text.contains("@POCKETROCKET_"))
        assertTrue(text.contains("new File(\"${sdk.path}/ndk-shim\")"))
        assertTrue(text.contains("new File(\"${sdk.path}/ndk-placeholder\")"))
        // The only throw is inside a native task's doFirst, so it runs when that task runs, never while configuring.
        assertEquals(1, Regex("throw ").findAll(text).count())
        assertTrue(text.contains("t.doFirst { throw new GradleException(message) }"))
        assertTrue(text.contains("android.ndkPath = ndkDir.path"))
        assertFalse(text.contains("ANDROID_STL"))
    }

    @Test fun theNdkNeedsTheAndroidToolAndCpp() {
        val (code, out) = shell("TOTAL_STEPS=4; step_android_ndk_build")
        assertNotEquals(0, code)
        assertTrue(out, out.contains("Install the Android tool first"))
        // An SDK the plugin recognises (android_sdk_ready), and C and C++ with a package missing.
        File(dir, "usr/${AndroidBuildTool.JAVA_RELATIVE}/bin/java").apply { parentFile!!.mkdirs(); writeText(""); setExecutable(true) }
        listOf("platforms/android-37.0", "build-tools/36.0.0").forEach { File(sdk, "$it/package.xml").apply { parentFile!!.mkdirs(); writeText("") } }
        val (code2, out2) = shell("missing_packages() { printf lldb; }; TOTAL_STEPS=4; step_android_ndk_build")
        assertNotEquals(0, code2)
        assertTrue(out2, out2.contains("Install C and C++ first"))
    }

    @Test fun anOldNdkInstallHandsItsCompilersToCpp() {
        val lists = File(home, ".pocketrocket").apply { mkdirs() }
        File(lists, "android-ndk.packages").writeText("clang\ncmake\nlibllvm\n")
        File(lists, "c-cpp.packages").writeText("lldb\n")
        assertEquals(0, shell("android_ndk_hand_over_packages").first)
        assertFalse(File(lists, "android-ndk.packages").exists())
        assertEquals(listOf("clang", "cmake", "libllvm", "lldb"), File(lists, "c-cpp.packages").readLines())
        assertEquals(File(home, ".pocketrocket/c-cpp.packages").path, shell("printf %s \"\$CPP_ADDED_FILE\"").second)
    }

    @Test fun stubsKeepPublicNamesAndVersionsAndAddTheAbisOwn() {
        val phone = File(dir, "phone.nm").apply {
            writeText(
                listOf(
                    "LIBC@@LIBC A 0 0", "LIBC_P@@LIBC_P A 0 0", "malloc@@LIBC T 10 4", "getrandom@@LIBC_P T 20 8", "environ@@LIBC B 30 8",
                    "old@LIBC T 40 4", "android_set_abort_message@@LIBC_PLATFORM T 50 4", "__private@@LIBC_PRIVATE T 60 4",
                ).joinToString("\n", postfix = "\n"),
            )
        }
        val abi = File(dir, "abi.nm").apply { writeText("malloc@@LIBC T 10 4\ndl_unwind_find_exidx@@LIBC T 70 4\n") }
        val fake = "llvm-nm() { cat \"\${@: -1}\"; }; llvm-readelf() { echo ' 0x0e (SONAME) Library soname: [libc.so]'; }; "
        val (code, out) = shell(fake + "ndk_stub_source \"$1\" \"$2\" \"$3\" /no/such.so", dir.path, phone.path, abi.path)
        assertEquals(out, 0, code)
        assertEquals("libc.so", out.trim())
        val source = File(dir, "stub.c").readText()
        val map = File(dir, "vers.map").readText()
        listOf("malloc", "getrandom", "environ", "dl_unwind_find_exidx").forEach { assertTrue(it, source.contains("\"$it\"")) }
        assertEquals(1, Regex("\"malloc\"").findAll(source).count())
        assertTrue(source.contains("char d2[8] __asm__(\"environ\")"))
        listOf("old", "android_set_abort_message", "__private").forEach { assertFalse(it, source.contains("\"$it\"")) }
        assertTrue(map, Regex("LIBC_P \\{\\s+global:\\s+getrandom;\\s+\\};").containsMatchIn(map))
        assertTrue(map, map.contains("dl_unwind_find_exidx;"))
    }

    @Test fun theToolchainBuildsFourAbisAndDefaultsToStaticLibcxx() {
        val text = File("../setup/android-ndk.toolchain.cmake").readText()
        assertTrue(Regex("if\\(NOT ANDROID_STL\\)\\s+set\\(ANDROID_STL c\\+\\+_static\\)").containsMatchIn(text))
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { assertTrue(it, text.contains("ANDROID_ABI STREQUAL \"$it\"")) }
    }

    @Test fun pageStepsAreTheScriptsSteps() {
        val all = script.readText()
        // C and C++'s own steps come from pkg_tool's "Installing <title>" and "Removing <title>", checked below.
        (AndroidNdkTool.installSteps(cppComplete = false) + AndroidNdkTool.REMOVE_STEPS + CppTool.INSTALL_STEPS + CppTool.removeSteps(ndkInstalled = true))
            .toSet().filterNot { it.endsWith("C and C++") }.forEach { assertTrue(it, all.contains("step \"$it\"")) }
        assertTrue(all.contains("step \"Installing \$(pkg_tool_title \"\$name\")\"") && all.contains("step \"Removing \$(pkg_tool_title \"\$name\")\""))
        assertEquals(5, AndroidNdkTool.installSteps(cppComplete = false).size)
        assertEquals(4, AndroidNdkTool.installSteps(cppComplete = true).size)
        assertTrue(all.contains("android-ndk) TOTAL_STEPS=4; cpp_complete || TOTAL_STEPS=5 ;;"))
        // C and C++ is a package tool: its install step is pkg_tool_install's "Installing <title>".
        assertEquals("C and C++", shell("pkg_tool_title c-cpp").second)
        assertEquals(CppTool.PACKAGES.joinToString(" ") { it.first }, shell("pkg_tool_packages c-cpp").second)
        assertEquals(listOf("Checking the device", "Removing C and C++"), CppTool.removeSteps(ndkInstalled = false))
        assertEquals(CppTool.PACKAGES.joinToString(" ") { it.first }, shell("printf %s \"\$CPP_PACKAGES\"").second)
    }

    @Test fun cppIsWhatDpkgSays() {
        fun pkgs(vararg names: String) = names.associateWith { com.pocketrocket.termux.DpkgStatus.Package("1:21.1.8", 1000) }
        val all = CppTool.PACKAGES.map { it.first }.toTypedArray()
        assertFalse(CppTool.presence(emptyMap()).present)
        assertTrue(CppTool.presence(pkgs(*all)).complete)
        assertEquals("lldb missing", CppTool.presence(pkgs(*all) - "lldb").problem)
        assertFalse(CppTool.presence(pkgs(*all) - "clang").present)
    }

    @Test fun anNdkFromBeforeNdkBuildNeedsRepair() {
        val prefix = File(dir, "p")
        val shim = File(prefix, AndroidNdkTool.SHIM_RELATIVE)
        File(shim, AndroidNdkTool.TOOLCHAIN_RELATIVE).apply { parentFile!!.mkdirs(); writeText("") }
        File(prefix, "${AndroidNdkTool.VERSIONS_RELATIVE}/${AndroidNdkTool.REVISION}/source.properties").apply { parentFile!!.mkdirs(); writeText("") }
        File(home, AndroidNdkTool.INIT_SCRIPT_RELATIVE).apply { parentFile!!.mkdirs(); writeText("") }
        val pkgs = AndroidNdkTool.PACKAGES.associate { it.first to com.pocketrocket.termux.DpkgStatus.Package("1", null) }
        assertEquals("ndk-build missing", AndroidNdkTool.presence(prefix, home, pkgs).problem)
        File(shim, "ndk-build").writeText("")
        assertTrue(AndroidNdkTool.presence(prefix, home, pkgs).complete)
    }

    @Test fun sizesIncludeCppOnlyWhenItIsMissing() {
        assertEquals(AndroidNdkTool.DOWNLOAD_MB, AndroidNdkTool.downloadMb(cppInstalled = true))
        assertEquals(AndroidNdkTool.DOWNLOAD_MB + CppTool.DOWNLOAD_MB, AndroidNdkTool.downloadMb(cppInstalled = false))
        assertEquals(AndroidNdkTool.INSTALLED_MB + CppTool.INSTALLED_MB, AndroidNdkTool.installedMb(cppInstalled = false))
    }
}
