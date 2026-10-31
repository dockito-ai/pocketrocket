package com.pocketrocket.ui.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrocket.appupdate.ApkInstaller
import com.pocketrocket.appupdate.AppUpdater
import com.pocketrocket.ui.accounts.ago
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.ui.tools.BusyButton
import com.pocketrocket.ui.tools.ToolLog
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.UpdateRow
import com.pocketrocket.updates.Updates
import com.pocketrocket.updates.UpdatesState
import com.pocketrocket.updates.updateOrder

/** The Settings row's value (decision 41): "Up to date ›", "2 updates ›" in orange, "Updating… ›" in blue. */
object UpdatesRow {
    fun value(s: UpdatesState): String = when {
        s.updating -> "Updating… ›"
        s.available > 0 -> "${s.available} ${if (s.available == 1) "update" else "updates"} ›"
        s.allKnown -> "Up to date ›"
        s.checking -> "Checking… ›"
        else -> "Not checked ›"
    }

    @Composable
    fun color(s: UpdatesState): Color = when {
        s.updating -> PrTheme.colors.blue
        s.available > 0 -> PrTheme.colors.accent
        else -> PrTheme.colors.textMuted
    }
}

/**
 * Settings › Updates (docs/ux-mockup/Updates.dc.html): one row per component with its version, "Up to date"
 * or "Update to X" and Update; Check now for all four; Update all when more than one is out of date. A
 * running update opens its row to show its steps and live progress, as a tool page does. Full screen, no
 * bottom nav. The Claude Code row opens the Claude Code page (Switch back, Restart stale sessions).
 */
@Composable
fun UpdatesScreen(onBack: () -> Unit, onClaudeCode: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val s by Updates.state.collectAsState()
    // Asking Android first: the running sessions warning, then "Install unknown apps", before PocketRocket joins.
    var warnFor by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var awaitingPermission by rememberSaveable { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { Updates.onAppOpen(context, checkAfterMs = 10 * 60_000L) }

    fun go(names: List<String>) {
        val list = names.map(UpdateComponent::valueOf)
        if (UpdateComponent.PocketRocket in list && !ApkInstaller.canInstall(context)) {
            awaitingPermission = names
            context.startActivity(ApkInstaller.unknownAppsSettings(context))
        } else {
            Updates.update(context, list)
        }
    }
    fun start(list: List<UpdateComponent>) {
        val names = updateOrder(list).map { it.name }
        if (UpdateComponent.PocketRocket in list && AppUpdater.activeSessions() > 0) warnFor = names else go(names)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val waiting = awaitingPermission
            if (event == Lifecycle.Event.ON_RESUME && waiting != null) {
                awaitingPermission = null
                if (ApkInstaller.canInstall(context)) Updates.update(context, waiting.map(UpdateComponent::valueOf))
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    MockPage {
        MockTopBar("Updates", MockIcons.Back, onBack, titleSize = 17.sp) {
            val enabled = !s.checking && !s.updating
            Text(
                if (s.checking) "Checking…" else "Check now",
                style = mockText(14.sp, if (enabled) FontWeight.SemiBold else FontWeight.Medium),
                color = if (enabled) c.accent else c.textMuted,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { Updates.checkAll(context) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        s.checking -> "Checking…"
                        s.checkedAt > 0 -> "Checked ${ago(s.checkedAt)}"
                        else -> "Not checked yet"
                    },
                    style = mockText(12.sp), color = c.textMuted, modifier = Modifier.weight(1f),
                )
                val (summary, tint) = when {
                    s.updating -> "Updating ${s.done + 1} of ${s.total}" to c.blue
                    s.available > 0 -> "${s.available} ${if (s.available == 1) "update" else "updates"}" to c.accent
                    s.allKnown -> "Everything is up to date" to c.textMuted
                    else -> "" to c.textMuted
                }
                Text(summary, style = mockText(12.sp), color = tint)
            }

            s.rows.forEach { row ->
                UpdateTile(
                    row,
                    queued = row.component in s.queued,
                    blocked = s.updating && s.current != row.component,
                    checkedOnce = s.checkedAt > 0,
                    onUpdate = { start(listOf(row.component)) },
                    onOpen = if (row.component == UpdateComponent.ClaudeCode) onClaudeCode else null,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val foot = when {
                s.updating -> "You can leave this page. Updates keep going."
                s.available > 0 -> "Running sessions keep the version they started with until they restart."
                else -> "PocketRocket checks when the app opens. Check now looks again for all four."
            }
            Text(foot, style = mockText(11.sp).copy(lineHeight = 16.sp), color = c.textMuted)
            when {
                s.updating -> BusyButton("Updating…")
                s.available >= 2 -> AccentButton("Update all (${s.available})", onClick = { start(Updates.pending()) }, height = 48.dp, radius = 12.dp)
            }
        }
    }

    warnFor?.let { names ->
        val n = AppUpdater.activeSessions()
        AlertDialog(
            onDismissRequest = { warnFor = null },
            title = { Text("Update now?") },
            text = {
                Text(
                    (if (n == 1) "1 session is working." else "$n sessions are working.") +
                        " Installing PocketRocket restarts the app: they pause and resume after.",
                    style = mockText(14.sp), color = c.text,
                )
            },
            confirmButton = { TextButton(onClick = { warnFor = null; go(names) }) { Text("Update anyway") } },
            dismissButton = { TextButton(onClick = { warnFor = null }) { Text("Wait") } },
            containerColor = c.card,
            titleContentColor = c.text,
        )
    }
}

@Composable
private fun UpdateTile(row: UpdateRow, queued: Boolean, blocked: Boolean, checkedOnce: Boolean, onUpdate: () -> Unit, onOpen: (() -> Unit)?) {
    val c = PrTheme.colors
    val job = row.job
    val open = (row.running || row.failed) && job != null
    var logOpen by rememberSaveable(row.component, row.failed) { mutableStateOf(row.failed) }
    val hot = row.available && !row.running
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, if (hot) c.accent.copy(alpha = 0.22f) else c.iconTile, RoundedCornerShape(14.dp))
            .let { if (onOpen != null) it.clickable(onClick = onOpen) else it }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateIcon(row.component, busy = row.running)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.component.label, style = mockText(14.sp, FontWeight.SemiBold), color = c.text)
                SubLine(row, checkedOnce)
            }
            when {
                row.running -> Pill("Updating", c.blue)
                queued -> Pill("Next", c.textMuted, c.iconTile)
                row.failed && row.available && !blocked -> SmallButton("Retry", onUpdate)
                row.available && !blocked -> SmallButton("Update", onUpdate)
                row.available -> Unit
                row.installed == null -> Unit
                row.latest != null -> Pill("Up to date", c.green)
                row.checking -> Pill("Checking", c.textMuted, c.iconTile)
                checkedOnce -> Pill("Couldn't check", c.textMuted, c.iconTile)
            }
        }
        if (open && job != null) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                StepList(job, modifier = Modifier.padding(top = 12.dp), style = StepListStyle.Compact, upcoming = row.steps)
            }
            if (row.failed) row.outcome?.let { Text(it.message.trim(), style = mockText(12.sp), color = c.redSoft) }
            if (job.log.isNotEmpty() || row.running) ToolLog(job.log, logOpen) { logOpen = !logOpen }
        }
    }
}

/** "2.1.312", "2.1.312 · Update to 2.1.315" (orange), "Updating to 2.1.315 · 58%" (blue), or why there is none. */
@Composable
private fun SubLine(row: UpdateRow, checkedOnce: Boolean) {
    val c = PrTheme.colors
    val job = row.job
    when {
        row.running && job != null -> {
            val pct = job.progress?.let { " · $it%" }.orEmpty()
            Text("${row.jobTitle ?: "Updating"}$pct", style = mockText(12.sp), color = c.blue, maxLines = 1)
        }
        row.installed == null -> Text(
            if (row.component == UpdateComponent.PocketRocket || row.checking) "Checking…" else "Not installed. Run setup again.",
            style = mockText(12.sp), color = c.textMuted,
        )
        row.available -> Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(row.installed) }
                append(" · ")
                withStyle(SpanStyle(color = c.accent)) { append("Update to ${row.latest}") }
            },
            // Long versions (0.0.1-beta.26) wrap rather than lose "Update to X".
            style = mockText(12.sp), color = c.textMuted, maxLines = 2,
        )
        else -> Text(row.installed, style = mockText(12.sp, mono = true), color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun UpdateIcon(component: UpdateComponent, busy: Boolean) {
    val c = PrTheme.colors
    val orange = component == UpdateComponent.PocketRocket || component == UpdateComponent.ClaudeCode
    val tint = when {
        busy -> c.blue
        orange -> c.accent
        else -> c.text
    }
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (busy || orange) tint.copy(alpha = 0.14f) else c.iconTile),
        contentAlignment = Alignment.Center,
    ) {
        when (component) {
            UpdateComponent.ClaudeCode -> Text("C", style = mockText(15.sp, FontWeight.Bold), color = tint)
            else -> Icon(
                when (component) {
                    UpdateComponent.PocketRocket -> MockIcons.Rocket
                    UpdateComponent.Git -> MockIcons.Branch
                    else -> MockIcons.Doc
                },
                contentDescription = null, tint = tint, modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, background: Color = color.copy(alpha = 0.14f)) {
    Text(
        text,
        style = mockText(11.sp, FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The row's own button: 34 dp, orange. */
@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    val c = PrTheme.colors
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(c.accent).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(13.sp, FontWeight.SemiBold), color = c.onAccent, maxLines = 1)
    }
}
