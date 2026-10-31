package com.pocketrocket.ui.rocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.assistant.Conversation
import com.pocketrocket.assistant.Router
import com.pocketrocket.assistant.Turn
import com.pocketrocket.assistant.VoiceModel
import com.pocketrocket.assistant.VoiceState
import com.pocketrocket.assistant.WakeWord
import com.pocketrocket.assistant.askRocket
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.chat.AssistantMessage
import com.pocketrocket.ui.chat.Composer
import com.pocketrocket.ui.chat.ComposerVoice
import com.pocketrocket.ui.chat.MicVisual
import com.pocketrocket.ui.chat.UserBubble
import com.pocketrocket.ui.chat.WorkingRow
import com.pocketrocket.ui.chat.drawTopBorder
import com.pocketrocket.ui.chat.followToEnd
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

private const val HOLD_TAB = "tab"

/**
 * The Rocket tab (decision 60): a plain chat like a session's, with the same bubbles, replies and chat box. It answers when
 * asked and says nothing on its own (decision 59). Typed questions get text; one asked with the mic is also answered aloud.
 * The conversation is the one the popup shares; Clear conversation is in the quick controls.
 */
@Composable
fun RocketScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val turns by remember { Conversation.turns(context) }.collectAsState()
    var controls by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()

    val loop = rememberRocketLoop(
        rationale = "PocketRocket listens only after you tap the mic, and while Rocket is talking so you can interrupt it. " +
            "Android's speech recognition turns what you say into text, on the phone where it can.",
    )
    val voice by loop.model.collectAsState()
    val level by loop.level.collectAsState()
    // Hey Rocket lets go of the mic while the tab listens or talks.
    LaunchedEffect(voice.state) { if (voice.state == VoiceState.Idle) WakeWord.hold(HOLD_TAB, false) }
    DisposableEffect(Unit) { onDispose { WakeWord.hold(HOLD_TAB, false) } }

    fun talk() {
        if (voice.state == VoiceState.Idle) WakeWord.hold(HOLD_TAB, true)
        loop.tapMic()
    }

    fun ask(question: String) {
        if (question.isBlank()) return
        loop.cancel()
        askRocket(context, question)
    }

    val last = turns.lastOrNull()
    LaunchedEffect(turns.size, last?.pending, last?.reply) { list.followToEnd(animate = true) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rocket", style = mockText(18.sp, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { controls = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MockIcons.Sliders, contentDescription = "Quick controls", tint = c.textSoft, modifier = Modifier.size(20.dp))
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turns.isEmpty()) item(key = "empty") { EmptyRocket(onAsk = ::ask) }
            itemsIndexed(turns) { _, turn -> TurnRows(turn) }
        }
        voice.notice?.let { Text(it, style = PrType.caption, color = c.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        Column(
            Modifier.fillMaxWidth().background(c.bar).drawTopBorder(c.border).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        ) {
            Composer(
                "rocket", working = false, onStop = {},
                onSend = { text, _ -> ask(text) },
                placeholder = "Ask Rocket",
                attachable = false,
                voice = ComposerVoice(
                    busy = voice.state == VoiceState.Listening || voice.state == VoiceState.Speaking,
                    hint = hint(voice),
                ) { modifier ->
                    MicVisual(
                        listening = voice.state == VoiceState.Listening,
                        level = level,
                        onClick = ::talk,
                        modifier = modifier,
                        icon = if (voice.state == VoiceState.Speaking) MockIcons.Stop else MockIcons.Mic,
                        label = when (voice.state) {
                            VoiceState.Listening -> "Stop listening"
                            VoiceState.Speaking -> "Stop speaking"
                            else -> "Talk to Rocket"
                        },
                    )
                },
            )
        }
    }

    if (controls) QuickControlsSheet(
        onDismiss = { controls = false },
        onMemory = { controls = false; navigate(Routes.ASSISTANT_MEMORY) },
        onAllSettings = { controls = false; navigate(Routes.ASSISTANT) },
    )
}

/** What the empty chat box says while the mic is busy. */
private fun hint(voice: VoiceModel): String? = when (voice.state) {
    VoiceState.Listening -> voice.heard.ifBlank { "Listening…" }
    VoiceState.Speaking -> "Speaking · tap to stop"
    else -> null
}

/** His question as his bubble, then the reply as the session chat shows Claude's: full width, code inline, copyable. */
@Composable
private fun TurnRows(turn: Turn) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (turn.question.isNotBlank()) UserBubble(turn.question)
        if (turn.pending != null) WorkingRow(turn.reply.text.removeSuffix(".").removeSuffix("…"))
        else AssistantMessage(ReplyText.markdown(turn.reply))
        turn.note?.let { Text(it, style = PrType.caption, color = PrTheme.colors.textMuted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyRocket(onAsk: (String) -> Unit) {
    val c = PrTheme.colors
    val sessions by SessionManager.sessions.collectAsState()
    val states = sessions.map { it.state.collectAsState().value }
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("What do you need?", style = PrType.title, color = c.textMuted, textAlign = TextAlign.Center)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Router.suggestions(states).forEach { Chip(it) { onAsk(it) } }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(19.dp)
    Box(
        Modifier.height(38.dp).clip(shape).border(1.dp, c.border, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(13.sp, color = c.text), maxLines = 1)
    }
}
