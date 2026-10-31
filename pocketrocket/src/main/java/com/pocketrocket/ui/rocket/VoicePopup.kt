package com.pocketrocket.ui.rocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrocket.assistant.Conversation
import com.pocketrocket.assistant.VoiceState
import com.pocketrocket.assistant.forLockedPhone
import com.pocketrocket.assistant.phoneIsLocked
import com.pocketrocket.ui.chat.AssistantMessage
import com.pocketrocket.ui.chat.MicVisual
import com.pocketrocket.ui.chat.UserBubble
import com.pocketrocket.ui.chat.WorkingRow
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.delay

private const val IDLE_CLOSE_MS = 8_000L
private const val LONG_CLOSE_MS = 20_000L

/**
 * The popup, for Hey Rocket, Android's assist request and the earbud long-press: a small chat card at the bottom of the screen,
 * so the app or lock screen stays visible behind. It listens as soon as it's in front, shows what was heard and the reply in
 * the chat's own text, says the reply, and closes itself a few seconds after it has nothing left to do. Tapping outside closes
 * it. An allow unlocks if it must, then moves to the approval screen.
 */
@Composable
fun VoicePopup(byWakeWord: Boolean, asked: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val turns by remember { Conversation.turns(context) }.collectAsState()
    // Only what's said in this popup shows here; the rest of the conversation is on the tab, behind the lock.
    val before = remember { turns.lastOrNull() }
    val latest = turns.lastOrNull()?.takeIf { it !== before }

    val loop = rememberRocketLoop(
        rationale = "PocketRocket listens after you call Rocket, and while Rocket is talking so you can interrupt it. " +
            "Android's speech recognition turns what you say into text, on the phone where it can.",
        canAskForMic = { !phoneIsLocked(context) },
    )
    val voice by loop.model.collectAsState()
    val level by loop.level.collectAsState()

    // Waking the screen can pause and resume the popup, which stops the first listen, so it listens again while nothing's come of it.
    val lifecycle = LocalLifecycleOwner.current
    val answered by rememberUpdatedState(latest != null)
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.currentStateFlow.collect { state ->
            val now = loop.model.value
            if (state == Lifecycle.State.RESUMED && now.state == VoiceState.Idle && now.notice == null && !answered) loop.tapMic()
        }
    }
    // Called again while it's open.
    LaunchedEffect(asked) { if (asked > 0 && loop.model.value.state == VoiceState.Idle) loop.tapMic() }

    val reply = latest?.reply?.let { if (phoneIsLocked(context)) it.forLockedPhone() else it }
    val quiet = voice.state == VoiceState.Idle
    LaunchedEffect(voice.state, latest, voice.notice) {
        if (!quiet || latest?.pending != null) return@LaunchedEffect
        delay(if (reply?.card != null) LONG_CLOSE_MS else IDLE_CLOSE_MS)
        onClose()
    }

    val top = when (voice.state) {
        VoiceState.Listening -> if (byWakeWord && voice.heard.isBlank()) "Heard \"Hey Rocket\"" else "Listening"
        VoiceState.Thinking -> "Thinking"
        VoiceState.Speaking -> "Speaking"
        VoiceState.Idle -> "Rocket"
    }

    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(shape).background(c.card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(top, style = mockText(13.sp, FontWeight.Medium, c.textMuted))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    voice.state == VoiceState.Listening -> Text(voice.heard.ifEmpty { "Say what you need" }, style = PrType.body, color = if (voice.heard.isEmpty()) c.textMuted else c.text)
                    latest != null -> {
                        if (latest.question.isNotBlank()) UserBubble(latest.question)
                        if (latest.pending != null) WorkingRow(latest.reply.text.removeSuffix("."))
                        else reply?.let { AssistantMessage(ReplyText.markdown(it)) }
                        latest.note?.takeIf { quiet }?.let { Text(it, style = PrType.caption, color = c.textMuted) }
                    }
                    voice.notice != null -> Text(voice.notice.orEmpty().removeSuffix(" You can still type."), style = PrType.body, color = c.text)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(c.surface).clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MockIcons.Close, contentDescription = "Close", tint = c.text, modifier = Modifier.size(22.dp))
                }
                if (voice.state != VoiceState.Thinking) {
                    MicVisual(
                        listening = voice.state == VoiceState.Listening,
                        level = level,
                        onClick = loop::tapMic,
                        modifier = Modifier.size(52.dp),
                        icon = if (voice.state == VoiceState.Speaking) MockIcons.Stop else MockIcons.Mic,
                        label = when (voice.state) {
                            VoiceState.Speaking -> "Stop speaking"
                            VoiceState.Listening -> "Stop listening"
                            else -> "Talk to Rocket"
                        },
                    )
                }
            }
        }
    }
}
