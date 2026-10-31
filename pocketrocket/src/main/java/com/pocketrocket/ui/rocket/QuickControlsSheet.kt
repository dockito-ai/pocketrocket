package com.pocketrocket.ui.rocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.assistant.AssistantBrain
import com.pocketrocket.assistant.AssistantPrefs
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.sessions.SessionReducer
import com.pocketrocket.ui.chat.ChatSheet
import com.pocketrocket.ui.chat.SheetButton
import com.pocketrocket.ui.chat.SheetDropdown
import com.pocketrocket.ui.chat.SheetHeading
import com.pocketrocket.ui.chat.SheetOption
import com.pocketrocket.ui.chat.SheetTile
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * Rocket's quick controls, from the sliders button: Hey Rocket and Speaker, the Claude login, the conversation (how full, Compact,
 * Clear conversation) and his memory notes.
 */
@Composable
fun QuickControlsSheet(onDismiss: () -> Unit, onMemory: () -> Unit, onAllSettings: () -> Unit) {
    val context = LocalContext.current
    val settings by remember { AssistantPrefs.settings(context) }.collectAsState()
    val accounts by ClaudeAccounts.state.collectAsState()
    val wakeWord = rememberWakeWordSwitch()
    ChatSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeading("LISTENING AND SPEAKING")
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTile("Hey Rocket", wakeWord.view.note, wakeWord.view.on, Modifier.weight(1f).fillMaxHeight(), minHeight = 76.dp, onClick = wakeWord.toggle)
                SheetTile(
                    "Speaker", if (settings.speaker) "On" else "Headphones only", settings.speaker, Modifier.weight(1f).fillMaxHeight(), minHeight = 76.dp,
                ) { AssistantPrefs.update(context) { it.copy(speaker = !it.speaker) } }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeading("CLAUDE LOGIN")
            SheetDropdown(
                title = settings.loginLabel(accounts),
                note = "Rocket's questions count against this login",
                options = settings.loginChoices(accounts).map { (id, label) ->
                    SheetOption(label, "", id == settings.loginId || (id == null && accounts.get(settings.loginId) == null)) {
                        AssistantPrefs.update(context) { it.copy(loginId = id) }
                    }
                },
            )
        }
        ConversationControls(onCleared = onDismiss)
        SheetButton("Memory notes", Modifier.fillMaxWidth(), onClick = onMemory)
        SheetButton("All assistant settings…", Modifier.fillMaxWidth(), onClick = onAllSettings)
    }
}

/** How full Rocket's conversation with Claude is, Compact, and Clear conversation, which asks first. Memory notes stay. */
@Composable
private fun ConversationControls(onCleared: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val session by AssistantBrain.session.collectAsState()
    val state = session?.state?.collectAsState()?.value
    val used = state?.context
    val window = used?.window?.takeIf { it > 0 }
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeading("CONVERSATION")
        if (window != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.iconTile)) {
                Box(Modifier.fillMaxWidth((used.used.toFloat() / window).coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
        Text(
            when {
                window != null -> "${SessionReducer.tokens(used.used)} of ${SessionReducer.tokens(window)} tokens"
                used != null -> "${SessionReducer.tokens(used.used)} tokens"
                else -> "Not started since the app opened"
            },
            style = mockText(12.5.sp, mono = window != null, color = c.textSoft),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton("Compact", Modifier.weight(1f)) { AssistantBrain.compact(context) }
            SheetButton("Clear conversation", Modifier.weight(1f), danger = true) { confirm = true }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear conversation?") },
            text = { Text("Everything you and Rocket said goes, here and in Claude's memory of it. Rocket starts fresh. Its memory notes stay.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; AssistantBrain.startFresh(context); onCleared() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = c.card,
            titleContentColor = c.text,
        )
    }
}
