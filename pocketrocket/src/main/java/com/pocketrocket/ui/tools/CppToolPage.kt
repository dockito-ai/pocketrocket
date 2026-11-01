package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.androidbuild.AndroidNdk
import com.pocketrocket.cpp.Cpp
import com.pocketrocket.cpp.CppTool
import com.pocketrocket.ui.components.OutlineButton

/**
 * Settings → Tools → C and C++ (decision 96): Termux's compilers and build tools for any C or C++ project, with
 * Install or Uninstall. The Android NDK builds on it, so uninstalling it says the NDK goes too.
 */
@Composable
fun CppToolPage(onBack: () -> Unit, onOpenNdk: () -> Unit) {
    val context = LocalContext.current
    val s by Cpp.state.collectAsState()
    val ndk by AndroidNdk.state.collectAsState()
    LaunchedEffect(Unit) {
        Cpp.refresh()
        AndroidNdk.refresh()
    }

    ToolPage(
        spec = Tool.Cpp.spec,
        state = ToolStates.scriptTool(s),
        icon = Tool.Cpp.icon,
        purpose = "Build C and C++ projects on this device.",
        onBack = onBack,
        install = ToolInstall(
            job = s.job, outcome = s.outcome, removing = s.removing,
            installSteps = CppTool.INSTALL_STEPS, removeSteps = CppTool.removeSteps(ndk.installed),
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            onInstall = { Cpp.install(context) },
            onRetry = { if (s.removing) Cpp.remove(context) else Cpp.install(context) },
            onUninstall = { Cpp.remove(context) },
            confirmTitle = "Uninstall C and C++?",
            confirmBody = "The compilers and build tools this tool installed are removed." +
                (if (ndk.installed) " The Android NDK needs them, so it goes too." else "") + " Your projects and their build folders stay.",
            blocked = if (ndk.running) ({ BusyButton("Android NDK is busy…") }) else null,
        ),
        storageRows = listOf("Packages" to CppTool.PACKAGES.joinToString(", ")),
    ) {
        if (s.installed) {
            ToolCard("HOW TO BUILD") {
                ToolText("In a terminal or a Claude session: cmake -B build -G Ninja && cmake --build build, or make. cc, c++, clang and clang++ are on the PATH. Debug with lldb.")
            }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "Compiles and runs C and C++ programs and libraries on the phone.",
                        "CMake, Ninja, make and pkg-config for the build; lldb to debug.",
                        "Works in terminals and Claude sessions.",
                    ),
                )
            }
            ToolCard("WHAT IT IS") {
                ToolText("Termux's packages: clang and LLVM, lld, CMake, Ninja, make, pkg-config and lldb. One version, moved by pkg upgrade. Programs it builds run on this phone (arm64 Android).")
            }
        }
        ToolCard("ANDROID APPS") {
            ToolText("For the C and C++ in Android apps, add the Android NDK. It builds on this tool and installs it if it isn't here.")
            OutlineButton("Open Android NDK", onOpenNdk, Modifier.fillMaxWidth())
        }
    }
}
