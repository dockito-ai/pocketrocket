package com.pocketrocket.cpp

import android.content.Context
import com.pocketrocket.androidbuild.AndroidNdk
import com.pocketrocket.setup.ScriptTool
import com.pocketrocket.setup.ScriptToolState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.termux.DpkgStatus
import kotlinx.coroutines.flow.StateFlow

/** The fixed facts of the C and C++ tool (decision 96). Sizes are apt's figures for what a fresh phone lacks. */
object CppTool {
    const val ID = "c-cpp"

    const val DOWNLOAD_MB = 105
    const val INSTALLED_MB = 630

    /** Termux's packages (the setup script's pkg_tool_packages c-cpp) and how the tile names them; clang means installed. */
    val PACKAGES = listOf(
        "clang" to "clang", "lld" to "lld", "llvm" to "LLVM", "cmake" to "CMake", "ninja" to "Ninja",
        "make" to "make", "pkg-config" to "pkg-config", "lldb" to "lldb",
    )

    const val INSTALL_STEP = "Installing C and C++"
    val INSTALL_STEPS = listOf("Checking the device", INSTALL_STEP)

    /** Removing C and C++ takes the Android NDK first when it's there, as a step of its own. */
    fun removeSteps(ndkInstalled: Boolean): List<String> =
        listOf("Checking the device") + (if (ndkInstalled) listOf("Removing the Android NDK") else emptyList()) + "Removing C and C++"

    /**
     * The tool as it is now, whoever installed it (decision 93): on the phone while clang is (dpkg), working when every
     * package is ("Installed · lldb missing" otherwise, with Repair).
     */
    fun presence(pkgs: Map<String, DpkgStatus.Package>): ToolPresence =
        ToolPresence.of(PACKAGES.first().first in pkgs, ToolPresence.missingPackages(pkgs, PACKAGES))
}

/**
 * The optional C and C++ tool (decision 96): Termux's clang, LLVM, lld, CMake, Ninja, make, pkg-config and lldb for
 * any C or C++ project. Settings → Tools installs and removes it through the setup script. The Android NDK builds on
 * it, so removing it removes the NDK too. Uninstall may leave packages other packages need (Rust needs clang), so
 * only the script's word judges it.
 */
object Cpp {
    private val tool = ScriptTool(CppTool.ID, ::presence, removalMayKeep = true, afterRun = { AndroidNdk.refresh() })

    val state: StateFlow<ScriptToolState> = tool.state

    /** dpkg's view now. Blocking: one file read. */
    fun presence(): ToolPresence = CppTool.presence(DpkgStatus.read(CppTool.PACKAGES.map { it.first }.toSet()))

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
