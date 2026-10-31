package com.pocketrocket.ui.claudeconfig

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.MemoryFile
import com.pocketrocket.claudeconfig.MemoryPanel
import com.pocketrocket.claudeconfig.MemoryStamp
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.ui.accounts.Confirm
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.sessions.displayPath
import com.pocketrocket.ui.theme.PrTheme
import java.io.File

/** An account's `CLAUDE.md`, in its config dir: read by every session on that account. */
@Composable
fun AccountMemoryScreen(id: String, onBack: () -> Unit) {
    val entry = ClaudeAccounts.get(id) ?: run { LaunchedEffect(Unit) { onBack() }; return }
    MemoryEditor("Memory for ${entry.name}", File(entry.folder, MemoryFile.NAME), "Sessions on this account read this, in every folder.", onBack)
}

/** A folder's `CLAUDE.md`: read by sessions in that folder, whichever account they use. */
@Composable
fun FolderMemoryScreen(path: String, onBack: () -> Unit) {
    MemoryEditor("Memory for this folder", File(path, MemoryFile.NAME), "Sessions in this folder read this, on any account. In a repo, it may be shared through git.", onBack)
}

/**
 * Plain text and an explicit Save. Nothing is written otherwise. Leaving with unsaved text asks first, and a save that
 * finds the file changed writes nothing until told what to do. The text stays in `remember`, not `rememberSaveable`:
 * up to [MemoryFile.MAX_BYTES] would overflow saved instance state, and the activity handles rotation itself.
 */
@Composable
private fun MemoryEditor(title: String, file: File, note: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val panel = remember(file.path) { MemoryPanel(file, scope) }
    val state by panel.state.collectAsState()
    var leaving by remember(file.path) { mutableStateOf(false) }
    LaunchedEffect(panel) { panel.load() }

    val leave: () -> Unit = {
        if (state.dirty && !state.saving) leaving = true
        else if (!state.saving) onBack()
    }
    BackHandler(enabled = state.dirty || state.saving, onBack = leave)

    val c = PrTheme.colors
    MockPage {
        MockTopBar(
            title, MockIcons.Back, leave, titleSize = 17.sp, subtitle = displayPath(file.path), subtitleSize = 11.sp,
            trailing = {
                if (state.editable) {
                    TextButton(onClick = panel::save, enabled = state.canSave) {
                        Text(if (state.saving) "Saving…" else "Save", style = mockText(14.sp, color = if (state.canSave) c.accent else c.textMuted))
                    }
                }
            },
        )
        Column(
            Modifier.weight(1f).padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val line = when {
                state.loading -> "Opening…"
                state.refused != null -> state.refused
                state.problem != null -> state.problem
                state.justSaved -> "Saved. New sessions use it; a running session may not until it restarts."
                state.stamp.missing && !state.dirty -> "There's no ${MemoryFile.NAME} here yet. Saving creates it. $note"
                else -> note
            }
            Text(
                line.orEmpty(),
                style = mockText(12.sp, color = if (state.refused != null || state.problem != null) c.redSoft else c.textMuted),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.editable) {
                val shape = RoundedCornerShape(12.dp)
                BasicTextField(
                    value = state.text,
                    onValueChange = panel::edit,
                    readOnly = state.saving,
                    textStyle = mockText(13.sp, color = c.text, mono = true),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(12.dp),
                )
            }
        }
    }

    if (leaving) {
        Confirm(
            "Discard your changes?",
            "What you typed here hasn't been saved. The file stays as it was.",
            "Discard", onDismiss = { leaving = false }, onConfirm = onBack,
        )
    }
    state.conflict?.let { current -> ConflictDialog(current, onLoad = panel::load, onOverwrite = panel::overwrite, onKeep = panel::keepEditing) }
}

/** The file moved on while this was open: nothing has been written. Replacing the other edit is a choice made here. */
@Composable
private fun ConflictDialog(current: MemoryStamp, onLoad: () -> Unit, onOverwrite: () -> Unit, onKeep: () -> Unit) {
    val c = PrTheme.colors
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(if (current.missing) "The file was deleted" else "The file changed") },
        text = {
            Text(
                if (current.missing) "Something removed ${MemoryFile.NAME} while you were editing. Nothing was saved."
                else "Something else, maybe a Claude session, changed ${MemoryFile.NAME} while you were editing. Nothing was saved.",
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onKeep) { Text("Keep editing") }
                TextButton(onClick = onLoad) { Text(if (current.missing) "Discard mine" else "Discard mine, load theirs") }
                TextButton(onClick = onOverwrite) { Text(if (current.missing) "Save mine" else "Replace theirs with mine", color = c.redSoft) }
            }
        },
    )
}
