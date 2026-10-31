package com.pocketrocket.setup

enum class StepStatus { Pending, Running, Done, Failed }

data class SetupStep(val title: String, val status: StepStatus)

enum class SetupPhase { Idle, Running, Done, Failed }

/** Everything the setup screen shows. Steps are the app's own bootstrap step followed by the script's steps. */
data class SetupState(
    val phase: SetupPhase = SetupPhase.Idle,
    val steps: List<SetupStep> = emptyList(),
    /** Steps the script announced it will run, plus the bootstrap step; null until the first `[step]` line. */
    val totalSteps: Int? = null,
    /** Progress of the running step, 0-100, or null when it has not reported any. */
    val progress: Int? = null,
    val detail: String? = null,
    val log: List<String> = emptyList(),
    val error: String? = null,
    /** When the running step started and when the script last printed anything (epoch ms), for the step's
     *  ticking timer and the quiet "Still working…" note. */
    val stepStartedAt: Long? = null,
    val lastLineAt: Long? = null,
) {
    val currentIndex: Int get() = steps.indexOfLast { it.status != StepStatus.Pending }
    val remainingSteps: Int get() = totalSteps?.let { it - steps.size }?.coerceAtLeast(0) ?: 0
}

private const val MAX_LOG_LINES = 1000

/**
 * The state after one script line, read at [now]. [extraSteps] are steps the caller runs before the script (the
 * setup screen's bootstrap step), counted into [SetupState.totalSteps].
 */
fun SetupState.on(line: SetupLine, extraSteps: Int = 0, now: Long = System.currentTimeMillis()): SetupState = when (line) {
    is SetupLine.Step -> finish(StepStatus.Done).let { done ->
        done.copy(
            steps = done.steps + SetupStep(line.title, StepStatus.Running),
            totalSteps = line.total + extraSteps,
            progress = null,
            detail = null,
            stepStartedAt = now,
            lastLineAt = now,
        )
    }
    is SetupLine.Progress -> copy(progress = line.percent, detail = line.detail.ifEmpty { null }, lastLineAt = now)
    is SetupLine.Done -> finish(StepStatus.Done)
    is SetupLine.Error -> copy(error = line.message, log = log.appendCapped("[error] ${line.message}"), lastLineAt = now)
    is SetupLine.Log -> copy(log = log.appendCapped(line.text), lastLineAt = now)
}

/** Marks the running step, if any, as [status]. */
fun SetupState.finish(status: StepStatus): SetupState =
    copy(steps = steps.map { if (it.status == StepStatus.Running) it.copy(status = status) else it })

private fun List<String>.appendCapped(line: String): List<String> =
    if (size < MAX_LOG_LINES) this + line else drop(size - MAX_LOG_LINES + 1) + line
