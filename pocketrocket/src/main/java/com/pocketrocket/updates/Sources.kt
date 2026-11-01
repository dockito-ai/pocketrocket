package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.appupdate.AppUpdateState
import com.pocketrocket.appupdate.AppUpdater
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.fluttertool.FlutterAction
import com.pocketrocket.fluttertool.FlutterRust
import com.pocketrocket.fluttertool.FlutterTool
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.SetupStep
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.updater.ClaudeUpdater
import com.pocketrocket.updater.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * The Updates rows, each a thin adapter over the module that already does the work. No logic of
 * its own beyond mapping that module's state to an UpdateRow.
 */

/** PocketRocket itself: the in-app updater (decision 26). Installing restarts the app, so it always goes last. */
object AppSource : UpdateSource {
    override val component = UpdateComponent.PocketRocket
    override val row = AppUpdater.state.map { s ->
        UpdateRow(
            component, installed = s.installed.ifEmpty { null }, latest = s.latest, available = s.updateAvailable,
            checking = s.checking, job = AppUpdateJob.of(s), jobTitle = s.latest?.let { "Updating to $it" },
            outcome = s.outcome, steps = AppUpdateJob.steps(s.latest),
        )
    }

    override fun load(context: Context) = AppUpdater.load(context)

    override suspend fun check(context: Context) {
        AppUpdater.check(context)
        AppUpdater.state.first { !it.checking }
    }

    /** The caller has made sure Android lets the app install packages. Ends at Android's own confirm screen. */
    override suspend fun update(context: Context) {
        if (AppUpdater.state.value.release == null) check(context)
        if (!AppUpdater.state.value.updateAvailable) return
        AppUpdater.update(context)
        AppUpdater.state.first { !it.busy }
    }
}

/** Claude Code: ClaudeUpdater's check and `--only claude` update (R9). */
object ClaudeSource : UpdateSource {
    /** The steps `--only claude` prints. */
    private val STEPS = listOf("Checking the device", "Downloading Claude Code", "Preparing Claude Code for Android", "Checking claude --version")
    override val component = UpdateComponent.ClaudeCode
    override val row = ClaudeUpdater.state.map { s ->
        UpdateRow(
            component, installed = s.installed, latest = s.latest, available = s.updateAvailable, checking = s.checking,
            job = s.job, jobTitle = s.jobTitle, outcome = s.outcome, steps = if (s.jobTitle?.startsWith("Switching") == true) emptyList() else STEPS,
        )
    }

    override fun load(context: Context) = ClaudeUpdater.onAppOpen(context)

    override suspend fun check(context: Context) {
        ClaudeUpdater.check(context)
        ClaudeUpdater.state.first { !it.checking }
    }

    override suspend fun update(context: Context) {
        val s = ClaudeUpdater.state.value
        val latest = s.latest ?: return
        if (!s.updateAvailable) return
        ClaudeUpdater.update(context, latest)
        ClaudeUpdater.state.first { !it.running }
    }
}

/** A Termux package through apt, `--only <package>-update`: Git, and Rust from the Rust tool. */
class AptSource(override val component: UpdateComponent, private val updater: AptUpdater) : UpdateSource {
    override val row = updater.state.map { s ->
        val latest = AptPolicy.display(s.policy?.candidate)
        UpdateRow(
            component, installed = AptPolicy.display(s.policy?.installed), latest = latest,
            available = s.policy?.updateAvailable == true, checking = s.checking,
            job = s.job, jobTitle = latest?.let { "Updating to $it" }, outcome = s.outcome, steps = updater.steps,
        )
    }

    override fun load(context: Context) = updater.load(context)

    override suspend fun check(context: Context) = withContext(Dispatchers.IO) { updater.checkBlocking(context) }

    override suspend fun update(context: Context) {
        if (updater.state.value.policy?.updateAvailable != true) return
        updater.update(context)
        updater.state.first { !it.running }
    }
}

val GitSource = AptSource(UpdateComponent.Git, GitUpdater)

/** Node.js and npm: Termux's nodejs-lts (decision 102). */
val NodeSource = AptSource(UpdateComponent.Node, NodeUpdater)

/** Headless Chrome's Chromium (decision 102), while it's installed. */
val ChromiumSource = AptSource(UpdateComponent.Chromium, ChromiumUpdater)

/** Rust shows only while `rust` is installed (dpkg): no installed version otherwise, and Check now skips its apt-get update. */
val RustSource = AptSource(UpdateComponent.Rust, RustUpdater)

/** Every package tool with a row (Python, Bun and the languages): one apt source each. */
val PackageToolSources: List<AptSource> =
    UpdateComponent.entries.mapNotNull { c -> c.packageTool?.let { AptSource(c, packageToolUpdater(it, c.label)) } }

/**
 * Flutter (decision 75): a newer stable for the default version only. Updating adds it as a new version, makes it the
 * default and removes the old default; other versions stay, and a project's pin is never changed (a project pinning
 * the old one gets it back with `fvm install <version>`).
 */
object FlutterSource : UpdateSource {
    override val component = UpdateComponent.Flutter
    private val checking = MutableStateFlow(false)
    override val row = combine(FlutterRust.state, checking) { s, busy ->
        val default = s.defaultVersion
        val adding = s.action == FlutterAction.AddVersion && s.target == s.latestStable
        UpdateRow(
            component, installed = default?.takeIf { s.flutterInstalled }, latest = s.latestStable,
            available = s.flutterInstalled && FlutterTool.newerStable(default, s.latestStable), checking = busy,
            job = if (adding) s.job else null, jobTitle = s.latestStable?.let { "Updating to $it" },
            outcome = if (adding && s.failed) Outcome(s.outcome ?: "Stopped.", failed = true) else null,
            steps = FlutterTool.flutterSteps(s.latestStable),
        )
    }

    /** Flutter's answer is not kept across restarts, so read it once per run, as for Dockito. */
    override fun load(context: Context) {
        FlutterRust.refresh()
        if (FlutterRust.flutterInstalled() && FlutterRust.state.value.latestStable == null) FlutterRust.loadReleasesLater()
    }

    override suspend fun check(context: Context) {
        if (!FlutterRust.flutterInstalled()) return
        checking.value = true
        try {
            withContext(Dispatchers.IO) { FlutterRust.loadReleases() }
        } finally {
            checking.value = false
        }
        FlutterRust.refresh()
    }

    override suspend fun update(context: Context) {
        val s = FlutterRust.state.value
        val latest = s.latestStable ?: return
        val old = s.defaultVersion ?: return
        if (!FlutterTool.newerStable(old, latest) || s.running) return
        FlutterRust.updateDefault(context, latest, replaces = old)
        FlutterRust.awaitJob()
    }
}

/** Dockito's CLI: the Homebrew tap formula, installed as `Dockito.install` does (decision 40). */
object DockitoSource : UpdateSource {
    /** The steps `--only dockito` prints. */
    private val STEPS = listOf("Checking the device", "Downloading Dockito", "Preparing Dockito for Android", "Checking dockito --version")
    override val component = UpdateComponent.Dockito
    override val row = Dockito.state.map { s ->
        UpdateRow(
            component, installed = s.installed, latest = s.latest?.version, available = s.updateAvailable, checking = s.checking,
            job = s.job, jobTitle = s.jobTitle, outcome = s.outcome, steps = STEPS,
        )
    }

    /** The formula's answer is not kept across restarts (an update needs its URL and SHA-256), so read it once per run. */
    override fun load(context: Context) {
        val s = Dockito.state.value
        if (s.latest == null && !s.checking) Dockito.check(context) else Dockito.load()
    }

    override suspend fun check(context: Context) {
        Dockito.check(context)
        Dockito.state.first { !it.checking }
    }

    override suspend fun update(context: Context) {
        val s = Dockito.state.value
        val latest = s.latest ?: return
        if (!s.updateAvailable) return
        Dockito.install(context, latest)
        Dockito.state.first { !it.running }
    }
}

/** The in-app updater's steps, shaped like the setup script's so the row shows them as a tool page does. */
internal object AppUpdateJob {
    private const val VERIFY = "Checking the SHA-256"
    private const val INSTALL = "Handing it to Android's installer"
    private const val CONFIRM = "Confirm in Android's installer"

    fun steps(version: String?) = listOf("Downloading ${version ?: "the update"}", VERIFY, INSTALL)

    fun of(s: AppUpdateState): SetupState? {
        val names = steps(s.release?.version ?: s.latest)
        val at = when (s.step) {
            AppUpdateState.Step.Idle -> return failed(s, names)
            AppUpdateState.Step.Downloading -> 0
            AppUpdateState.Step.Verifying -> 1
            AppUpdateState.Step.Installing -> 2
            AppUpdateState.Step.Confirming -> 3
        }
        val steps = (names + CONFIRM).take(at + 1).mapIndexed { i, name -> SetupStep(name, if (i < at) StepStatus.Done else StepStatus.Running) }
        val downloading = s.step == AppUpdateState.Step.Downloading
        return SetupState(
            phase = SetupPhase.Running,
            steps = steps,
            totalSteps = names.size,
            progress = if (downloading && s.total > 0) (s.downloaded * 100 / s.total).toInt().coerceIn(0, 100) else null,
            detail = if (downloading) if (s.total > 0) "${mb(s.downloaded)} of ${mb(s.total)} MB" else "${mb(s.downloaded)} MB" else null,
        )
    }

    /** After a failed update: the steps up to the one that failed, with the reason. */
    private fun failed(s: AppUpdateState, names: List<String>): SetupState? {
        val outcome = s.outcome?.takeIf { it.failed } ?: return null
        val at = when (s.failedAt) {
            AppUpdateState.Step.Verifying -> 1
            AppUpdateState.Step.Installing, AppUpdateState.Step.Confirming -> 2
            else -> 0
        }
        val steps = names.take(at + 1).mapIndexed { i, name -> SetupStep(name, if (i < at) StepStatus.Done else StepStatus.Failed) }
        return SetupState(phase = SetupPhase.Failed, steps = steps, totalSteps = names.size, error = outcome.message, log = listOf("[error] ${outcome.message}"))
    }

    private fun mb(bytes: Long): String {
        val m = bytes / 1_048_576.0
        return if (m < 10) String.format(Locale.US, "%.1f", m) else m.toInt().toString()
    }
}
