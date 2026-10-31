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
import com.pocketrocket.ui.components.OutlineButton

private val LIMITS = listOf(
    "arm64 only. Other ABIs stop the build, and a project with no abiFilters quietly builds arm64 alone.",
    "The C++ library must be shared: -DANDROID_STL=c++_shared. It is the default here; a project that sets c++_static has to change it.",
    "ndk-build and Android.mk do not work. CMake only.",
    "Every NDK version here is the same Termux compiler. A pinned ndkVersion on the list is accepted, but you do not get that real NDK. A version not on the list stops the build.",
    "Linking uses this phone's system libraries, so check your app on older phones.",
    "Build Play releases with Google's real NDK, off the phone.",
)

/** Settings → Tools → Android NDK (decision 50): what it does, its limits and sizes, and Install, Uninstall or Retry. Without the Android tool it offers no install. */
@Composable
fun AndroidNdkToolPage(onBack: () -> Unit, onOpenAndroid: () -> Unit) {
    val context = LocalContext.current
    val s by AndroidNdk.state.collectAsState()
    val android by AndroidBuild.state.collectAsState()
    LaunchedEffect(Unit) {
        AndroidBuild.refresh()
        AndroidNdk.refresh()
    }
    val state = ToolStates.ndk(s, android.installed)
    val failed = state.job == ToolJob.InstallFailed || state.job == ToolJob.UninstallFailed

    ToolPage(
        spec = Tool.AndroidNdk.spec,
        state = state,
        icon = Tool.AndroidNdk.icon,
        purpose = "C and C++ for your Android apps, arm64.",
        onBack = onBack,
        install = ToolInstall(
            job = s.job, outcome = s.outcome, removing = s.removing,
            installSteps = AndroidNdkTool.INSTALL_STEPS, removeSteps = AndroidNdkTool.REMOVE_STEPS,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            onInstall = { AndroidNdk.install(context) },
            onRetry = { if (s.removing) AndroidNdk.remove(context) else AndroidNdk.install(context) },
            onUninstall = { AndroidNdk.remove(context) },
            confirmTitle = "Uninstall the Android NDK?",
            confirmBody = "Clang, CMake, Ninja and the NDK folders are removed. The Android tool and your projects stay.",
            blocked = when {
                android.running -> ({ BusyButton("Android tool is busy…") })
                !android.installed && !s.installed && !failed -> ({ OutlineButton("Go to the Android tool", onOpenAndroid, Modifier.fillMaxWidth()) })
                else -> null
            },
        ),
    ) {
        if (s.installed) {
            ToolCard("HOW TO BUILD") {
                ToolText("Add externalNativeBuild with CMake to your app and run gradle assembleDebug as usual. The NDK folder and CMake are already where Gradle looks. New terminals and sessions pick them up.")
            }
            ToolCard("LIMITS") { ToolBullets(LIMITS) }
            ToolDetails(label = "DETAILS", rows = listOf("Accepted NDK versions" to AndroidNdk.servedVersions().joinToString("\n")))
        } else {
            if (!android.installed) {
                ToolCard("NEEDS THE ANDROID TOOL") {
                    ToolText("This adds C and C++ to the Android tool, so install that first.")
                }
            }
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "Builds the C and C++ code in your Android apps, through CMake and Gradle's usual externalNativeBuild.",
                        "Works in terminals and Claude sessions.",
                        "It may not work on every project. It suits your own arm64 apps and trying things.",
                    ),
                )
            }
            ToolCard("WHAT IT IS") {
                ToolText("Termux's clang, CMake and Ninja, behind a folder laid out as the NDK. It is not Google's NDK: Google publishes none for arm64 phones.")
            }
            ToolCard("LIMITS") { ToolBullets(LIMITS) }
        }
    }
}
