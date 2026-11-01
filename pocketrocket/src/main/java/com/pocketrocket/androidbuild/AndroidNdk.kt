package com.pocketrocket.androidbuild

import android.content.Context
import com.pocketrocket.cpp.Cpp
import com.pocketrocket.cpp.CppTool
import com.pocketrocket.setup.ScriptTool
import com.pocketrocket.setup.ScriptToolState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.termux.DpkgStatus
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The fixed facts of the Android NDK tool (decisions 50 and 96). Sizes are measured on the phone,
 * docs/android-ndk-gaps-spike.md: ndk-multilib and AOSP's ndk-build to download, the shim on disk.
 */
object AndroidNdkTool {
    const val ID = "android-ndk"
    const val SHIM_RELATIVE = "${AndroidBuildTool.SDK_RELATIVE}/ndk-shim"
    const val VERSIONS_RELATIVE = "${AndroidBuildTool.SDK_RELATIVE}/ndk"

    const val DOWNLOAD_MB = 74
    const val INSTALLED_MB = 140
    /** The NDK version the shim claims: the AOSP release its ndk-build comes from (r28c). */
    const val REVISION = "28.2.13676358"

    /** Under home: the Gradle init script that keeps the plugin on the shim (setup script's ANDROID_NDK_INIT_SCRIPT). */
    const val INIT_SCRIPT_RELATIVE = ".gradle/init.d/pocketrocket-android-ndk.gradle"
    const val TOOLCHAIN_RELATIVE = "build/cmake/android.toolchain.cmake"

    /** What the shim runs, from C and C++ (ndk-build needs make), as the tile names them. */
    val PACKAGES = listOf("clang" to "clang", "lld" to "lld", "cmake" to "CMake", "ninja" to "Ninja", "make" to "make", "ndk-sysroot" to "NDK sysroot")

    /** The steps the setup script prints; C and C++ is installed first when any of it is missing. */
    fun installSteps(cppComplete: Boolean): List<String> =
        listOf("Checking the device") +
            (if (cppComplete) emptyList() else listOf(CppTool.INSTALL_STEP)) +
            listOf("Fetching ndk-build", "Downloading the other ABIs", "Setting up the NDK folders")

    val REMOVE_STEPS = listOf("Checking the device", "Removing the Android NDK")

    /** What the install downloads and adds on disk, C and C++ included when it isn't there. */
    fun downloadMb(cppInstalled: Boolean) = DOWNLOAD_MB + if (cppInstalled) 0 else CppTool.DOWNLOAD_MB
    fun installedMb(cppInstalled: Boolean) = INSTALLED_MB + if (cppInstalled) 0 else CppTool.INSTALLED_MB

    /** The version folders the setup script made. Newest first. */
    fun servedVersions(folders: List<String>): List<String> =
        folders.filter { it.firstOrNull()?.isDigit() == true }.sortedByDescending { it.substringBefore('.').toIntOrNull() ?: 0 }

    /**
     * The tool as it is now, whoever set it up (decision 93). It is on the phone while the shim folder is ([prefix]
     * holds it). It works when C and C++'s compilers and make are installed (dpkg), the shim has ndk-build and its
     * toolchain file, a version folder claims a revision, and Gradle has the init script ([home] holds it). A shim from
     * before decision 96 has no ndk-build, so it reads "ndk-build missing" and Repair brings it up to date.
     */
    fun presence(prefix: File, home: File, pkgs: Map<String, DpkgStatus.Package>): ToolPresence {
        val shim = File(prefix, SHIM_RELATIVE)
        if (!shim.isDirectory) return ToolPresence.Absent
        val versions = File(prefix, VERSIONS_RELATIVE).listFiles()?.filter { File(it, "source.properties").isFile }?.map { it.name }.orEmpty()
        val folders = File(shim, TOOLCHAIN_RELATIVE).isFile && servedVersions(versions).isNotEmpty()
        return ToolPresence.of(
            true,
            ToolPresence.missingPackages(pkgs, PACKAGES) + listOfNotNull(
                "ndk-build".takeUnless { File(shim, "ndk-build").isFile },
                "NDK folders".takeUnless { folders },
                "Gradle setup".takeUnless { File(home, INIT_SCRIPT_RELATIVE).isFile },
            ),
        )
    }
}

/**
 * The optional Android NDK tool (decisions 50 and 96), on top of the Android tool and C and C++. Settings → Tools
 * installs and removes it through the setup script; installing brings in C and C++ when any of it is missing.
 */
object AndroidNdk {
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val tool = ScriptTool(AndroidNdkTool.ID, ::presence, afterRun = { Cpp.refresh() })

    val state: StateFlow<ScriptToolState> = tool.state

    /** What is on the phone now (the shim, dpkg, Gradle's init script), whoever put it there. Blocking: small reads. */
    fun presence(): ToolPresence = AndroidNdkTool.presence(
        File(prefix), File(TermuxConstants.TERMUX_HOME_DIR_PATH), DpkgStatus.read(AndroidNdkTool.PACKAGES.map { it.first }.toSet()),
    )

    /** Reads [presence] off the main thread. */
    fun refresh() {
        tool.refresh()
    }

    fun install(context: Context) {
        tool.install(context)
    }

    fun remove(context: Context) {
        tool.remove(context)
    }
}
