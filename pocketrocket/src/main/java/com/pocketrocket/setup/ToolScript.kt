package com.pocketrocket.setup

import android.content.Context

/** One optional tool's `--only <option>` run of the setup script, with each line fed into its job as it arrives. */
object ToolScript {
    data class Result(val exit: Int, val sawDone: Boolean)

    /**
     * Blocks until the script ends. [update] receives a function that advances the job; the caller applies it to its
     * state. An exception from starting the script becomes the job's error and exit -1.
     */
    fun run(
        context: Context,
        option: String,
        environment: Map<String, String> = emptyMap(),
        update: ((SetupState) -> SetupState) -> Unit,
    ): Result {
        var sawDone = false
        val exit = runCatching {
            SetupScript.run(context, listOf("--only", option), environment) { raw ->
                val line = SetupLine.parse(raw)
                if (line == SetupLine.Done) sawDone = true
                synchronized(this) { update { job -> job.on(line) } }
            }
        }.getOrElse { e ->
            update { job -> job.on(SetupLine.Error(e.message ?: e.toString())) }
            -1
        }
        return Result(exit, sawDone)
    }
}

/** The job after the script ended: every step closed, and the phase that says how it went. */
fun SetupState.finished(ok: Boolean): SetupState =
    finish(if (ok) StepStatus.Done else StepStatus.Failed)
        .copy(phase = if (ok) SetupPhase.Done else SetupPhase.Failed, progress = null, detail = null)
