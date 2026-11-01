package com.pocketrocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.mock
import com.pocketrocket.ui.components.mockText

/** One credit on the About screen: what it is and its licence. */
internal data class Credit(val name: String, val licence: String)

/** The licences and icon credits, kept in step with `THIRD_PARTY.md` and `docs/third-party-icons.md`. */
internal object AboutCredits {
    const val SOURCE = "https://github.com/dockito-ai/pocketrocket"

    val code = listOf(
        Credit("Termux (app, termux-shared)", "GPLv3; termux-shared MIT with GPLv3 and Apache 2.0 parts"),
        Credit("terminal-emulator, terminal-view (Termux)", "Apache 2.0"),
        Credit("claude-code-android, parts of the setup scripts", "MIT, © 2026 ferrumclaudepilgrim"),
        Credit("sherpa-onnx Kotlin API (Xiaomi Corporation)", "Apache 2.0"),
        Credit("Inter font", "SIL Open Font License 1.1"),
        Credit("JetBrains Mono font", "SIL Open Font License 1.1"),
    )

    val libraries = listOf(
        Credit("AndroidX, Jetpack Compose, Material Components", "Apache 2.0"),
        Credit("Guava, kotlinx-coroutines, Apache Commons Compress and IO", "Apache 2.0"),
        Credit("Markwon, hiddenapibypass", "Apache 2.0"),
        Credit("termux-am-library", "MIT"),
    )

    val downloaded = listOf(
        Credit("Claude Code", "Anthropic's terms; downloaded from Anthropic, never bundled"),
        Credit("workerd (for Wrangler)", "Apache 2.0, © Cloudflare, Inc."),
        Credit("sherpa-onnx native library and keyword model", "Apache 2.0"),
        Credit("Termux packages, git, gh, Node.js and other tools", "Their own licences"),
        Credit("Dockito CLI", "Dockito's terms"),
        Credit("ndk-build (Android NDK add-on)", "Apache 2.0, © The Android Open Source Project; fetched from AOSP"),
    )

    val icons = listOf(
        Credit("Claude Code, Node.js, Headless Chrome, Wrangler, Flutter & Dart, Python, Bun, Go, Vercel, Netlify, Firebase, flyctl, C and C++", "Simple Icons 16.36.0, CC0 1.0"),
        Credit("AWS CLI (a cloud, not AWS's mark)", "Material Icons, Apache 2.0 (Google)"),
        Credit("Deno", "Simple Icons, MIT"),
        Credit("Java and Kotlin (OpenJDK)", "Simple Icons, BSD 3-Clause"),
        Credit("PHP", "Simple Icons, CC BY-SA 4.0"),
        Credit("Ruby", "Simple Icons, CC BY-SA 2.5"),
        Credit("Git", "Simple Icons, CC BY 3.0 (Jason Long)"),
        Credit("Android, Android NDK", "Simple Icons, CC BY 3.0 (Google)"),
        Credit("Rust", "Simple Icons, CC BY-SA 4.0 (Rust Foundation)"),
        Credit("Dockito", "Dockito's own mark, used with permission"),
    )
}

/** Settings › About (decision 88): version, that it's open source with a link, and the third-party licences and icon credits. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = mock
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        SettingsTopBar("About", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Section("POCKETROCKET") {
                SettingsCard(
                    tile = { IconTile(MockIcons.Rocket, accent = true) },
                    title = "PocketRocket $version".trim(),
                    line = "Open source under the GNU GPL v3",
                    trailing = "",
                )
                SettingsCard(
                    tile = { IconTile(MockIcons.Code) },
                    title = "Source code",
                    line = AboutCredits.SOURCE.removePrefix("https://"),
                    onClick = { openInTab(context, AboutCredits.SOURCE, c.bg) },
                )
            }
            Credits("IN THE APP", AboutCredits.code)
            Credits("LIBRARIES", AboutCredits.libraries)
            Credits("DOWNLOADED ON THE PHONE", AboutCredits.downloaded)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Credits("TOOL ICONS", AboutCredits.icons)
                Text(
                    "The logos are trademarks of their owners and only name the tool they stand for; no endorsement or affiliation is implied.",
                    style = mockText(12.sp, color = c.muted),
                )
            }
        }
    }
}

@Composable
private fun Credits(title: String, credits: List<Credit>) {
    val c = mock
    Section(title) {
        credits.forEach { credit ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(credit.name, style = mockText(13.sp, FontWeight.SemiBold, c.text))
                Text(credit.licence, style = mockText(12.sp, color = c.muted))
            }
        }
    }
}
