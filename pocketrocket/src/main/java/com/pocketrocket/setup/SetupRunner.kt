package com.pocketrocket.setup

import android.app.Activity
import android.content.Context
import com.pocketrocket.PocketRocket
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.dockito.DockitoFormula
import com.pocketrocket.termux.BootstrapSecondStage
import com.pocketrocket.termux.Termux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs first-run setup: Termux's bootstrap, then the setup script. Lives for the whole process, so it
 * outlives rotation and the activity; Termux's foreground service keeps the process alive in the background.
 */
object SetupRunner {
    private const val BOOTSTRAP_TITLE = "Preparing the Linux environment"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /** Starts setup, or does nothing if it is already running. Also used for Retry. */
    fun start(activity: Activity) {
        if (_state.value.phase == SetupPhase.Running) return
        _state.value = SetupState(
            phase = SetupPhase.Running,
            steps = listOf(SetupStep(BOOTSTRAP_TITLE, StepStatus.Running)),
            stepStartedAt = System.currentTimeMillis(),
            lastLineAt = System.currentTimeMillis(),
        )
        val appContext = activity.applicationContext
        Termux.startForegroundService(appContext)
        PocketRocket.bootstrapInstaller.install(activity) {
            scope.launch { runScript(appContext) }
        }
    }

    private fun runScript(context: Context) {
        var sawDone = false
        val exitCode = try {
            val secondStage = BootstrapSecondStage.runIfNeeded(context) { line -> onOutput(SetupLine.Log(line)) }
            if (secondStage != 0) {
                onOutput(SetupLine.Error("Finishing the Linux environment failed (exit code $secondStage)."))
                secondStage
            } else {
                // Dockito's CLI is a first-run step (decision 40); the script installs the release the formula
                // names and refuses it unless the SHA-256 matches. Offline, the script stops at that step.
                val dockito = DockitoFormula.fetch()
                val env = dockito?.let {
                    mapOf(
                        "POCKETROCKET_DOCKITO_VERSION" to it.version,
                        "POCKETROCKET_DOCKITO_URL" to it.url,
                        "POCKETROCKET_DOCKITO_SHA256" to it.sha256,
                    )
                }.orEmpty()
                SetupScript.run(context, environment = env) { line ->
                    val parsed = SetupLine.parse(line)
                    if (parsed == SetupLine.Done) sawDone = true
                    onOutput(parsed)
                }
            }
        } catch (e: Exception) {
            _state.update { it.apply(SetupLine.Error(e.message ?: e.toString())) }
            -1
        }

        if (exitCode == 0 && sawDone) {
            SetupPrefs.markDone(context)
            Dockito.load()
            // At app start home didn't exist yet: install pocketrocket-workspace and listen now.
            com.pocketrocket.workspaces.WorkspaceRequests.start(context)
            _state.update { it.finish(StepStatus.Done).copy(phase = SetupPhase.Done, progress = null, detail = null) }
        } else {
            _state.update {
                it.finish(StepStatus.Failed).copy(
                    phase = SetupPhase.Failed,
                    error = it.error ?: "Setup stopped unexpectedly (exit code $exitCode).",
                )
            }
        }
    }

    private fun onOutput(line: SetupLine) = synchronized(this) { _state.update { it.apply(line) } }

    /** The bootstrap step comes before the script's own steps. */
    private fun SetupState.apply(line: SetupLine): SetupState = on(line, extraSteps = 1)
}
