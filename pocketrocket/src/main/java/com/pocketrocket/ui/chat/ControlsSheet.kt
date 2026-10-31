package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.pocketrocket.claudeconfig.SetupChoices
import com.pocketrocket.claudeconfig.SetupPanel
import com.pocketrocket.claudeconfig.SetupState
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.ui.claudeconfig.ChoicePill
import com.pocketrocket.sessions.PermissionMode
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.SessionStatus

/**
 * The session's Claude controls, from the status line: mode, model and effort, context with Compact / Rewind / Stop,
 * and the commands the CLI reports. Everything goes to the running CLI through its control protocol. Opened from the
 * chat box's model chip ([modelFirst]), the model and effort come first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsSheet(
    session: Session,
    state: SessionState,
    onDismiss: () -> Unit,
    onCommand: (String) -> Unit,
    onRewind: (ChatItem.User) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSetup: () -> Unit,
    setup: SetupPanel,
    modelFirst: Boolean = false,
) {
    val settings by setup.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    ChatSheet(onDismiss) {
        if (picking) {
            RewindPicker(state, onPick = { onRewind(it); onDismiss() }, onBack = { picking = false })
        } else {
            if (!modelFirst) ModeSection(state.mode) { session.setMode(it) }
            ModelSection(state) { session.setModel(it) }
            EffortSection(state, settings, onPick = setup::pickEffort)
            if (modelFirst) ModeSection(state.mode) { session.setMode(it) }
            ContextSection(
                state,
                onCompact = { session.compact(); onDismiss() },
                onRewind = { picking = true },
                onStop = { session.interrupt(); onDismiss() },
            )
            SheetButton("Status and connections…", Modifier.fillMaxWidth(), enabled = true, onClick = onOpenStatus)
            SheetButton("Style, effort, skills and settings…", Modifier.fillMaxWidth(), enabled = true, onClick = onOpenSetup)
            CommandsSection(state) { onCommand(it); onDismiss() }
        }
    }
}

/** Four mode cards, two per row; the selected one tinted and outlined in orange. */
@Composable
private fun ModeSection(current: PermissionMode, onPick: (PermissionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("MODE")
        PermissionMode.entries.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { mode ->
                    SheetTile(mode.label, mode.description, mode == current, Modifier.weight(1f).fillMaxHeight()) { onPick(mode) }
                }
            }
        }
    }
}

/** The models the CLI offers in a dropdown: one row with the current model, opening a menu of all of them. */
@Composable
private fun ModelSection(state: SessionState, onPick: (String) -> Unit) {
    val c = PrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("MODEL")
        if (state.models.isEmpty()) {
            Text("Claude Code lists its models once it has started.", style = PrType.caption, color = c.textMuted)
            return@Column
        }
        // "Default" is the account's default model; the named entries are what can be picked.
        val options = state.models.filter { it.value != "default" }
        val current = options.firstOrNull { it.resolvedModel == state.model }
        SheetDropdown(
            title = current?.displayName ?: state.model ?: "Default",
            note = current?.description,
            options = options.map { option -> SheetOption(option.displayName, option.description, option == current) { onPick(option.value) } },
        )
    }
}

/** The model's thinking levels as pills, when it has any; the pick lasts for this session (as in the setup sheet). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortSection(state: SessionState, setup: SetupState, onPick: (String) -> Unit) {
    val levels = SetupChoices.effortLevels(state.models, setup.settings?.appliedModel ?: state.model)
    if (levels.isEmpty()) return
    val current = setup.settings?.appliedEffort
    val managed = setup.settings?.isManaged(SetupChoices.EFFORT_KEY) == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("EFFORT", current)
        if (managed) {
            Text("Managed by your organization.", style = PrType.caption, color = PrTheme.colors.textMuted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                levels.forEach { level -> ChoicePill(level, on = level == current, enabled = !setup.busy) { onPick(level) } }
            }
        }
        setup.notice?.let { Text(it, style = PrType.caption, color = if (setup.noticeIsProblem) PrTheme.colors.redSoft else PrTheme.colors.textMuted) }
    }
}

/** How full the context window is, then Compact, Rewind… and Stop. */
@Composable
private fun ContextSection(state: SessionState, onCompact: () -> Unit, onRewind: () -> Unit, onStop: () -> Unit) {
    val c = PrTheme.colors
    val context = state.context
    val working = state.status == SessionStatus.Working || state.status == SessionStatus.WaitingForPermission
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("CONTEXT", context?.let { u -> contextText(u.used, u.window).replace("/", " of ") })
        val share = context?.window?.takeIf { it > 0 }?.let { (context.used.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
        val fill = contextColor(context?.used ?: 0, context?.window, c.blue)
        Box(Modifier.fillMaxWidth().height(8.dp).background(c.border, RoundedCornerShape(4.dp))) {
            if (share > 0f) Box(Modifier.fillMaxWidth(share).height(8.dp).background(fill, RoundedCornerShape(4.dp)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton("Compact", Modifier.weight(1f), enabled = !working && !state.compacting, onClick = onCompact)
            SheetButton("Rewind…", Modifier.weight(1f), enabled = !working && state.items.any { it is ChatItem.User && it.uuid != null }, onClick = onRewind)
            SheetButton("Stop", Modifier.weight(1f), enabled = working, danger = true, onClick = onStop)
        }
    }
}

/** The CLI's commands as pills; tapping one puts it in the chat box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandsSection(state: SessionState, onPick: (String) -> Unit) {
    if (state.commands.isEmpty()) return
    val c = PrTheme.colors
    var all by remember { mutableStateOf(false) }
    val shown = if (all) state.commands else state.commands.take(12)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("COMMANDS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { command ->
                CommandPill("/${command.name}", c.text) { onPick(command.name) }
            }
            if (!all && state.commands.size > shown.size) {
                CommandPill("${state.commands.size - shown.size} more…", c.textMuted) { all = true }
            }
        }
    }
}

@Composable
private fun CommandPill(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = PrType.codeSmall,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(PrTheme.colors.raised)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** Pick which of your messages to rewind to; the newest first. */
@Composable
private fun RewindPicker(state: SessionState, onPick: (ChatItem.User) -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("REWIND TO")
        Text(
            "Claude forgets that message and everything after it; its text comes back in the chat box. Files are not changed.",
            style = PrType.caption,
            color = PrTheme.colors.textMuted,
        )
        state.items.filterIsInstance<ChatItem.User>().filter { it.uuid != null }.asReversed().take(12).forEach { message ->
            Text(
                message.text,
                style = PrType.body,
                color = PrTheme.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(PrTheme.colors.userBubble)
                    .clickable { onPick(message) }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        SheetButton("Back", Modifier.fillMaxWidth(), enabled = true, onClick = onBack)
    }
}
