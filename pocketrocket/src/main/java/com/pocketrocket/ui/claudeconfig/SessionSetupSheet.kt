package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.SessionMcpHost
import com.pocketrocket.claudeconfig.SetupChoices
import com.pocketrocket.claudeconfig.SetupPanel
import com.pocketrocket.claudeconfig.SetupState
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.ui.chat.ChatSheet
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * What can be set for one session, from its controls sheet. Everything goes over the session's own process, which this
 * never starts. A pick lasts for the session; saving it is a separate button that says where it goes.
 */
@Composable
fun SessionSetupSheet(session: Session, state: SessionState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panel = remember(session.id) { SetupPanel(SessionMcpHost(context.applicationContext, session), scope) }
    val alive by session.alive.collectAsState()
    val setup by panel.state.collectAsState()
    val c = PrTheme.colors

    LaunchedEffect(alive) { if (alive) panel.refresh() }

    ChatSheet(onDismiss) {
        if (!alive) Note("Claude Code isn't running in this session. These show once it starts.")
        if (setup.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                Note("Asking Claude Code…")
            }
        }
        setup.notice?.let { Text(it, style = mockText(12.sp, color = if (setup.noticeIsProblem) c.redSoft else c.text)) }

        if (setup.loaded) {
            StyleSection(setup, panel)
            EffortSection(setup, panel, SetupChoices.effortLevels(state.models, setup.settings?.appliedModel ?: state.model))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Heading("SKILLS")
            if (setup.loaded) SkillsList(setup)
            if (setup.loaded && setup.canReloadSkills) {
                OutlineButton("Reload skills", onClick = panel::reloadSkills, enabled = !setup.busy && alive, modifier = Modifier.fillMaxWidth())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Heading("SETTINGS")
            if (setup.loaded) SettingsList(setup)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSection(setup: SetupState, panel: SetupPanel) {
    val current = SetupChoices.currentStyle(setup.settings)
    val managed = setup.settings?.isManaged(SetupChoices.OUTPUT_STYLE_KEY) == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("OUTPUT STYLE", current)
        when {
            managed -> Note("Managed by your organization.")
            setup.styles.isEmpty() -> Note("This version of Claude Code doesn't list its styles.")
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    setup.styles.forEach { style -> ChoicePill(style, on = style == current, enabled = !setup.busy) { panel.pickStyle(style) } }
                }
                Note("A pick lasts for this session and isn't saved.")
                OutlineButton("Save $current for this folder", onClick = { panel.saveStyleForFolder(current) }, enabled = !setup.busy, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortSection(setup: SetupState, panel: SetupPanel, levels: List<String>) {
    val current = setup.settings?.appliedEffort
    val managed = setup.settings?.isManaged(SetupChoices.EFFORT_KEY) == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("EFFORT", current)
        when {
            managed -> Note("Managed by your organization.")
            levels.isEmpty() -> Note("Claude Code doesn't list effort levels for this model.")
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    levels.forEach { level -> ChoicePill(level, on = level == current, enabled = !setup.busy) { panel.pickEffort(level) } }
                }
                Note("A pick lasts for this session and isn't saved.")
                if (current != null && current in levels) {
                    OutlineButton("Save $current for this account and model", onClick = { panel.saveEffortForAccount(current) }, enabled = !setup.busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** One option as a pill, the current one tinted and outlined in orange like the mode cards. */
@Composable
internal fun ChoicePill(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Text(
        label,
        style = mockText(13.sp, color = c.text),
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f).clip(shape)
            .background(if (on) c.accentTint else c.raised)
            .border(1.dp, if (on) c.accent else c.raised, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
