package com.pocketrocket.ui.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import com.pocketrocket.updates.Updates
import com.pocketrocket.updates.UpdatesState

/** An optional tool's install and uninstall: its job, its steps and what the buttons do. */
class ToolInstall(
    val job: SetupState?,
    val outcome: String?,
    val removing: Boolean,
    val installSteps: List<String>,
    val removeSteps: List<String>,
    /** The header's line after a failure ("Retry picks up where it stopped."). */
    val failedLine: String,
    val onInstall: () -> Unit,
    val onRetry: () -> Unit,
    val onUninstall: () -> Unit,
    val confirmTitle: String,
    val confirmBody: String,
    /** Replaces the Install button while another tool blocks it (busy, or needed first); null when nothing does. */
    val blocked: (@Composable () -> Unit)? = null,
    /** Uninstall waits while this is true (a sign-in running). */
    val holdUninstall: Boolean = false,
)

/** A tool that updates from the Updates page: its row there, and Update on it. */
class ToolUpdate(val row: UpdateRow, val queued: Boolean, val blocked: Boolean, val onUpdate: () -> Unit)

/** [component]'s Updates row as a [ToolUpdate] that updates through the Updates flow, or null while it has no row. */
fun toolUpdate(context: Context, updates: UpdatesState, component: UpdateComponent): ToolUpdate? =
    updates.rows.firstOrNull { it.component == component }?.let {
        ToolUpdate(
            it,
            queued = component in updates.queued,
            blocked = updates.updating && updates.current != component,
            onUpdate = { Updates.update(context, component) },
        )
    }

/**
 * Settings → Tools → a tool (decision 64): one layout for every tool. In order, each only when [ToolModel.sections]
 * says it applies: the header (name, what it's for, Built in / Installed / Not installed) with the tool's own
 * cards ([about]), Version (installed and latest, Update through the Updates flow), Accounts ([accounts]), Storage,
 * and Install or Uninstall for optional tools. Built-in tools say they come with PocketRocket instead.
 */
@Composable
fun ToolPage(
    spec: ToolSpec,
    state: ToolState,
    icon: ToolGlyph,
    purpose: String,
    onBack: () -> Unit,
    install: ToolInstall? = null,
    update: ToolUpdate? = null,
    storageRows: List<Pair<String, String>> = emptyList(),
    accounts: (@Composable ColumnScope.() -> Unit)? = null,
    about: @Composable ColumnScope.() -> Unit = {},
) {
    val sections = ToolModel.sections(spec, state)
    val job = install?.job
    val jobShown = state.job != ToolJob.None && job != null
    val failed = state.job == ToolJob.InstallFailed || state.job == ToolJob.UninstallFailed
    var confirm by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable(failed) { mutableStateOf(failed) }

    MockPage {
        MockTopBar(spec.name, MockIcons.Back, onBack, titleSize = 17.sp)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (failed) 18.dp else 22.dp),
        ) {
            val sub = when {
                state.job == ToolJob.Installing || state.job == ToolJob.Uninstalling ->
                    job?.progress?.let { p -> listOfNotNull("$p%", job.detail).joinToString(" · ") } ?: job?.detail ?: purpose
                failed -> install?.failedLine ?: purpose
                else -> purpose
            }
            ToolHeader(spec.name, icon, sub, ToolModel.status(spec, state))

            if (jobShown && install != null && job != null) {
                ToolCard("STEPS") {
                    StepList(job, style = StepListStyle.Compact, upcoming = if (install.removing) install.removeSteps else install.installSteps)
                    // A failure after the last step has no failed step to show it under.
                    if (failed && job.steps.none { it.status == StepStatus.Failed }) {
                        install.outcome?.let { Text(it, style = mockText(12.sp), color = PrTheme.colors.redSoft) }
                    }
                }
                ToolLog(job.log, logOpen) { logOpen = !logOpen }
                if (!failed) Note(if (install.removing) "You can leave this page. The uninstall keeps going." else "You can leave this page. The install keeps going.")
            } else {
                about()
            }

            if (ToolSection.Version in sections) VersionSection(spec, state, update)
            if (ToolSection.Accounts in sections && accounts != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caps("ACCOUNTS")
                    accounts()
                }
            }
            if (ToolSection.Storage in sections) {
                val rows = listOfNotNull(
                    spec.downloadMb?.let { "Download" to "$it MB" },
                    ToolModel.sizeMb(spec, state)?.let { "On the phone" to "$it MB" },
                ) + storageRows
                ToolDetails(rows, label = "STORAGE")
            }
        }

        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp)) {
            when {
                ToolSection.Install !in sections -> Note("Comes with PocketRocket. Setup installs it, and it stays.")
                install == null -> Unit
                state.job == ToolJob.Installing || state.job == ToolJob.Uninstalling -> BusyButton(if (install.removing) "Uninstalling…" else "Installing…")
                install.blocked != null -> install.blocked.invoke()
                failed -> AccentButton("Retry", onClick = install.onRetry, height = 48.dp, radius = 12.dp)
                state.installed -> DangerButton("Uninstall", onClick = { if (!install.holdUninstall) confirm = true })
                else -> AccentButton("Install", onClick = install.onInstall, height = 48.dp, radius = 12.dp, enabled = state.checked)
            }
        }
    }

    if (confirm && install != null) {
        ConfirmSheet(
            title = install.confirmTitle,
            body = install.confirmBody,
            confirm = "Uninstall",
            onConfirm = {
                confirm = false
                install.onUninstall()
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Installed and latest versions; for a tool on the Updates page, its update's steps and Update. */
@Composable
private fun VersionSection(spec: ToolSpec, state: ToolState, update: ToolUpdate?) {
    val row = update?.row
    val rows = state.versions + listOfNotNull(
        if (spec.canUpdate) "Latest" to (state.latest ?: if (row?.checking == true) "Checking…" else "Not checked") else null,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolDetails(rows, label = "VERSION", monoValues = state.versions.map { it.first }.toSet() + listOfNotNull(state.latest?.let { "Latest" }))
        if (update == null || row == null) return@Column
        val job = row.job
        var logOpen by rememberSaveable(row.failed) { mutableStateOf(row.failed) }
        if ((row.running || row.failed) && job != null) {
            ToolCard("UPDATE") {
                StepList(job, style = StepListStyle.Compact, upcoming = row.steps)
                if (row.failed) row.outcome?.let { Text(it.message.trim(), style = mockText(12.sp), color = PrTheme.colors.redSoft) }
            }
            if (job.log.isNotEmpty() || row.running) ToolLog(job.log, logOpen) { logOpen = !logOpen }
        }
        when {
            row.running -> BusyButton("Updating…")
            update.queued -> BusyButton("Waiting for another update…")
            row.available && update.blocked -> BusyButton("Another update is running…")
            row.available -> AccentButton(if (row.failed) "Retry" else "Update to ${row.latest}", onClick = update.onUpdate, height = 44.dp, radius = 12.dp)
            row.latest != null -> Note("Up to date. Settings › Updates checks for every tool.")
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = mockText(11.sp).copy(lineHeight = 16.sp), color = PrTheme.colors.textMuted)
}
