package com.pocketrocket.ui.tools

/*
 * The tool model behind Settings → Tools (decision 64): each tool declares what it supports (a [ToolSpec]) and
 * reports where it stands (a [ToolState]); [ToolModel] turns the two into the sections its page shows and the
 * line its tile shows. Plain data, no Compose, so it is tested on its own.
 */

/** Built in tools come with PocketRocket (first-run setup installs them); optional ones are installed from Tools. */
enum class ToolKind { BuiltIn, Optional }

/** The parts of a tool page, in page order. Each is shown only when it applies. */
enum class ToolSection { Header, Version, Accounts, Storage, Install }

/** What a tool's install or uninstall job is doing, if anything. */
enum class ToolJob { None, Installing, Uninstalling, InstallFailed, UninstallFailed }

/** What a tool declares about itself. */
data class ToolSpec(
    val name: String,
    val kind: ToolKind,
    /** It shows its version on its page. */
    val hasVersion: Boolean = false,
    /** It updates from the Updates page (and its own Version section). */
    val canUpdate: Boolean = false,
    /** It has its own logins, shown in an Accounts section. */
    val needsLogin: Boolean = false,
    val downloadMb: Int? = null,
    /** The size on the phone when it is not measured. */
    val installedMb: Int? = null,
)

/** Where a tool stands now. */
data class ToolState(
    val installed: Boolean,
    val checked: Boolean = true,
    val job: ToolJob = ToolJob.None,
    /** Progress of a running install, in percent. */
    val progress: Int? = null,
    /** Named versions on the phone ("Node.js" to "v22.9.0"), in page order. */
    val versions: List<Pair<String, String>> = emptyList(),
    val latest: String? = null,
    val updateAvailable: Boolean = false,
    val updating: Boolean = false,
    /** Who is signed in (the default login), when someone is. */
    val signedIn: String? = null,
    /** The size on the phone as measured, in MB; the spec's figure when null. */
    val sizeMb: Int? = null,
    /** Why it can't be installed yet ("Needs the Android tool"), shown instead of its download size. */
    val blockedBy: String? = null,
)

object ToolModel {
    /** The sections [spec]'s page shows in [state], in order. */
    fun sections(spec: ToolSpec, state: ToolState): List<ToolSection> = buildList {
        add(ToolSection.Header)
        if ((spec.hasVersion || spec.canUpdate) && state.installed) add(ToolSection.Version)
        if (spec.needsLogin && state.installed) add(ToolSection.Accounts)
        if (spec.downloadMb != null || sizeMb(spec, state) != null) add(ToolSection.Storage)
        if (spec.kind == ToolKind.Optional) add(ToolSection.Install)
    }

    /** The size on the phone: measured, else the spec's figure, only while installed. */
    fun sizeMb(spec: ToolSpec, state: ToolState): Int? = if (state.installed) state.sizeMb ?: spec.installedMb else null

    /** "Built in", "Installed" or "Not installed". */
    fun label(spec: ToolSpec, state: ToolState): String = when {
        spec.kind == ToolKind.BuiltIn -> "Built in"
        state.installed -> "Installed"
        else -> "Not installed"
    }

    /** What the tile adds after the label: an available update first, then who's signed in, then the size. */
    fun detail(spec: ToolSpec, state: ToolState): String? = when {
        state.updateAvailable && state.latest != null -> "Update to ${state.latest}"
        spec.needsLogin && state.signedIn != null -> state.signedIn
        else -> sizeMb(spec, state)?.let { "$it MB" }
    }

    /** The tile's line, the page's pill and their colour. A running or failed job comes before everything else. */
    fun status(spec: ToolSpec, state: ToolState): ToolStatus = when {
        state.job == ToolJob.Uninstalling -> ToolStatus("Uninstalling…", "Uninstalling", ToolTone.Busy)
        state.job == ToolJob.Installing -> ToolStatus("Installing…" + (state.progress?.let { " $it%" } ?: ""), "Installing", ToolTone.Busy)
        state.job == ToolJob.UninstallFailed -> ToolStatus("Uninstall failed", "Uninstall failed", ToolTone.Failed)
        state.job == ToolJob.InstallFailed -> ToolStatus("Install failed", "Install failed", ToolTone.Failed)
        state.updating -> ToolStatus("Updating…", "Updating", ToolTone.Busy)
        !state.checked -> ToolStatus("Checking…", "Checking", ToolTone.Idle)
        !state.installed && spec.kind == ToolKind.BuiltIn -> ToolStatus("Not installed · run setup again", "Not installed", ToolTone.Failed)
        !state.installed && state.blockedBy != null -> ToolStatus(state.blockedBy, "Not installed", ToolTone.Idle)
        !state.installed -> ToolStatus(listOfNotNull("Not installed", spec.downloadMb?.let { "$it MB download" }).joinToString(" · "), "Not installed", ToolTone.Idle)
        else -> {
            val label = label(spec, state)
            val tone = if (state.updateAvailable && state.latest != null) ToolTone.Update else ToolTone.Installed
            ToolStatus(listOfNotNull(label, detail(spec, state)).joinToString(" · "), label, tone)
        }
    }

    /** The job from an install module's flags. */
    fun job(running: Boolean, failed: Boolean, removing: Boolean): ToolJob = when {
        running -> if (removing) ToolJob.Uninstalling else ToolJob.Installing
        failed -> if (removing) ToolJob.UninstallFailed else ToolJob.InstallFailed
        else -> ToolJob.None
    }

    /** Settings' Tools row: "2 built in · 3 installed". */
    fun summary(tools: List<Pair<ToolSpec, ToolState>>): String {
        val builtIn = tools.count { (spec, _) -> spec.kind == ToolKind.BuiltIn }
        val installed = tools.count { (spec, state) -> spec.kind == ToolKind.Optional && state.installed }
        return "$builtIn built in · ${if (installed == 0) "none" else "$installed"} installed"
    }
}
