package com.pocketrocket.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.fluttertool.FlutterPart
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates

/**
 * Settings → Tools → Rust (decisions 75 and 87): Termux's rust package for cargo check and clippy, with its version,
 * Update through Updates, and Install or Uninstall on its own. It shares Flutter's job runner, so it waits while a
 * Flutter job runs.
 */
@Composable
fun RustToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by FlutterRust.state.collectAsState()
    val updates by Updates.state.collectAsState()
    val mine = s.isFor(FlutterPart.Rust)
    ToolPage(
        spec = Tool.Rust.spec,
        state = ToolStates.rust(s, updates.rows.firstOrNull { it.component == UpdateComponent.Rust }),
        icon = Tool.Rust.icon,
        purpose = "Check Rust code for errors.",
        onBack = onBack,
        install = ToolInstall(
            job = if (mine) s.job else null, outcome = s.outcome, removing = s.removing,
            installSteps = FlutterTool.RUST_STEPS, removeSteps = FlutterTool.RUST_REMOVE_STEPS,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            onInstall = { FlutterRust.installRust(context) },
            onRetry = { FlutterRust.retry(context) },
            onUninstall = { FlutterRust.removeRust(context) },
            confirmTitle = "Uninstall Rust?",
            confirmBody = "The rust packages this tool installed and ~/.cargo are removed. Flutter, your crates and their target folders stay.",
            blocked = if (s.running && !mine) ({ BusyButton("Flutter is busy…") }) else null,
        ),
        update = toolUpdate(context, updates, UpdateComponent.Rust),
        storageRows = listOf("Packages per project" to "~/.cargo, target/"),
    ) {
        ToolCard("WHAT IT DOES") {
            ToolBullets(
                listOf(
                    "cargo check and cargo clippy in your crates.",
                    "Flutter apps with a rust/ crate (flutter_rust_bridge) included.",
                    "Works in terminals and Claude sessions.",
                ),
            )
        }
        ToolCard("WHAT IT IS") {
            ToolText(
                "Termux's rust package: one version, updated from Settings › Updates. A project's rust-toolchain.toml is not followed: " +
                    "checks use this stable. No builds for release on the phone.",
            )
        }
    }
}
