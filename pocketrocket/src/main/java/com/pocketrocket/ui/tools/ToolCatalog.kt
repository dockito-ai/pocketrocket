package com.pocketrocket.ui.tools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.androidbuild.AndroidBuild
import com.pocketrocket.androidbuild.AndroidBuildState
import com.pocketrocket.androidbuild.AndroidBuildTool
import com.pocketrocket.androidbuild.AndroidNdk
import com.pocketrocket.androidbuild.AndroidNdkTool
import com.pocketrocket.browser.Chromium
import com.pocketrocket.browser.ChromiumMcp
import com.pocketrocket.browser.ChromiumState
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.dockito.DockitoCli
import com.pocketrocket.dockito.DockitoState
import com.pocketrocket.node.NodeJs
import com.pocketrocket.node.NodeState
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import com.pocketrocket.updates.Updates
import com.pocketrocket.wrangler.Wrangler
import com.pocketrocket.wrangler.WranglerState
import com.pocketrocket.wrangler.WranglerTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Every tool in Settings → Tools (decision 64): what it declares and how its module's state reads as a
 * [ToolState]. Built in: Node.js and npm, Dockito. Optional: Headless Chrome, Android, Android NDK, Wrangler.
 */
enum class Tool(val spec: ToolSpec, val route: String) {
    Node(ToolSpec("Node.js and npm", ToolKind.BuiltIn, hasVersion = true), Routes.TOOL_NODE),
    Dockito(ToolSpec("Dockito", ToolKind.BuiltIn, hasVersion = true, canUpdate = true, needsLogin = true), Routes.TOOL_DOCKITO),
    Chromium(
        ToolSpec("Headless Chrome", ToolKind.Optional, hasVersion = true, downloadMb = ChromiumMcp.DOWNLOAD_MB, installedMb = ChromiumMcp.INSTALLED_MB),
        Routes.TOOL_CHROMIUM,
    ),
    Android(ToolSpec("Android", ToolKind.Optional, downloadMb = AndroidBuildTool.DOWNLOAD_MB, installedMb = AndroidBuildTool.INSTALLED_MB), Routes.TOOL_ANDROID_BUILD),
    AndroidNdk(ToolSpec("Android NDK", ToolKind.Optional, downloadMb = AndroidNdkTool.DOWNLOAD_MB, installedMb = AndroidNdkTool.INSTALLED_MB), Routes.TOOL_ANDROID_NDK),
    Wrangler(
        ToolSpec("Wrangler", ToolKind.Optional, hasVersion = true, needsLogin = true, downloadMb = WranglerTool.DOWNLOAD_MB, installedMb = WranglerTool.INSTALLED_MB),
        Routes.TOOL_WRANGLER,
    ),
    ;

    val icon: ImageVector get() = when (this) {
        Node -> MockIcons.Wrench
        Dockito -> MockIcons.Doc
        Chromium -> MockIcons.Globe
        Android, AndroidNdk -> MockIcons.Android
        Wrangler -> MockIcons.Cloud
    }
}

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

    fun android(s: AndroidBuildState) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed, s.removing),
        progress = s.job?.progress,
    )

    /** A failed NDK install only counts while the Android tool is there; without it the tile says it's needed. */
    fun ndk(s: AndroidBuildState, androidInstalled: Boolean) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed && (androidInstalled || s.removing), s.removing),
        progress = s.job?.progress,
        blockedBy = if (androidInstalled) null else "Needs the Android tool",
    )

    fun wrangler(s: WranglerState) = ToolState(
        installed = s.installed,
        checked = s.checked,
        job = ToolModel.job(s.running, s.failed, s.removing),
        progress = s.job?.progress,
        versions = listOf("Wrangler" to WranglerTool.VERSION, "workerd" to WranglerTool.WORKERD_VERSION),
        signedIn = if (s.signedIn) s.account ?: "Signed in" else null,
    )

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
    val wrangler by Wrangler.state.collectAsState()
    LaunchedEffect(Unit) { refreshAll(context) }
    val dockitoSize by produceState<Int?>(null, dockito.installed) { value = withContext(Dispatchers.IO) { ToolStates.dockitoSizeMb(dockito.installed) } }
    val dockitoRow = updates.rows.firstOrNull { it.component == UpdateComponent.Dockito }
    return listOf(
        Tool.Node to ToolStates.node(node),
        Tool.Dockito to ToolStates.dockito(dockito, dockitoRow, dockitoAccounts.default?.email, dockitoSize),
        Tool.Chromium to ToolStates.chromium(chromium),
        Tool.Android to ToolStates.android(android),
        Tool.AndroidNdk to ToolStates.ndk(ndk, android.installed),
        Tool.Wrangler to ToolStates.wrangler(wrangler),
    )
}

/** Cheap reads only: files on disk, Node's versions once per run, and the Updates cache (checked when it's old). */
fun refreshAll(context: Context) {
    NodeJs.refresh(context)
    Dockito.load()
    Updates.onAppOpen(context)
    Chromium.refresh()
    AndroidBuild.refresh()
    AndroidNdk.refresh()
    Wrangler.refresh()
}
