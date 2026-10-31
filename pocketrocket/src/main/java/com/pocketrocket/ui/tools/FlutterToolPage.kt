package com.pocketrocket.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.fluttertool.FlutterAction
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.fluttertool.FlutterRustState
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.fluttertool.FlutterVersion
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.updates.AptPolicy
import com.pocketrocket.updates.RustUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FOR_CHECKING =
    "For checking code: pub get, build_runner, analyze, test, cargo check and clippy. No APKs on the phone; release builds need a Mac or CI."

private const val RUST_PIN =
    "Rust is Termux's build, one version, updated from Settings › Updates. A project's rust-toolchain.toml is not followed: checks use this stable."

/** What the confirm sheet is asking about. */
private sealed interface Ask {
    data class RemoveVersion(val version: String) : Ask
    data object RemoveRust : Ask
}

/**
 * Settings → Tools → Flutter & Rust (decision 74): what it is for, the Flutter versions (sizes, default, Add version,
 * Set default, Remove), Rust (version, Install or Remove), sizes, and Install or Uninstall all. Adding a version,
 * setting the default and Rust run inside their cards; installing or uninstalling everything is the page's job.
 */
@Composable
fun FlutterToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by FlutterRust.state.collectAsState()
    val rust by RustUpdater.state.collectAsState()
    var picker by rememberSaveable { mutableStateOf(false) }
    var ask by androidx.compose.runtime.remember { mutableStateOf<Ask?>(null) }
    LaunchedEffect(Unit) {
        RustUpdater.load(context)
        withContext(Dispatchers.IO) { FlutterRust.measure() }
    }
    LaunchedEffect(s.running) { if (!s.running) RustUpdater.load(context) }

    val whole = s.action == FlutterAction.InstallAll || s.action == FlutterAction.UninstallAll
    ToolPage(
        spec = Tool.Flutter.spec,
        state = ToolStates.flutter(s),
        icon = Tool.Flutter.icon,
        purpose = "Check Flutter, Dart and Rust code for errors.",
        onBack = onBack,
        install = ToolInstall(
            job = if (whole) s.job else null, outcome = s.outcome, removing = s.removing,
            installSteps = s.steps.ifEmpty { FlutterTool.installAllSteps(s.latestStable) }, removeSteps = s.steps,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            onInstall = { FlutterRust.installAll(context) },
            onRetry = { FlutterRust.retry(context) },
            onUninstall = { FlutterRust.uninstallAll(context) },
            confirmTitle = "Uninstall Flutter and Rust?",
            confirmBody = "Every Flutter version, FVM, Dart's and pub's caches (~/.pub-cache) and Rust with ~/.cargo are removed. Your projects stay.",
            blocked = if (s.running && !whole) ({ BusyButton("Working…") }) else null,
        ),
        storageRows = listOf(
            "Flutter, each version" to "About ${FlutterTool.sizeMb(FlutterTool.FLUTTER_INSTALLED_MB)}",
            "Rust" to FlutterTool.sizeMb(FlutterTool.RUST_INSTALLED_MB),
            "Packages per project" to "~/.pub-cache, ~/.cargo, target/",
        ),
    ) {
        if (s.installed) {
            ToolCard("FLUTTER") { FlutterVersions(s, onAdd = { picker = true }, onAsk = { ask = it }) }
            ToolCard("RUST") { RustRow(s, AptPolicy.display(rust.policy?.installed), onRemove = { ask = Ask.RemoveRust }) }
            ToolCard("HOW TO USE") {
                ToolText(
                    "In a terminal or a Claude session, run flutter or dart in your project: the version in its .fvmrc is used, " +
                        "else the default. fvm list and fvm use work too. Run cargo check or cargo clippy in a Rust crate. " +
                        "New terminals and sessions already have them.",
                )
            }
            ToolCard("FOR CHECKING CODE") { ToolText(FOR_CHECKING) }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "flutter pub get, dart run build_runner build, flutter analyze and flutter test.",
                        "cargo check and cargo clippy, Flutter plugins with Rust included.",
                        "Uses the Flutter version a project pins in .fvmrc, as FVM does.",
                        "Works in terminals and Claude sessions.",
                    ),
                )
            }
            ToolCard("FOR CHECKING CODE") { ToolText(FOR_CHECKING) }
            ToolCard("WHAT IT IS") {
                ToolText(
                    "Official Flutter from GitHub with its Dart SDK from Google, in FVM's layout, and FVM ${FlutterTool.FVM_VERSION}. " +
                        "Install adds the newest stable Flutter as the default, and Rust. $RUST_PIN",
                )
            }
        }
    }

    if (picker) {
        VersionPicker(s, onPick = { v ->
            picker = false
            FlutterRust.addVersion(context, v)
        }, onDismiss = { picker = false })
    }
    when (val a = ask) {
        is Ask.RemoveVersion -> ConfirmSheet(
            title = "Remove Flutter ${a.version}?",
            body = "Its folder goes. Projects that pin ${a.version} in .fvmrc stop working until you add it again." +
                (if (a.version == s.defaultVersion) " The newest other version becomes the default." else ""),
            confirm = "Remove",
            onConfirm = { ask = null; FlutterRust.removeVersion(context, a.version) },
            onDismiss = { ask = null },
        )
        Ask.RemoveRust -> ConfirmSheet(
            title = "Remove Rust?",
            body = "The rust packages this tool installed and ~/.cargo are removed. Your crates and their target folders stay.",
            confirm = "Remove",
            onConfirm = { ask = null; FlutterRust.removeRust(context) },
            onDismiss = { ask = null },
        )
        null -> Unit
    }
}

/** The installed versions with size and default, the running version job, and Add version. */
@Composable
private fun FlutterVersions(s: FlutterRustState, onAdd: () -> Unit, onAsk: (Ask) -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val versionJob = s.action in setOf(FlutterAction.AddVersion, FlutterAction.SetDefault, FlutterAction.RemoveVersion)
    if (!s.flutterInstalled) {
        ToolText("Flutter is not installed.")
    } else if (s.versions.isEmpty()) {
        ToolText("No Flutter version yet. Add one.")
    }
    s.versions.forEach { v ->
        VersionRow(v, expanded = open == v.version && !s.running, onClick = { open = if (open == v.version) null else v.version })
        if (open == v.version && !s.running) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!v.isDefault) OutlineButton("Set default", { open = null; FlutterRust.setDefault(context, v.version) }, Modifier.weight(1f))
                OutlineButton("Remove", { open = null; onAsk(Ask.RemoveVersion(v.version)) }, Modifier.weight(1f), color = PrTheme.colors.redSoft, border = PrTheme.colors.redBorder)
            }
        }
    }
    if (versionJob) JobBlock(s)
    when {
        s.running -> Unit
        else -> OutlineButton(if (s.flutterInstalled) "Add version" else "Install Flutter", onAdd, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VersionRow(v: FlutterVersion, expanded: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(v.version, style = mockText(14.sp, mono = true), color = c.text)
        if (v.isDefault) {
            Text(
                "Default", style = mockText(11.sp, FontWeight.SemiBold), color = c.green,
                modifier = Modifier.background(c.green.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Box(Modifier.weight(1f))
        Text(v.bytes?.let(FlutterTool::size) ?: "Measuring…", style = mockText(13.sp), color = c.textMuted)
        Text(if (expanded) "⌄" else "›", style = mockText(14.sp), color = c.textMuted)
    }
}

/** Rust's version and its button, with the running Rust job. */
@Composable
private fun RustRow(s: FlutterRustState, version: String?, onRemove: () -> Unit) {
    val context = LocalContext.current
    ToolDetails(listOf("Rust" to if (s.rustInstalled) version ?: "Installed" else "Not installed"), monoValues = setOf("Rust"))
    ToolText(RUST_PIN)
    if (s.action == FlutterAction.InstallRust || s.action == FlutterAction.RemoveRust) JobBlock(s)
    when {
        s.running -> Unit
        s.rustInstalled -> OutlineButton("Remove Rust", onRemove, Modifier.fillMaxWidth(), color = PrTheme.colors.redSoft, border = PrTheme.colors.redBorder)
        else -> OutlineButton("Install Rust · ${FlutterTool.RUST_DOWNLOAD_MB} MB download", { FlutterRust.installRust(context) }, Modifier.fillMaxWidth())
    }
}

/** A job running inside a card: its steps, what failed, the log and Retry. */
@Composable
private fun JobBlock(s: FlutterRustState) {
    val context = LocalContext.current
    val job = s.job ?: return
    var logOpen by rememberSaveable(s.failed) { mutableStateOf(s.failed) }
    if (!s.running && !s.failed) return
    StepList(job, style = StepListStyle.Compact, upcoming = s.steps)
    if (s.failed) s.outcome?.let { Text(it, style = mockText(12.sp), color = PrTheme.colors.redSoft) }
    ToolLog(job.log, logOpen) { logOpen = !logOpen }
    if (s.failed) AccentButton("Retry", onClick = { FlutterRust.retry(context) }, height = 44.dp, radius = 12.dp)
}

/** Add version: recent stable releases from Flutter's list, newest first; the ones on the phone are left out. */
@Composable
private fun VersionPicker(s: FlutterRustState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    var attempt by rememberSaveable { mutableStateOf(0) }
    var loading by rememberSaveable { mutableStateOf(s.releases.isEmpty()) }
    LaunchedEffect(attempt) {
        loading = true
        withContext(Dispatchers.IO) { FlutterRust.loadReleases() }
        loading = false
    }
    val have = s.versions.map { it.version }.toSet()
    ToolSheet(onDismiss) {
        Text("Add a Flutter version", style = mockText(17.sp, FontWeight.Bold), color = c.text)
        Text(
            "Stable releases, about ${FlutterTool.sizeMb(FlutterTool.FLUTTER_INSTALLED_MB)} each. For another version, run fvm install <version> in a terminal.",
            style = mockText(13.sp).copy(lineHeight = 19.sp), color = c.textMuted,
        )
        val choices = s.releases.filter { it.version !in have }
        when {
            choices.isNotEmpty() -> Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                choices.forEachIndexed { i, r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(r.version) }.padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.version, style = mockText(14.sp, mono = true), color = c.text, modifier = Modifier.weight(1f))
                        Text(if (i == 0 && r.version == s.latestStable) "Newest · ${r.date}" else r.date, style = mockText(12.sp), color = c.textMuted)
                    }
                    if (i < choices.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
            loading -> Text("Loading Flutter's releases…", style = mockText(13.sp), color = c.textMuted)
            s.releasesFailed -> {
                Text("Could not read Flutter's release list. Check the connection.", style = mockText(13.sp), color = c.redSoft)
                AccentButton("Try again", onClick = { attempt++ }, height = 48.dp, radius = 12.dp)
            }
            else -> Text("Every recent stable is already on the phone.", style = mockText(13.sp), color = c.textMuted)
        }
        OutlineButton("Cancel", onDismiss, Modifier.fillMaxWidth())
    }
}
