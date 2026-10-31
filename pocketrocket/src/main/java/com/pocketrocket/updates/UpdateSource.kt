package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupState
import com.pocketrocket.updater.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * What the Updates screen keeps current (decision 41), in the screen's order. Optional ones come from an optional
 * tool and show only while it's installed (Flutter and Rust, decisions 75 and 87; Python and Bun, decision 92).
 */
enum class UpdateComponent(val label: String, val optional: Boolean = false) {
    PocketRocket("PocketRocket"),
    ClaudeCode("Claude Code"),
    Git("Git"),
    Dockito("Dockito"),
    Flutter("Flutter", optional = true),
    Rust("Rust", optional = true),
    Python("Python", optional = true),
    Bun("Bun", optional = true),
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
 * ClaudeUpdater, GitUpdater, Dockito); the Updates screen only sees this.
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
