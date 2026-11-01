package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.androidbuild.AndroidBuild
import com.pocketrocket.androidbuild.AndroidNdk
import com.pocketrocket.androidbuild.AndroidNdkTool
import com.pocketrocket.cpp.Cpp
import com.pocketrocket.ui.components.OutlineButton

private val LIMITS = listOf(
    "Builds arm64-v8a, armeabi-v7a, x86 and x86_64, but only arm64 runs on this phone: test the others on another device or an emulator. riscv64 is dropped with a warning.",
    "Every ndkVersion builds with the same Termux compiler, which moves with pkg upgrade. A pinned version is accepted; you do not get that real NDK.",
    "Linking uses stubs of this phone's system libraries, so a symbol newer than your minSdk links without an error. Check your app on older phones.",
    "Build Play releases with Google's real NDK, off the phone.",
)

/**
 * Settings → Tools → Android NDK (decisions 50 and 96): what it does, its limits and sizes, and Install, Uninstall or
 * Retry. Without the Android tool it offers no install; without C and C++ the install brings it in.
 */
@Composable
fun AndroidNdkToolPage(onBack: () -> Unit, onOpenAndroid: () -> Unit, onOpenCpp: () -> Unit) {
    val context = LocalContext.current
    val s by AndroidNdk.state.collectAsState()
    val android by AndroidBuild.state.collectAsState()
    val cpp by Cpp.state.collectAsState()
    LaunchedEffect(Unit) {
        AndroidBuild.refresh()
        AndroidNdk.refresh()
        Cpp.refresh()
    }
    val state = ToolStates.ndk(s, android.installed)
    val failed = state.job == ToolJob.InstallFailed || state.job == ToolJob.UninstallFailed
    val spec = Tool.AndroidNdk.spec.copy(downloadMb = AndroidNdkTool.downloadMb(cpp.installed), installedMb = AndroidNdkTool.installedMb(cpp.installed))

    ToolPage(
        spec = spec,
        state = state,
        icon = Tool.AndroidNdk.icon,
        purpose = "C and C++ for your Android apps.",
        onBack = onBack,
        install = ToolInstall(
            job = s.job, outcome = s.outcome, removing = s.removing,
            installSteps = AndroidNdkTool.installSteps(cppComplete = cpp.installed && cpp.problem == null), removeSteps = AndroidNdkTool.REMOVE_STEPS,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            onInstall = { AndroidNdk.install(context) },
            onRetry = { if (s.removing) AndroidNdk.remove(context) else AndroidNdk.install(context) },
            onUninstall = { AndroidNdk.remove(context) },
            confirmTitle = "Uninstall the Android NDK?",
            confirmBody = "The NDK folders are removed. C and C++, the Android tool and your projects stay; native builds then stop with a message, and Kotlin and Java still build.",
            blocked = when {
                android.running -> ({ BusyButton("Android tool is busy…") })
                cpp.running -> ({ BusyButton("C and C++ is busy…") })
                !android.installed && !s.installed && !failed -> ({ OutlineButton("Go to the Android tool", onOpenAndroid, Modifier.fillMaxWidth()) })
                else -> null
            },
        ),
    ) {
        if (s.installed) {
            if (s.problem?.contains("ndk-build") == true && !s.running) {
                ToolCard("REPAIR") {
                    ToolText("This NDK was set up before ndk-build, the other ABIs and c++_static were added. Repair brings them in, and C and C++ with them.")
                }
            }
            ToolCard("HOW TO BUILD") {
                ToolText("Add externalNativeBuild with CMake or ndk-build to your app and run gradle assembleDebug as usual. The NDK folder and CMake are already where Gradle looks. New terminals and sessions pick them up.")
            }
            ToolCard("LIMITS") { ToolBullets(LIMITS) }
            ToolDetails(
                label = "DETAILS",
                rows = listOf(
                    "Claims NDK" to AndroidNdkTool.REVISION,
                    "ABIs" to "arm64-v8a, armeabi-v7a, x86, x86_64",
                    "C++ library" to "c++_static (default) or c++_shared",
                ),
            )
        } else {
            if (!android.installed) {
                ToolCard("NEEDS THE ANDROID TOOL") {
                    ToolText("This adds C and C++ to the Android tool, so install that first.")
                }
            } else if (!cpp.installed) {
                ToolCard("NEEDS C AND C++") {
                    ToolText("The NDK builds on the C and C++ tool. Installing the NDK installs it too; the sizes here include it.")
                    OutlineButton("Open C and C++", onOpenCpp, Modifier.fillMaxWidth())
                }
            }
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "Builds the C and C++ code in your Android apps, through CMake or ndk-build and Gradle's usual externalNativeBuild.",
                        "Works in terminals and Claude sessions.",
                        "It may not work on every project. It suits your own apps and trying things.",
                    ),
                )
            }
            ToolCard("WHAT IT IS") {
                ToolText("Termux's clang behind a folder laid out as the NDK, with Google's ndk-build scripts from AOSP and Termux's libraries for four ABIs. It is not Google's NDK: Google publishes none for arm64 phones.")
            }
            ToolCard("LIMITS") { ToolBullets(LIMITS) }
        }
    }
}
