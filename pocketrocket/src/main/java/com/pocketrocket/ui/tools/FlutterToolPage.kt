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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.fluttertool.FlutterAction
import com.pocketrocket.fluttertool.FlutterPart
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.fluttertool.FlutterRustState
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.fluttertool.FlutterVersion
import com.pocketrocket.fluttertool.ReleaseBuilds
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FOR_CHECKING =
    "For checking code: pub get, build_runner, analyze and test. Debug APKs build here with Android and C and C++ " +
        "installed (flutter build apk --debug); release APKs too once Release builds is added below."

/**
 * Settings → Tools → Flutter & Dart (decisions 75 and 87): what it is for, its version with Update, the Flutter
 * versions (sizes, default, Add version, Set default, Remove), Release builds (decision 106), a nudge towards Rust when a workspace is a
 * flutter_rust_bridge app, sizes, and Install or Uninstall. Version jobs run inside their card; installing or
 * uninstalling is the page's job. Rust is its own tool and page.
 */
@Composable
fun FlutterToolPage(onBack: () -> Unit, onOpenRust: () -> Unit) {
    val context = LocalContext.current
    val s by FlutterRust.state.collectAsState()
    val updates by Updates.state.collectAsState()
    val workspaces by Workspaces.state.collectAsState()
    var picker by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { FlutterRust.measure() } }
    val bridged by produceState(emptyList<String>(), workspaces, s.rustInstalled) {
        value = if (s.rustInstalled) emptyList() else withContext(Dispatchers.IO) {
            FlutterTool.rustBridgeProjects(workspaces.map { it.dir }).map { dir -> workspaces.first { it.dir == dir }.name }
        }
    }

    val mine = s.isFor(FlutterPart.Flutter)
    val whole = mine && s.action?.whole == true
    ToolPage(
        spec = Tool.Flutter.spec,
        state = ToolStates.flutter(s, updates.rows.firstOrNull { it.component == UpdateComponent.Flutter }),
        icon = Tool.Flutter.icon,
        purpose = "Check Flutter and Dart code for errors.",
        onBack = onBack,
        install = ToolInstall(
            job = if (whole) s.job else null, outcome = s.outcome, removing = s.removing,
            installSteps = if (whole) s.steps else FlutterTool.flutterSteps(s.latestStable), removeSteps = s.steps,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry picks up where it stopped.",
            // Install, or Repair: Release builds' own install when they are all that's missing.
            onInstall = { FlutterRust.repairFlutter(context) },
            onRetry = { FlutterRust.retry(context) },
            onUninstall = { FlutterRust.uninstallFlutter(context) },
            confirmTitle = "Uninstall Flutter & Dart?",
            confirmBody = "Every Flutter version, FVM, Release builds, and Dart's and pub's caches (~/.pub-cache) are removed. Rust and your projects stay.",
            blocked = if (s.running && !whole) ({ BusyButton(if (mine) "Working…" else "Rust is busy…") }) else null,
        ),
        update = toolUpdate(context, updates, UpdateComponent.Flutter),
        storageRows = listOf(
            "Each version" to "About ${FlutterTool.sizeMb(FlutterTool.FLUTTER_INSTALLED_MB)}",
            "Packages per project" to "~/.pub-cache",
        ),
    ) {
        if (s.flutterInstalled) {
            ToolCard("VERSIONS") { FlutterVersions(s, onAdd = { picker = true }, onRemove = { removing = it }) }
            ToolCard("HOW TO USE") {
                ToolText(
                    "In a terminal or a Claude session, run flutter or dart in your project: the version in its .fvmrc is used, " +
                        "else the default. fvm install, fvm use, fvm flutter and fvm dart work too. New terminals and sessions already have them.",
                )
            }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "flutter pub get, dart run build_runner build, flutter analyze and flutter test.",
                        "Uses the Flutter version a project pins in .fvmrc, as FVM does.",
                        "Works in terminals and Claude sessions.",
                    ),
                )
            }
            ToolCard("WHAT IT IS") {
                ToolText(
                    "Official Flutter from GitHub with its Dart SDK from Google, in FVM's layout, and FVM ${FlutterTool.FVM_VERSION}. " +
                        "Install adds the newest stable Flutter as the default.",
                )
            }
        }
        if (bridged.isNotEmpty()) {
            ToolCard("RUST") {
                val names = bridged.joinToString(", ")
                ToolText("$names ${if (bridged.size == 1) "has" else "have"} a rust/ folder, as flutter_rust_bridge apps do. Rust is a separate tool: add it to check that code with cargo.")
                OutlineButton("Open Rust", onOpenRust, Modifier.fillMaxWidth())
            }
        }
        if (s.flutterInstalled) ReleaseBuildsCard(s)
        ToolCard("FOR CHECKING CODE") { ToolText(FOR_CHECKING) }
    }

    if (picker) {
        VersionPicker(s, onPick = { v ->
            picker = false
            FlutterRust.addVersion(context, v)
        }, onDismiss = { picker = false })
    }
    removing?.let { v ->
        ConfirmSheet(
            title = "Remove Flutter $v?",
            body = "Its folder goes. Projects that pin $v in .fvmrc stop working until you add it again." +
                (if (v == s.defaultVersion) " The newest other version becomes the default." else ""),
            confirm = "Remove",
            onConfirm = { removing = null; FlutterRust.removeVersion(context, v) },
            onDismiss = { removing = null },
        )
    }
}

/**
 * Release builds (decision 106), added and removed on their own like the wasm target on Rust's page: Termux's
 * qemu-x86_64 and an x86_64 C library, so `flutter build apk --release` (and --profile) run Google's x86_64 AOT
 * compiler under emulation. Repair when only part of it is there.
 */
@Composable
private fun ReleaseBuildsCard(s: FlutterRustState) {
    val context = LocalContext.current
    val mine = s.action == FlutterAction.AddReleaseBuilds || s.action == FlutterAction.RemoveReleaseBuilds
    var confirm by rememberSaveable { mutableStateOf(false) }
    ToolCard("RELEASE BUILDS") {
        ToolText(
            "flutter build apk --release and --profile. Google ships Flutter's Android compiler for release code only for " +
                "x86_64 computers, so it runs here under an x86_64 emulator: slow, a few minutes for a small app and more for a " +
                "large one. About ${FlutterTool.RELEASE_DOWNLOAD_MB} MB download, ${FlutterTool.RELEASE_INSTALLED_MB} MB on the phone. " +
                "Uninstalling Flutter removes it too.",
        )
        if (mine) JobBlock(s)
        when {
            mine && s.running -> BusyButton(if (s.removing) "Removing…" else "Adding…")
            s.running -> BusyButton("Waiting for another install…")
            mine && s.failed -> Unit // JobBlock offers Retry
            s.releaseBuilds == ReleaseBuilds.Installed -> DangerButton("Remove release builds", onClick = { confirm = true })
            s.releaseBuilds == ReleaseBuilds.Missing -> {
                ToolText("Part of it is gone from the phone. Repair puts it back.")
                AccentButton("Repair release builds", onClick = { FlutterRust.addReleaseBuilds(context) }, height = 44.dp, radius = 12.dp)
                DangerButton("Remove release builds", onClick = { confirm = true })
            }
            else -> AccentButton("Add release builds", onClick = { FlutterRust.addReleaseBuilds(context) }, height = 44.dp, radius = 12.dp, enabled = s.checked)
        }
    }
    if (confirm) {
        ConfirmSheet(
            title = "Remove release builds?",
            body = "The x86_64 emulator and C library go, unless something else needs them. Debug APK builds keep working.",
            confirm = "Remove",
            onConfirm = { confirm = false; FlutterRust.removeReleaseBuilds(context) },
            onDismiss = { confirm = false },
        )
    }
}

/** The installed versions with size and default, the running version job, and Add version. */
@Composable
private fun FlutterVersions(s: FlutterRustState, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // Adding the newest stable is what Update does; the Version section above already shows that job.
    val updateJob = s.action == FlutterAction.AddVersion && s.target == s.latestStable
    val versionJob = !updateJob && s.action in setOf(FlutterAction.AddVersion, FlutterAction.SetDefault, FlutterAction.RemoveVersion)
    if (s.versions.isEmpty()) {
        ToolText("No Flutter version yet. Add one.")
    }
    s.versions.forEach { v ->
        VersionRow(v, expanded = open == v.version && !s.running, onClick = { open = if (open == v.version) null else v.version })
        if (open == v.version && !s.running) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!v.isDefault) OutlineButton("Set default", { open = null; FlutterRust.setDefault(context, v.version) }, Modifier.weight(1f))
                OutlineButton("Remove", { open = null; onRemove(v.version) }, Modifier.weight(1f), color = PrTheme.colors.redSoft, border = PrTheme.colors.redBorder)
            }
        }
    }
    if (versionJob) JobBlock(s)
    if (!s.running) OutlineButton("Add version", onAdd, Modifier.fillMaxWidth())
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
