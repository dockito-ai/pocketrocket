package com.pocketrocket.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.pocketrocket.ui.theme.PrType

/**
 * Settings (mockup Settings): ACCOUNTS cards, the Assistant row, the Health row, and the APP list (Tools, Theme, Updates). The Health row turns amber when a check needs attention (R15).
 */
@Composable
fun SettingsScreen(
    onClaudeAccounts: () -> Unit = {},
    onGitHubAccounts: () -> Unit = {},
    onTools: () -> Unit = {},
    onUpdates: () -> Unit = {},
    onBackup: () -> Unit = {},
    onAssistant: () -> Unit = {},
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
            AssistantRow(onAssistant)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthRow()
                UsageRow()
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caps("APP", modifier = Modifier.padding(bottom = 6.dp))
                // "2 built in · 3 installed" (decision 64).
                AppRow("Tools", "${ToolModel.summary(tools.map { (tool, state) -> tool.spec to state })} ›", divider = true, onClick = onTools)
                AppRow("Theme", "${mode.label} ›", divider = true) { pickingTheme = true }
                AppRow("Updates", UpdatesRow.value(updates), divider = true, valueColor = UpdatesRow.color(updates), onClick = onUpdates)
                AppRow("Backup", BackupRow.value(backupJob), divider = false, valueColor = BackupRow.color(backupJob), onClick = onBackup)
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

/** The mockup's settings card: a 36 dp tile, a title and a line, and a trailing hint. */
@Composable
internal fun SettingsCard(
    tile: @Composable () -> Unit,
    title: String,
    line: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
    background: Color = mock.field,
    border: BorderStroke = BorderStroke(1.dp, mock.rowBorder),
) {
    val c = mock
    MockBox(Modifier.fillMaxWidth(), radius = 14.dp, background = background, border = border, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tile()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = mockText(14.sp, FontWeight.SemiBold, c.text))
                Text(line, style = mockText(12.sp, color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, style = mockText(12.sp, color = c.muted))
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

/** A grey tile with a line icon (GitHub's branch, Health's pulse); orange when [accent]. */
@Composable
internal fun IconTile(icon: ImageVector, accent: Boolean = false) {
    val c = mock
    Box(Modifier.size(36.dp).background(if (accent) c.accent.copy(alpha = 0.14f) else c.rowBorder, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (accent) c.accent else c.text, modifier = Modifier.size(18.dp))
    }
}

/** The way into Settings › Assistant: a plain settings card, on or off. */
@Composable
private fun AssistantRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val settings by remember { AssistantPrefs.settings(context) }.collectAsState()
    val accounts by ClaudeAccounts.state.collectAsState()
    val line = if (settings.enabled) listOfNotNull("On", settings.loginName(accounts)).joinToString(" · ") else "Off"
    SettingsCard(tile = { IconTile(MockIcons.Mic) }, title = "Assistant", line = line, trailing = "›", onClick = onClick)
}

/** The Usage row with one read of storage and memory each time Settings opens; only the Usage screen keeps refreshing. */
@Composable
private fun UsageRow() {
    val context = LocalContext.current
    val line by produceState("Reading…", context) {
        value = withContext(Dispatchers.IO) { UsageText.summary(StorageUsage.totalBytes(context), RamUsage.read(context).totalKb) }
    }
    SettingsCard(tile = { IconTile(MockIcons.Bars) }, title = "Usage", line = line, trailing = "›", onClick = LocalOpenUsage.current)
}

/**
 * The Health row: amber with "N things need attention" when a check needs it (mockup Settings), a plain
 * settings card otherwise. Opening Settings re-runs the checks when the last run is over five minutes old.
 */
@Composable
private fun HealthRow() {
    val context = LocalContext.current
    val open = LocalOpenHealth.current
    val report by Health.report.collectAsState()
    LaunchedEffect(Unit) { Health.check(context, ifOlderThanMs = 5 * 60_000L) }
    val attention = report?.attention ?: 0
    if (attention == 0) {
        SettingsCard(
            tile = { IconTile(MockIcons.Pulse) },
            title = "Health",
            line = if (report == null) "Checking…" else "Everything checks out",
            trailing = "›",
            onClick = open,
        )
        return
    }
    val c = PrTheme.colors
    MockBox(Modifier.fillMaxWidth(), radius = 14.dp, background = c.amberSurface, border = BorderStroke(1.5.dp, c.amber), onClick = open) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(c.amber.copy(alpha = 0.16f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(MockIcons.Pulse, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Health", style = PrType.body.copy(fontWeight = FontWeight.SemiBold, color = c.text, lineHeight = 18.sp))
                Text(
                    "$attention ${if (attention == 1) "thing needs" else "things need"} attention",
                    style = PrType.caption.copy(color = c.amberText),
                )
            }
            Text("›", style = PrType.body.copy(fontSize = 16.sp, color = c.textMuted))
        }
    }
}
