package com.pocketrocket.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.fluttertool.FlutterPart
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.pkgtools.PackageTools
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates

/**
 * Settings → Tools → Rust (decisions 75 and 87): Termux's rust package for cargo check and clippy, with its version,
 * Update through Updates, and Install or Uninstall on its own. It shares Flutter's job runner, so it waits while a
 * Flutter job runs. The wasm target is an option on it (decision 92), added and removed on its own.
 */
@Composable
fun RustToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by FlutterRust.state.collectAsState()
    val updates by Updates.state.collectAsState()
    val mine = s.isFor(FlutterPart.Rust)
    val packages by PackageTools.state.collectAsState()
    val wasmBusy = packages.running && packages.isFor(PackageTool.RustWasm)
    // dpkg is read again when a Rust job ends: uninstalling Rust takes the wasm target with it.
    LaunchedEffect(s.running) { PackageTools.refresh() }
    ToolPage(
        spec = Tool.Rust.spec,
        state = ToolStates.rust(s, updates.rows.firstOrNull { it.component == UpdateComponent.Rust }, wasmBusy),
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
            confirmBody = "The rust packages this tool installed, the wasm target and ~/.cargo are removed. Flutter, your crates and their target folders stay.",
            blocked = when {
                s.running && !mine -> ({ BusyButton("Flutter is busy…") })
                packages.running -> ({ BusyButton("Waiting for another install…") })
                else -> null
            },
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
        if (s.rustInstalled) WasmTargetCard(packages, rustBusy = s.running)
    }
}
