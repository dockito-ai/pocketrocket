package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.clitools.NpmTool
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.updater.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * What the Updates screen keeps current (decision 41), in the screen's order: every tool in Settings › Tools that can
 * get updates (decision 103). Optional ones show only while installed: the add-ons (Flutter and Rust, decisions 75 and
 * 87; Python and Bun, decision 92; the languages and deploy CLIs, decision 98; Headless Chrome and Wrangler), and
 * Node.js, whose row is Termux's nodejs-lts and goes if a terminal swapped it for another Node. A [packageTool]'s row is
 * its main package through apt; an [npmTool]'s is its npm package against `npm view`.
 */
enum class UpdateComponent(
    val label: String,
    val optional: Boolean = false,
    val packageTool: PackageTool? = null,
    val npmTool: NpmTool? = null,
) {
    PocketRocket("PocketRocket"),
    ClaudeCode("Claude Code"),
    Git("Git"),
    Node("Node.js and npm", optional = true),
    Dockito("Dockito"),
    Flutter("Flutter", optional = true),
    Rust("Rust", optional = true),
    Python("Python", optional = true, packageTool = PackageTool.Python),
    Bun("Bun", optional = true, packageTool = PackageTool.Bun),
    Go("Go", optional = true, packageTool = PackageTool.Go),
    Deno("Deno", optional = true, packageTool = PackageTool.Deno),
    Ruby("Ruby", optional = true, packageTool = PackageTool.Ruby),
    Php("PHP", optional = true, packageTool = PackageTool.Php),
    Java("Java and Kotlin", optional = true, packageTool = PackageTool.Java),
    Aws("AWS CLI", optional = true, packageTool = PackageTool.Aws),
    Fly("flyctl", optional = true, packageTool = PackageTool.Fly),
    Chromium("Headless Chrome", optional = true),
    Wrangler("Wrangler", optional = true),
    Vercel("Vercel CLI", optional = true, npmTool = NpmTool.Vercel),
    Netlify("Netlify CLI", optional = true, npmTool = NpmTool.Netlify),
    Firebase("Firebase CLI", optional = true, npmTool = NpmTool.Firebase),
    ;

    companion object {
        /** A package tool's row, or null for one without (the wasm target moves with Rust). */
        fun of(t: PackageTool): UpdateComponent? = entries.firstOrNull { it.packageTool == t }

        /** An npm tool's row. */
        fun of(t: NpmTool): UpdateComponent = entries.first { it.npmTool == t }
    }
}

/** Screen order, except PocketRocket goes last: installing it restarts the app. */
fun updateOrder(components: List<UpdateComponent>): List<UpdateComponent> =
    components.distinct().sortedBy { if (it == UpdateComponent.PocketRocket) Int.MAX_VALUE else it.ordinal }

/** One row of the Updates screen, the same shape for every component. */
data class UpdateRow(
    val component: UpdateComponent,
    /** The installed version, or null when it is not installed (or not read yet). */
    val installed: String?,
    /** The newest version from the last check, or null when none answered. */
    val latest: String?,
    val available: Boolean,
    val checking: Boolean,
    /** The running or last update's steps, as a tool page shows them. */
    val job: SetupState? = null,
    /** What the running job is ("Updating to 2.1.315"). */
    val jobTitle: String? = null,
    val outcome: Outcome? = null,
    /** The steps the update prints, so they can be listed before they start. */
    val steps: List<String> = emptyList(),
) {
    val running: Boolean get() = job?.phase == SetupPhase.Running
    /** The last update failed: the row keeps its steps, the log and Retry. */
    val failed: Boolean get() = !running && (job?.phase == SetupPhase.Failed || outcome?.failed == true)

    /** On the screen: always, except an optional tool's row while that tool isn't installed. */
    val shown: Boolean get() = !component.optional || installed != null || running
}

/**
 * Where one component's versions and update come from. Each wraps the component's own module (AppUpdater,
 * ClaudeUpdater, an AptUpdater, Dockito, FlutterRust, NpmTools, Wrangler); the Updates screen only sees this.
 */
interface UpdateSource {
    val component: UpdateComponent
    val row: Flow<UpdateRow>

    /** The installed version (and any cached check), with no network. */
    fun load(context: Context)

    /** Asks for the newest version; returns when the check has ended. */
    suspend fun check(context: Context)

    /** Updates to the newest version; returns when the update has ended (or needs the user, for PocketRocket). */
    suspend fun update(context: Context)
}
