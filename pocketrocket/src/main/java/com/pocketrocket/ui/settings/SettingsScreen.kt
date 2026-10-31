package com.pocketrocket.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.pocketrocket.ui.tools.ToolGlyph
import com.pocketrocket.ui.tools.ToolIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.assistant.AssistantPrefs
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.settings.ThemeMode
import com.pocketrocket.settings.ThemePrefs
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.RadioDot
import com.pocketrocket.ui.components.mock
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.backup.BackupRow
import com.pocketrocket.ui.updates.UpdatesRow
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.rememberTools
import com.pocketrocket.updates.Updates
import androidx.compose.runtime.produceState
import com.pocketrocket.health.Health
import com.pocketrocket.usage.RamUsage
import com.pocketrocket.usage.StorageUsage
import com.pocketrocket.usage.UsageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pocketrocket.ui.theme.PrTheme

/**
 * Settings (decision 88): five sections, ACCOUNTS, DEVELOP, ROCKET, THIS PHONE and APP, every item one [SettingsCard].
 * Health turns amber when a check needs attention (R15); Theme switches from the buttons on its card.
 */
@Composable
fun SettingsScreen(
    onClaudeAccounts: () -> Unit = {},
    onGitHubAccounts: () -> Unit = {},
    onTools: () -> Unit = {},
    onUpdates: () -> Unit = {},
    onBackup: () -> Unit = {},
    onAssistant: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = mock
    val mode by remember { ThemePrefs.mode(context) }.collectAsState()
    var pickingTheme by rememberSaveable { mutableStateOf(false) }
    val tools = rememberTools()
    // Installed versions and cached checks; all four are checked again when the last check is six hours old.
    val updates by Updates.state.collectAsState()
    val backupJob by com.pocketrocket.backup.BackupManager.job.collectAsState()
    LaunchedEffect(Unit) { Updates.onAppOpen(context) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Settings",
            style = mockText(22.sp, FontWeight.Bold, c.text, letterSpacing = (-0.3).sp),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 10.dp),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            AccountsSetting(onClaudeAccounts, onGitHubAccounts)
            Section("DEVELOP") {
                // "4 included · 2 add-ons" (decision 64), and the installed tools' marks (decision 87).
                SettingsCard(
                    tile = { IconTile(MockIcons.Wrench) },
                    title = "Tools",
                    line = ToolModel.summary(tools.map { (tool, state) -> tool.spec to state }),
                    onClick = onTools,
                    below = { ToolStrip(tools.filter { (_, state) -> state.installed }.map { (tool, _) -> tool.icon }) },
                )
                SettingsCard(
                    tile = { IconTile(MockIcons.Download) },
                    title = "Updates",
                    line = UpdatesRow.value(updates).removeSuffix(" ›"),
                    lineColor = UpdatesRow.color(updates),
                    onClick = onUpdates,
                )
            }
            Section("ROCKET") { AssistantCard(onAssistant) }
            Section("THIS PHONE") {
                HealthCard()
                UsageCard()
            }
            Section("APP") {
                SettingsCard(
                    tile = { IconTile(MockIcons.Auto) },
                    title = "Theme",
                    line = mode.label,
                    onClick = { pickingTheme = true },
                    end = { ThemeButtons(mode) { ThemePrefs.set(context, it) } },
                )
                SettingsCard(
                    tile = { IconTile(MockIcons.Cloud) },
                    title = "Backup",
                    line = BackupRow.value(backupJob).removeSuffix(" ›"),
                    lineColor = BackupRow.color(backupJob),
                    onClick = onBackup,
                )
                SettingsCard(tile = { IconTile(MockIcons.Info) }, title = "About", line = "Version, source and licences", onClick = onAbout)
            }
        }
    }

    if (pickingTheme) {
        AlertDialog(
            onDismissRequest = { pickingTheme = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { ThemePrefs.set(context, option); pickingTheme = false }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioDot(mode == option)
                            Text(option.label, style = mockText(14.sp, color = c.text))
                        }
                    }
                    Text("System follows your phone's dark theme setting.", style = mockText(12.sp, color = c.muted))
                }
            },
            confirmButton = { TextButton(onClick = { pickingTheme = false }) { Text("Done") } },
            containerColor = c.card,
            titleContentColor = c.text,
        )
    }
}

/** A Caps header over its cards. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caps(title)
        content()
    }
}

/** Theme's quick buttons: System, Light, Dark, the current one in accent. */
@Composable
private fun ThemeButtons(mode: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val c = mock
    Row(
        Modifier.background(c.bg, RoundedCornerShape(10.dp)).border(1.dp, c.rowBorder, RoundedCornerShape(10.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(ThemeMode.System to MockIcons.Auto, ThemeMode.Light to MockIcons.Sun, ThemeMode.Dark to MockIcons.Moon).forEach { (option, icon) ->
            val on = option == mode
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(if (on) c.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .semantics { contentDescription = option.label; selected = on }
                    .clickable(role = Role.RadioButton) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (on) c.accent else c.muted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** The installed tools' brand marks, small, in Tools' order. */
@Composable
private fun ToolStrip(icons: List<ToolGlyph>) {
    if (icons.isEmpty()) return
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        icons.forEach { ToolIcon(it, tile = 22.dp, glyph = 13.dp, corner = 6.dp) }
    }
}

/** A row of the APP list: label left, muted value right, a hairline under all but the last. */
@Composable
internal fun AppRow(label: String, value: String, divider: Boolean, valueColor: Color = mock.muted, onClick: (() -> Unit)? = null) {
    val c = mock
    Column {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 2.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = mockText(14.sp, color = c.text), modifier = Modifier.weight(1f))
            Text(value, style = mockText(14.sp, color = valueColor), maxLines = 1)
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = c.divider)
    }
}

/**
 * The card every Settings item uses (decision 88): a 36 dp tile, a title and a status line, and a trailing
 * "›" or an [end] control. [below] adds a line under the status (Tools' icon strip).
 */
@Composable
internal fun SettingsCard(
    tile: @Composable () -> Unit,
    title: String,
    line: String,
    trailing: String = "›",
    onClick: (() -> Unit)? = null,
    lineColor: Color = mock.muted,
    background: Color = mock.field,
    border: BorderStroke = BorderStroke(1.dp, mock.rowBorder),
    below: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
) {
    val c = mock
    MockBox(Modifier.fillMaxWidth(), radius = 14.dp, background = background, border = border, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tile()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = mockText(14.sp, FontWeight.SemiBold, c.text))
                Text(line, style = mockText(12.sp, color = lineColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
                below?.invoke()
            }
            if (end != null) end() else Text(trailing, style = mockText(12.sp, color = c.muted))
        }
    }
}

/** The orange letter tile ("C" for Claude). */
@Composable
internal fun LetterTile(letter: String) {
    val c = mock
    Box(Modifier.size(36.dp).background(c.accent.copy(alpha = 0.14f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(letter, style = mockText(15.sp, FontWeight.Bold, c.accent))
    }
}

/** A grey tile with a line icon (GitHub's branch, Health's pulse); orange when [accent], or in [tone] (Health's amber). */
@Composable
internal fun IconTile(icon: ImageVector, accent: Boolean = false, tone: Color? = null) {
    val c = mock
    val ink = tone ?: if (accent) c.accent else null
    Box(Modifier.size(36.dp).background(ink?.copy(alpha = if (tone != null) 0.16f else 0.14f) ?: c.rowBorder, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = ink ?: c.text, modifier = Modifier.size(18.dp))
    }
}

/** The way into Settings › Assistant: on (with its login) or off. */
@Composable
private fun AssistantCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val settings by remember { AssistantPrefs.settings(context) }.collectAsState()
    val accounts by ClaudeAccounts.state.collectAsState()
    val line = if (settings.enabled) listOfNotNull("On", settings.loginName(accounts)).joinToString(" · ") else "Off"
    SettingsCard(tile = { IconTile(MockIcons.Mic) }, title = "Assistant", line = line, onClick = onClick)
}

/** Usage, with one read of storage and memory each time Settings opens; only the Usage screen keeps refreshing. */
@Composable
private fun UsageCard() {
    val context = LocalContext.current
    val line by produceState("Reading…", context) {
        value = withContext(Dispatchers.IO) { UsageText.summary(StorageUsage.totalBytes(context), RamUsage.read(context).totalKb) }
    }
    SettingsCard(tile = { IconTile(MockIcons.Bars) }, title = "Usage", line = line, onClick = LocalOpenUsage.current)
}

/**
 * Health: amber with "N things need attention" when a check needs it (mockup Settings), plain otherwise.
 * Opening Settings re-runs the checks when the last run is over five minutes old.
 */
@Composable
private fun HealthCard() {
    val context = LocalContext.current
    val report by Health.report.collectAsState()
    LaunchedEffect(Unit) { Health.check(context, ifOlderThanMs = 5 * 60_000L) }
    val attention = report?.attention ?: 0
    val p = PrTheme.colors
    SettingsCard(
        tile = { IconTile(MockIcons.Pulse, tone = if (attention > 0) p.amber else null) },
        title = "Health",
        line = when {
            attention > 0 -> "$attention ${if (attention == 1) "thing needs" else "things need"} attention"
            report == null -> "Checking…"
            else -> "Everything checks out"
        },
        lineColor = if (attention > 0) p.amberText else mock.muted,
        background = if (attention > 0) p.amberSurface else mock.field,
        border = if (attention > 0) BorderStroke(1.5.dp, p.amber) else BorderStroke(1.dp, mock.rowBorder),
        onClick = LocalOpenHealth.current,
    )
}
