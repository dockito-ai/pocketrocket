package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.auth.ClaudeCli
import com.pocketrocket.claudeconfig.ClaudeConfigProbe
import com.pocketrocket.claudeconfig.MemoryFile
import com.pocketrocket.claudeconfig.ProbeMcpHost
import com.pocketrocket.claudeconfig.SetupPanel
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.ui.accounts.QuietCard
import com.pocketrocket.ui.accounts.SectionLabel
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * The account's memory, skills and settings. Skills and settings wait for the button, like Connections, and run in the
 * same empty folder, so they are the account's and no folder's. Nothing here changes anything but the memory file.
 */
@Composable
internal fun AccountSetupSection(entry: ClaudeAccountEntry, onOpenMemory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panel = remember(entry.id) {
        SetupPanel(ProbeMcpHost(context.applicationContext, ClaudeConfigProbe.neutralFolder, ClaudeCli.environment(entry.configDir)), scope)
    }
    val state by panel.state.collectAsState()
    val c = PrTheme.colors

    QuietCard(onClick = onOpenMemory) {
        SectionLabel("Memory")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit this account's ${MemoryFile.NAME}", style = mockText(14.sp, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
            Text("›", style = mockText(14.sp, color = c.textMuted))
        }
    }

    QuietCard {
        SectionLabel("Skills and settings")
        if (!state.loaded && state.settingsProblem == null) {
            Text("See this account's skills and settings. Starts Claude Code for a moment; no message is sent and no usage is counted.", style = mockText(13.sp, color = c.textMuted))
        }
        if (state.settingsProblem != null || state.skillsProblem != null || state.loaded) {
            SkillsList(state)
            SettingsList(state)
            Note("A folder's own skills and settings show in a session there. Output style and effort are picked in a session's controls.")
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                Text("Asking Claude Code…", style = mockText(12.sp, color = c.textMuted))
            } else {
                OutlineButton(if (state.loaded) "Check again" else "Show skills and settings", onClick = panel::refresh, modifier = Modifier.weight(1f))
            }
        }
    }
}
