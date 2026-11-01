package com.pocketrocket.ui.tools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.androidbuild.AndroidBuild
import com.pocketrocket.androidbuild.AndroidBuildTool
import com.pocketrocket.androidbuild.AndroidNdk
import com.pocketrocket.androidbuild.AndroidNdkTool
import com.pocketrocket.browser.Chromium
import com.pocketrocket.browser.ChromiumMcp
import com.pocketrocket.browser.ChromiumState
import com.pocketrocket.cpp.Cpp
import com.pocketrocket.cpp.CppTool
import com.pocketrocket.R
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.dockito.DockitoCli
import com.pocketrocket.dockito.DockitoState
import com.pocketrocket.fluttertool.FlutterPart
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.fluttertool.FlutterRustState
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.node.NodeJs
import com.pocketrocket.node.NodeState
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.pkgtools.PackageTools
import com.pocketrocket.pkgtools.PackageToolsState
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.setup.ScriptToolState
import com.pocketrocket.ui.Routes
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import com.pocketrocket.updates.Updates
import com.pocketrocket.wrangler.Wrangler
import com.pocketrocket.wrangler.WranglerState
import com.pocketrocket.wrangler.WranglerTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pocketrocket.clilogin.CliLogins
import com.pocketrocket.clilogin.LoginSpec
import com.pocketrocket.clilogin.LoginState
import com.pocketrocket.clitools.NpmTool
import com.pocketrocket.clitools.NpmTools
import com.pocketrocket.clitools.NpmToolsState
import com.pocketrocket.wrangler.WranglerLogin
import java.io.File

/**
 * Every tool in Settings → Tools (decisions 64 and 87): what it declares and how its module's state reads as a
 * [ToolState]. Included (built in): Claude Code, Dockito, Git, Node.js and npm. Add-ons (optional): Android,
 * Android NDK, AWS CLI, Bun, C and C++, Deno, Firebase CLI, flyctl, Flutter & Dart, Go, Headless Chrome, Java and Kotlin, Netlify
 * CLI, PHP, Python, Ruby, Rust, Vercel CLI, Wrangler. The screen orders them by name. A tool with a [pkg] is a package
 * tool (decisions 92 and 98), read from dpkg; one with an [npm] is a global npm CLI (decision 98).
 */
enum class Tool(val spec: ToolSpec, val route: String, val pkg: PackageTool? = null, val npm: NpmTool? = null) {
    ClaudeCode(ToolSpec("Claude Code", ToolKind.BuiltIn, hasVersion = true, canUpdate = true), Routes.CLAUDE_CODE),
    Git(ToolSpec("Git", ToolKind.BuiltIn, hasVersion = true, canUpdate = true), Routes.TOOL_GIT),
    Node(ToolSpec("Node.js and npm", ToolKind.BuiltIn, hasVersion = true), Routes.TOOL_NODE),
    Dockito(ToolSpec("Dockito", ToolKind.BuiltIn, hasVersion = true, canUpdate = true, needsLogin = true), Routes.TOOL_DOCKITO),
    Chromium(
        ToolSpec("Headless Chrome", ToolKind.Optional, hasVersion = true, downloadMb = ChromiumMcp.DOWNLOAD_MB, installedMb = ChromiumMcp.INSTALLED_MB),
        Routes.TOOL_CHROMIUM,
    ),
    Android(ToolSpec("Android", ToolKind.Optional, downloadMb = AndroidBuildTool.DOWNLOAD_MB, installedMb = AndroidBuildTool.INSTALLED_MB), Routes.TOOL_ANDROID_BUILD),
    AndroidNdk(ToolSpec("Android NDK", ToolKind.Optional, downloadMb = AndroidNdkTool.DOWNLOAD_MB, installedMb = AndroidNdkTool.INSTALLED_MB), Routes.TOOL_ANDROID_NDK),
    Cpp(ToolSpec("C and C++", ToolKind.Optional, downloadMb = CppTool.DOWNLOAD_MB, installedMb = CppTool.INSTALLED_MB), Routes.TOOL_CPP),
    Wrangler(
        ToolSpec("Wrangler", ToolKind.Optional, hasVersion = true, needsLogin = true, downloadMb = WranglerTool.DOWNLOAD_MB, installedMb = WranglerTool.INSTALLED_MB),
        Routes.TOOL_WRANGLER,
    ),
    Flutter(
        ToolSpec(
            "Flutter & Dart", ToolKind.Optional, hasVersion = true, canUpdate = true,
            downloadMb = FlutterTool.FLUTTER_DOWNLOAD_MB, installedMb = FlutterTool.FLUTTER_INSTALLED_MB,
        ),
        Routes.TOOL_FLUTTER,
    ),
    Rust(
        ToolSpec(
            "Rust", ToolKind.Optional, hasVersion = true, canUpdate = true,
            downloadMb = FlutterTool.RUST_DOWNLOAD_MB, installedMb = FlutterTool.RUST_INSTALLED_MB,
        ),
        Routes.TOOL_RUST,
    ),
    Python(PackageTool.Python.spec("Python"), Routes.TOOL_PYTHON, PackageTool.Python),
    Bun(PackageTool.Bun.spec("Bun"), Routes.TOOL_BUN, PackageTool.Bun),
    Go(PackageTool.Go.spec("Go"), Routes.TOOL_GO, PackageTool.Go),
    Deno(PackageTool.Deno.spec("Deno"), Routes.TOOL_DENO, PackageTool.Deno),
    Ruby(PackageTool.Ruby.spec("Ruby"), Routes.TOOL_RUBY, PackageTool.Ruby),
    Php(PackageTool.Php.spec("PHP"), Routes.TOOL_PHP, PackageTool.Php),
    Java(PackageTool.Java.spec("Java and Kotlin"), Routes.TOOL_JAVA, PackageTool.Java),
    Aws(PackageTool.Aws.spec("AWS CLI"), Routes.TOOL_AWS, PackageTool.Aws),
    Fly(PackageTool.Fly.spec("flyctl"), Routes.TOOL_FLY, PackageTool.Fly),
    Vercel(NpmTool.Vercel.spec("Vercel CLI"), Routes.TOOL_VERCEL, npm = NpmTool.Vercel),
    Netlify(NpmTool.Netlify.spec("Netlify CLI"), Routes.TOOL_NETLIFY, npm = NpmTool.Netlify),
    Firebase(NpmTool.Firebase.spec("Firebase CLI"), Routes.TOOL_FIREBASE, npm = NpmTool.Firebase),
    ;

    /** Its own login, shown on its page and as who's signed in on its tile (decision 98). */
    val login: LoginSpec? get() = if (this == Wrangler) WranglerLogin else pkg?.login ?: npm?.login

    /** Its brand mark (decision 87), bundled as a VectorDrawable; sources in docs/third-party-icons.md. */
    val icon: ToolGlyph get() = when (this) {
        ClaudeCode -> ToolGlyph(R.drawable.ic_tool_claude, 0xFFD97757)
        Git -> ToolGlyph(R.drawable.ic_tool_git, 0xFFF03C2E)
        Node -> ToolGlyph(R.drawable.ic_tool_nodedotjs, 0xFF5FA04E)
        Dockito -> ToolGlyph(R.drawable.ic_tool_dockito, 0xFF1A6DFF)
        Chromium -> ToolGlyph(R.drawable.ic_tool_googlechrome, 0xFF4285F4)
        Android, AndroidNdk -> ToolGlyph(R.drawable.ic_tool_android, 0xFF3DDC84)
        Cpp -> ToolGlyph(R.drawable.ic_tool_cplusplus, 0xFF00599C)
        Wrangler -> ToolGlyph(R.drawable.ic_tool_cloudflareworkers, 0xFFF38020)
        Flutter -> ToolGlyph(R.drawable.ic_tool_flutter, 0xFF02569B)
        Rust -> ToolGlyph(R.drawable.ic_tool_rust, 0xFF000000)
        Python -> ToolGlyph(R.drawable.ic_tool_python, 0xFF3776AB)
        Bun -> ToolGlyph(R.drawable.ic_tool_bun, 0xFF000000)
        Go -> ToolGlyph(R.drawable.ic_tool_go, 0xFF00ADD8)
        Deno -> ToolGlyph(R.drawable.ic_tool_deno, 0xFF000000)
        Ruby -> ToolGlyph(R.drawable.ic_tool_ruby, 0xFFCC342D)
        Php -> ToolGlyph(R.drawable.ic_tool_php, 0xFF777BB4)
        Java -> ToolGlyph(R.drawable.ic_tool_openjdk, 0xFF000000)
        Aws -> ToolGlyph(R.drawable.ic_tool_awscli, 0xFFFF9900)
        Fly -> ToolGlyph(R.drawable.ic_tool_flydotio, 0xFF24175B)
        Vercel -> ToolGlyph(R.drawable.ic_tool_vercel, 0xFF000000)
        Netlify -> ToolGlyph(R.drawable.ic_tool_netlify, 0xFF00C7B7)
        Firebase -> ToolGlyph(R.drawable.ic_tool_firebase, 0xFFDD2C00)
    }
}

/**
 * An Updates row's icon: the same brand mark as its tool, and PocketRocket's own rocket (the launcher mark cropped)
 * in its orange.
 */
val UpdateComponent.icon: ToolGlyph get() = when (this) {
    UpdateComponent.PocketRocket -> ToolGlyph(R.drawable.ic_pr_notification, 0xFFFF7A1A)
    UpdateComponent.ClaudeCode -> Tool.ClaudeCode.icon
    UpdateComponent.Git -> Tool.Git.icon
    UpdateComponent.Dockito -> Tool.Dockito.icon
    UpdateComponent.Flutter -> Tool.Flutter.icon
    UpdateComponent.Rust -> Tool.Rust.icon
    else -> Tool.entries.first { it.pkg != null && it.pkg == packageTool }.icon
}

/** A package tool's tile (decision 92): an add-on with its versions, updated from Updates. */
private fun PackageTool.spec(name: String) =
    ToolSpec(name, ToolKind.Optional, hasVersion = true, canUpdate = true, needsLogin = login != null, downloadMb = downloadMb, installedMb = installedMb)

/** An npm CLI's tile (decision 98): its version from npm's folder, its login, and how long the install takes. */
private fun NpmTool.spec(name: String) = ToolSpec(
    name, ToolKind.Optional, hasVersion = true, needsLogin = true,
    downloadMb = downloadMb, installedMb = installedMb, installMinutes = installMinutes,
)

/** Who a login's tile says is signed in, or null. */
private fun LoginState.who(): String? = if (signedIn) account ?: "Signed in" else null

/** How each module's state reads as a [ToolState]. */
object ToolStates {
    fun node(s: NodeState) = ToolState(
        installed = s.installed,
        checked = s.checked,
        versions = listOfNotNull(s.node?.let { "Node.js" to it }, s.npm?.let { "npm" to it }),
        sizeMb = s.sizeMb,
    )

    fun dockito(s: DockitoState, row: UpdateRow?, signedIn: String?, sizeMb: Int?) = ToolState(
        installed = s.installed != null,
        checked = s.loaded,
        versions = listOfNotNull(s.installed?.let { "Installed" to it }),
        latest = row?.latest ?: s.latest?.version,
        updateAvailable = s.updateAvailable,
        updating = s.running,
        signedIn = signedIn,
        sizeMb = sizeMb,
    )

    fun chromium(s: ChromiumState) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed, s.removing),
        progress = s.job?.progress,
        versions = listOf("Chrome DevTools MCP" to ChromiumMcp.MCP_VERSION),
    )

    /** A tool installed and removed by the setup script alone (Android, C and C++). */
    fun scriptTool(s: ScriptToolState) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed, s.removing),
        progress = s.job?.progress,
        problem = s.problem,
    )

    /** A failed NDK install only counts while the Android tool is there; without it the tile says it's needed. */
    fun ndk(s: ScriptToolState, androidInstalled: Boolean) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed && (androidInstalled || s.removing), s.removing),
        progress = s.job?.progress,
        problem = s.problem,
        blockedBy = if (androidInstalled) null else "Needs the Android tool",
    )

    fun wrangler(s: WranglerState, login: LoginState = LoginState()) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed, s.removing),
        progress = s.job?.progress,
        // What npm installed, which a terminal may have changed; the pinned versions until it has been read.
        versions = listOf("Wrangler" to (s.version ?: WranglerTool.VERSION), "workerd" to (s.workerdVersion ?: WranglerTool.WORKERD_VERSION)),
        problem = s.problem,
        signedIn = login.who(),
    )

    /**
     * A deploy CLI from npm (decision 98): installed while npm's folder for it is there, its version from there, Repair
     * when its command is missing, and who is signed in from its own config.
     */
    fun npmTool(t: NpmTool, s: NpmToolsState, login: LoginState = LoginState()): ToolState {
        val mine = s.isFor(t)
        val here = s.present[t]
        return ToolState(
            installed = here != null,
            checked = s.checked,
            job = if (mine) ToolModel.job(s.running, s.failed, s.removing) else ToolJob.None,
            progress = if (mine) s.job?.progress else null,
            versions = listOfNotNull(here?.version?.let { t.npm to it }),
            problem = here?.problem,
            signedIn = login.who(),
        )
    }

    /** A tool that is only an Updates row (Claude Code, Git): its versions and update come from the row. */
    fun fromRow(row: UpdateRow?) = ToolState(
        installed = row?.installed != null,
        checked = row != null,
        versions = listOfNotNull(row?.installed?.let { "Installed" to it }),
        latest = row?.latest,
        updateAvailable = row?.available == true,
        updating = row?.running == true,
    )

    /**
     * Flutter & Dart (decision 87). Installing or uninstalling it is the tool's job; adding a version, setting the
     * default or removing one shows as Updating on the tile and runs inside the page's own cards. Rust's jobs aren't
     * its own. [row] is Flutter's Updates row (a newer stable for the default).
     */
    fun flutter(s: FlutterRustState, row: UpdateRow? = null): ToolState {
        val mine = s.isFor(FlutterPart.Flutter)
        val whole = mine && s.action?.whole == true
        return ToolState(
            installed = s.flutterInstalled,
            checked = s.checked,
            job = if (whole) ToolModel.job(s.running, s.failed, s.removing) else ToolJob.None,
            progress = if (whole) s.job?.progress else null,
            versions = listOfNotNull(s.defaultVersion?.let { "Default" to it }, "FVM" to FlutterTool.FVM_VERSION),
            latest = row?.latest,
            updateAvailable = row?.available == true,
            updating = (mine && s.running && !whole) || row?.running == true,
            sizeMb = FlutterTool.flutterSizeMb(s.flutterInstalled, s.versions.size, s.flutterBytes).takeIf { s.flutterInstalled },
            problem = s.flutterProblem,
        )
    }

    /**
     * A package tool (decision 92): installed when its main package is on the phone, read from dpkg; its versions and
     * size from dpkg too, its update from its Updates row.
     */
    fun packageTool(t: PackageTool, s: PackageToolsState, row: UpdateRow?, login: LoginState = LoginState()): ToolState {
        val mine = s.isFor(t)
        return ToolState(
            installed = s.installed(t),
            checked = s.checked,
            job = if (mine) ToolModel.job(s.running, s.failed, s.removing) else ToolJob.None,
            progress = if (mine) s.job?.progress else null,
            versions = t.versions(s.packages),
            latest = row?.latest,
            updateAvailable = row?.available == true,
            // A JDK or Kotlin being added or removed on Java's page shows on its tile (decision 101).
            updating = row?.running == true || (t == PackageTool.Java && s.installed(t) && s.running && s.tool in PackageTool.JAVA_PARTS),
            sizeMb = t.sizeMb(s.packages),
            problem = s.problem(t),
            signedIn = login.who(),
        )
    }

    /**
     * Rust (decision 87): installing and removing are its jobs. Installed, version and size come from dpkg, so a
     * `pkg install rust` from a terminal shows too; its update comes from its Updates row. Adding or removing the wasm
     * target ([wasmBusy]) shows as Updating.
     */
    fun rust(s: FlutterRustState, row: UpdateRow?, wasmBusy: Boolean = false): ToolState {
        val mine = s.isFor(FlutterPart.Rust)
        return ToolState(
            installed = s.rustInstalled,
            checked = s.checked,
            job = if (mine) ToolModel.job(s.running, s.failed, s.removing) else ToolJob.None,
            progress = if (mine) s.job?.progress else null,
            versions = listOfNotNull(FlutterTool.rustVersion(s.rustPackages)?.let { "Rust" to it }),
            latest = row?.latest,
            updateAvailable = row?.available == true,
            updating = row?.running == true || wasmBusy,
            sizeMb = FlutterTool.rustSizeMb(s.rustPackages),
        )
    }

    /** The installed CLI's folder, in MB. Blocking. */
    fun dockitoSizeMb(version: String?): Int? {
        val dir = version?.let { File(DockitoCli.dir, it) }?.takeIf { it.isDirectory } ?: return null
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ((bytes + 524_288) / 1_048_576).toInt().coerceAtLeast(1)
    }
}

/** Every tool with its state, for the Tools list and Settings' Tools row. Reads each module once when shown. */
@Composable
fun rememberTools(): List<Pair<Tool, ToolState>> {
    val context = LocalContext.current
    val node by NodeJs.state.collectAsState()
    val dockito by Dockito.state.collectAsState()
    val dockitoAccounts by DockitoAccounts.state.collectAsState()
    val updates by Updates.state.collectAsState()
    val chromium by Chromium.state.collectAsState()
    val android by AndroidBuild.state.collectAsState()
    val ndk by AndroidNdk.state.collectAsState()
    val cpp by Cpp.state.collectAsState()
    val wrangler by Wrangler.state.collectAsState()
    val flutter by FlutterRust.state.collectAsState()
    val packages by PackageTools.state.collectAsState()
    val npm by NpmTools.state.collectAsState()
    // One collector per tool with a login; Tool.entries is fixed, so the order of these calls never changes.
    val logins = Tool.entries.associateWith { t -> t.login?.let { CliLogins.of(it).state.collectAsState().value } ?: LoginState() }
    LaunchedEffect(Unit) { refreshAll(context) }
    val dockitoSize by produceState<Int?>(null, dockito.installed) { value = withContext(Dispatchers.IO) { ToolStates.dockitoSizeMb(dockito.installed) } }
    fun row(c: UpdateComponent) = updates.rows.firstOrNull { it.component == c }
    return listOf(
        Tool.ClaudeCode to ToolStates.fromRow(row(UpdateComponent.ClaudeCode)),
        Tool.Git to ToolStates.fromRow(row(UpdateComponent.Git)),
        Tool.Node to ToolStates.node(node),
        Tool.Dockito to ToolStates.dockito(dockito, row(UpdateComponent.Dockito), dockitoAccounts.default?.email, dockitoSize),
        Tool.Chromium to ToolStates.chromium(chromium),
        Tool.Android to ToolStates.scriptTool(android),
        Tool.AndroidNdk to ToolStates.ndk(ndk, android.installed),
        Tool.Cpp to ToolStates.scriptTool(cpp),
        Tool.Wrangler to ToolStates.wrangler(wrangler, logins.getValue(Tool.Wrangler)),
        Tool.Flutter to ToolStates.flutter(flutter, row(UpdateComponent.Flutter)),
        Tool.Rust to ToolStates.rust(flutter, row(UpdateComponent.Rust), packages.running && packages.isFor(PackageTool.RustWasm)),
    ) + Tool.entries.mapNotNull { tool ->
        val login = logins.getValue(tool)
        tool.pkg?.let { tool to ToolStates.packageTool(it, packages, UpdateComponent.of(it)?.let(::row), login) }
            ?: tool.npm?.let { tool to ToolStates.npmTool(it, npm, login) }
    }
}

/** Cheap reads only: files on disk, Node's versions once per run, and the Updates cache (checked when it's old). */
fun refreshAll(context: Context) {
    NodeJs.refresh(context)
    Dockito.load()
    Updates.onAppOpen(context)
    Chromium.refresh()
    AndroidBuild.refresh()
    AndroidNdk.refresh()
    Cpp.refresh()
    Wrangler.refresh()
    FlutterRust.refresh()
    PackageTools.refresh()
    NpmTools.refresh()
}
