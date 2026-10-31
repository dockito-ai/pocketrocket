package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.pkgtools.PackageTools
import com.pocketrocket.pkgtools.PackageToolsState
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates

/** Settings → Tools → Python (decision 92): Termux's python with pip and uv. */
@Composable
fun PythonToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Python,
    pkg = PackageTool.Python,
    component = UpdateComponent.Python,
    purpose = "Run Python, with pip and uv.",
    onBack = onBack,
    removeNote = "uv's PocketRocket settings go too. Your projects and their venvs stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(
            listOf(
                "python3 and pip in terminals and Claude sessions.",
                "uv for venvs and installs. It uses this Python and never downloads its own.",
            ),
        )
    }
    ToolCard("WHAT IT IS") {
        ToolText(
            "Termux's python package, updated from Settings › Updates. Packages with native parts and no Android wheel " +
                "build from source and need clang (the Android NDK tool has it); some won't build on the phone.",
        )
    }
}

/** Settings → Tools → Bun (decision 92): Termux's bun package. */
@Composable
fun BunToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Bun,
    pkg = PackageTool.Bun,
    component = UpdateComponent.Bun,
    purpose = "Run JavaScript and TypeScript with Bun.",
    onBack = onBack,
    removeNote = "Your projects and their node_modules stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(listOf("bun run, bun install and bun test in your projects.", "Works in terminals and Claude sessions."))
    }
    ToolCard("WHAT IT IS") {
        ToolText("Termux's bun package, updated from Settings › Updates. Node.js and npm stay as they are. Packages that ship only Linux (glibc) binaries may not run.")
    }
}

/**
 * A package tool's page (decision 92): the shared [ToolPage] with Install or Uninstall through [PackageTools], the
 * missing pieces when the main package came some other way (Python from Headless Chrome, without uv), and an
 * uninstall that says what stays because another tool needs it.
 */
@Composable
private fun PackageToolPage(
    tool: Tool,
    pkg: PackageTool,
    component: UpdateComponent,
    purpose: String,
    onBack: () -> Unit,
    removeNote: String,
    about: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val s by PackageTools.state.collectAsState()
    val updates by Updates.state.collectAsState()
    LaunchedEffect(Unit) { PackageTools.refresh() }
    val mine = s.isFor(pkg)
    val missing = pkg.missing(s.packages)
    val held = pkg.held(s.packages)
    val nothingToRemove = s.installed(pkg) && pkg.removable(s.packages).isEmpty()

    ToolPage(
        spec = tool.spec,
        state = ToolStates.packageTool(pkg, s, updates.rows.firstOrNull { it.component == component }),
        icon = tool.icon,
        purpose = purpose,
        onBack = onBack,
        install = ToolInstall(
            job = if (mine) s.job else null, outcome = s.outcome, removing = s.removing,
            installSteps = pkg.installSteps, removeSteps = pkg.removeSteps,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            onInstall = { PackageTools.install(context, pkg) },
            onRetry = { PackageTools.retry(context) },
            onUninstall = { PackageTools.remove(context, pkg) },
            confirmTitle = "Uninstall ${tool.spec.name}?",
            confirmBody = pkg.removeLine(s.packages, removeNote),
            blocked = when {
                s.running && !mine -> ({ BusyButton("Another install is running…") })
                !s.running && nothingToRemove && held != null && !(mine && s.failed) ->
                    ({ Note("${held.name} needs ${PackageTool.names(pkg.kept(s.packages))}, so they stay while it's installed.") })
                else -> null
            },
        ),
        update = toolUpdate(context, updates, component),
    ) {
        if (missing.isNotEmpty() && !s.running) {
            ToolCard("NOT HERE YET") {
                ToolText("${PackageTool.names(missing)} ${if (missing.size == 1) "isn't" else "aren't"} on the phone. ${pkg.versions(s.packages).first().first} came with another tool or a terminal.")
                AccentButton("Add ${PackageTool.names(missing)}", onClick = { PackageTools.install(context, pkg) }, height = 44.dp, radius = 12.dp)
            }
        }
        about()
    }
}

/**
 * The wasm target on Rust's page (decision 92): `rust-std-wasm32-unknown-unknown`, added and removed on its own
 * through [PackageTools]. [rustBusy] while a Rust or Flutter job runs (the script takes one job at a time).
 */
@Composable
fun WasmTargetCard(s: PackageToolsState, rustBusy: Boolean) {
    val context = LocalContext.current
    val t = PackageTool.RustWasm
    val mine = s.isFor(t)
    val installed = s.installed(t)
    val job = s.job
    var confirm by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable(s.failed) { mutableStateOf(s.failed) }

    ToolCard("WASM TARGET") {
        ToolText(
            "wasm32-unknown-unknown, for cargo check --target wasm32-unknown-unknown and crates built for the web. " +
                "${t.downloadMb} MB download, ${t.installedMb} MB on the phone. Uninstalling Rust removes it too.",
        )
        if (mine && job != null && (s.running || s.failed)) {
            StepList(job, style = StepListStyle.Compact, upcoming = if (s.removing) t.removeSteps else t.installSteps)
            if (s.failed) s.outcome?.let { ToolText(it) }
            ToolLog(job.log, logOpen) { logOpen = !logOpen }
        }
        when {
            mine && s.running -> BusyButton(if (s.removing) "Removing…" else "Adding…")
            s.running || rustBusy -> BusyButton("Waiting for another install…")
            mine && s.failed -> AccentButton("Retry", onClick = { PackageTools.retry(context) }, height = 44.dp, radius = 12.dp)
            installed -> DangerButton("Remove the wasm target", onClick = { confirm = true })
            else -> AccentButton("Add the wasm target", onClick = { PackageTools.install(context, t) }, height = 44.dp, radius = 12.dp, enabled = s.checked)
        }
    }

    if (confirm) {
        ConfirmSheet(
            title = "Remove the wasm target?",
            body = "${t.installedMb} MB is freed. Rust stays.",
            confirm = "Remove",
            onConfirm = {
                confirm = false
                PackageTools.remove(context, t)
            },
            onDismiss = { confirm = false },
        )
    }
}
