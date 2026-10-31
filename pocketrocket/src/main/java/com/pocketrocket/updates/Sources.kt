package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.appupdate.AppUpdateState
import com.pocketrocket.appupdate.AppUpdater
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.SetupStep
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.updater.ClaudeUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * The four Updates rows, each a thin adapter over the module that already does the work. No logic of
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

/** Git: apt, through `--only git-update`. */
object GitSource : UpdateSource {
    override val component = UpdateComponent.Git
    override val row = GitUpdater.state.map { s ->
        val latest = AptPolicy.display(s.policy?.candidate)
        UpdateRow(
            component, installed = AptPolicy.display(s.policy?.installed), latest = latest,
            available = s.policy?.updateAvailable == true, checking = s.checking,
            job = s.job, jobTitle = latest?.let { "Updating to $it" }, outcome = s.outcome, steps = GitUpdater.STEPS,
        )
    }

    override fun load(context: Context) = GitUpdater.load(context)

    override suspend fun check(context: Context) = withContext(Dispatchers.IO) { GitUpdater.checkBlocking(context) }

    override suspend fun update(context: Context) {
        if (GitUpdater.state.value.policy?.updateAvailable != true) return
        GitUpdater.update(context)
        GitUpdater.state.first { !it.running }
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
