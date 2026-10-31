package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.SettingRow
import com.pocketrocket.claudeconfig.SetupState
import com.pocketrocket.claudeconfig.SkillInfo
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/** The skills as listed, the first few until asked for the rest. Editing a skill stays in the terminal or chat. */
@Composable
internal fun SkillsList(state: SetupState) {
    val c = PrTheme.colors
    when {
        state.skillsUnsupported -> Note("This version of Claude Code doesn't list its skills here. In a terminal, /skills shows them.")
        state.skillsProblem != null -> Text("Couldn't list skills: ${state.skillsProblem}", style = mockText(12.sp, color = c.redSoft))
        state.skills.isEmpty() -> Note("No skills of your own. Claude Code's built-in ones aren't listed here.")
        else -> {
            var all by remember { mutableStateOf(false) }
            val shown = if (all) state.skills else state.skills.take(SHOWN_AT_FIRST)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { SkillRow(it) }
                if (!all && state.skills.size > shown.size) {
                    TextButton(onClick = { all = true }) { Text("${state.skills.size - shown.size} more", style = mockText(13.sp, color = c.accent)) }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(skill: SkillInfo) {
    val c = PrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(skill.displayName, style = mockText(13.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(skill.stateLabel, style = mockText(12.sp, color = c.textMuted))
        }
        skill.description?.let { Text(it, style = mockText(12.sp, color = c.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        val line = listOfNotNull(skill.sourceLabel, "${skill.tokens} tokens", skill.lockedBy?.let { "locked by $it" }).joinToString(" · ")
        Text(line, style = mockText(11.sp, color = c.textMuted))
    }
}

/** Effective values, where each came from, and how many stay hidden. Read-only. */
@Composable
internal fun SettingsList(state: SetupState) {
    val c = PrTheme.colors
    val view = state.settings
    when {
        state.settingsUnsupported -> Note("This version of Claude Code doesn't report its settings here. In a terminal, /config shows them.")
        state.settingsProblem != null -> Text("Couldn't read settings: ${state.settingsProblem}", style = mockText(12.sp, color = c.redSoft))
        view != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.rows.forEach { SettingLine(it) }
            if (view.errorCount > 0) {
                Text("Claude Code found a problem in the settings files. In a terminal, /doctor says what.", style = mockText(12.sp, color = c.redSoft))
            }
            if (view.hiddenCount > 0) {
                val count = if (view.hiddenCount == 1) "1 other setting is" else "${view.hiddenCount} others are"
                Note("$count not shown here, since settings such as env and hooks can hold secrets.")
            }
            if (view.managedKeys.isNotEmpty()) Note("Your organization manages some settings. Those can't be changed on this phone.")
        }
    }
}

@Composable
private fun SettingLine(row: SettingRow) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(row.label, style = mockText(13.sp, color = c.text), modifier = Modifier.weight(1f))
        Column(Modifier.weight(1f)) {
            Text(row.value, style = mockText(12.sp, color = c.text, mono = true), textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (row.managed) "Managed by your organization" else row.source,
                style = mockText(11.sp, color = if (row.managed) c.amber else c.textMuted),
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun Note(text: String) = Text(text, style = mockText(12.sp, color = PrTheme.colors.textMuted))

private const val SHOWN_AT_FIRST = 12
