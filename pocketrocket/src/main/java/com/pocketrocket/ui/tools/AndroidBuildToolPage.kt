package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.androidbuild.AndroidBuild
import com.pocketrocket.androidbuild.AndroidBuildTool
import com.pocketrocket.androidbuild.AndroidNdk
import com.pocketrocket.androidbuild.SdkLicense
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val EXTRA_ROWS = listOf(
    "First build" to "Downloads about ${AndroidBuildTool.FIRST_BUILD_DOWNLOAD_MB} MB more, ${AndroidBuildTool.FIRST_BUILD_DISK_MB} MB on the phone",
    "Memory" to "About ${AndroidBuildTool.BUILD_MEMORY_GB} GB while building",
)

private const val CAPACITOR = "Svelte, React or Vue apps wrapped with Capacitor build here too: npm run build, npx cap sync android, then ./gradlew assembleDebug in the android folder. Capacitor needs Java 21, so install Java and Kotlin in Tools; Gradle picks it up for those projects by itself."

private const val NO_NDK = "Kotlin and Java apps. For native code (C or C++), add the Android NDK in Tools; until then a native project's C and C++ tasks stop with a message, and its Kotlin, Java and unit tests still build. There is no emulator on the phone."

/** Settings → Tools → Android (decision 45): what it does, its sizes, the SDK license, and Install, Uninstall or Retry. */
@Composable
fun AndroidBuildToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by AndroidBuild.state.collectAsState()
    val ndk by AndroidNdk.state.collectAsState()
    var license by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AndroidBuild.refresh()
        AndroidNdk.refresh()
    }

    ToolPage(
        spec = Tool.Android.spec,
        state = ToolStates.scriptTool(s),
        icon = Tool.Android.icon,
        purpose = "Android development environment on this device.",
        onBack = onBack,
        install = ToolInstall(
            job = s.job, outcome = s.outcome, removing = s.removing,
            installSteps = AndroidBuildTool.INSTALL_STEPS, removeSteps = AndroidBuildTool.REMOVE_STEPS,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            // Accepting the license is the only way an install starts; a retried install was already accepted.
            onInstall = { license = true },
            onRetry = { if (s.removing) AndroidBuild.remove(context) else AndroidBuild.install(context) },
            onUninstall = { AndroidBuild.remove(context) },
            confirmTitle = "Uninstall the Android tools?",
            confirmBody = "Java, Gradle, the SDK and everything Gradle downloaded are removed." +
                (if (ndk.installed) " The Android NDK needs them, so it goes too; C and C++ stays." else "") + " Your projects stay.",
            blocked = if (ndk.running) ({ BusyButton("Android NDK tool is busy…") }) else null,
        ),
        storageRows = EXTRA_ROWS,
    ) {
        if (s.installed) {
            ToolCard("HOW TO BUILD") {
                ToolText("In a terminal or a Claude session, run gradle assembleDebug or ./gradlew assembleDebug in your project. JAVA_HOME, ANDROID_HOME and the aapt2 setting are already there. New terminals and sessions pick them up.")
            }
            ToolCard("CAPACITOR APPS") { ToolText(CAPACITOR) }
            ToolCard("BUILDS TAKE TURNS") {
                ToolText("So the phone keeps enough memory, only a few builds run at once, from any session or terminal; the rest wait their turn. Gradle gets a heap sized for this phone and compiles Kotlin in its own process. The session's Background sheet shows what is building and waiting.")
            }
            ToolDetails(label = "DETAILS", rows = listOf("SDK" to "Android 17 (API 37), build tools 36"))
            ToolCard("NOT INCLUDED") { ToolText(NO_NDK) }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "Provides an Android development environment on this device.",
                        "Builds Kotlin and Compose apps.",
                        "Builds Capacitor apps (Svelte, React, Vue) with Java and Kotlin installed.",
                        "Works in terminals and Claude sessions.",
                        "Needs no Developer options.",
                    ),
                )
            }
            ToolCard("WHAT IT IS") {
                ToolText("Java 17, Gradle and arm64 build tools from Termux's packages, plus the Android SDK from Google. You accept Google's SDK license before anything downloads.")
            }
            ToolCard("NOT INCLUDED") { ToolText(NO_NDK) }
        }
    }

    if (license) {
        SdkLicenseSheet(
            onAccept = {
                license = false
                AndroidBuild.install(context)
            },
            onDismiss = { license = false },
        )
    }
}

/** Google's license, read live, with Accept and Decline. Accepting is the only way an install starts. */
@Composable
private fun SdkLicenseSheet(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    var attempt by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        error = null
        withContext(Dispatchers.IO) { runCatching { SdkLicense.fetch() } }
            .onSuccess { text = it }
            .onFailure { error = "Could not load the license from Google. Check the connection." }
    }
    ToolSheet(onDismiss) {
        Text("Android SDK license", style = mockText(17.sp, FontWeight.Bold), color = c.text)
        Text("From Google. Installing needs your agreement to it.", style = mockText(13.sp).copy(lineHeight = 19.sp), color = c.textMuted)
        val body = text
        when {
            body != null -> Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(body, style = mockText(12.sp).copy(lineHeight = 18.sp), color = c.textSoft)
            }
            error != null -> Text(error.orEmpty(), style = mockText(13.sp), color = c.redSoft)
            else -> Text("Loading…", style = mockText(13.sp), color = c.textMuted)
        }
        when {
            body != null -> AccentButton("Accept and install", onClick = onAccept, height = 48.dp, radius = 12.dp)
            error != null -> AccentButton("Try again", onClick = { attempt++ }, height = 48.dp, radius = 12.dp)
        }
        OutlineButton("Decline", onDismiss, Modifier.fillMaxWidth())
    }
}
