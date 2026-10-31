package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pocketrocket.assistant.Approvals
import com.pocketrocket.assistant.AssistantPrefs
import com.pocketrocket.assistant.SensitiveActions
import com.pocketrocket.sessions.Draft
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import com.pocketrocket.claudeconfig.SessionMcpHost
import com.pocketrocket.claudeconfig.SetupChoices
import com.pocketrocket.claudeconfig.SetupPanel
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.pocketrocket.sessions.ChatItem
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.ui.claudeconfig.SessionSetupSheet
import com.pocketrocket.ui.claudeconfig.SessionStatusSheet

/** One session's chat: the conversation, the permission card when Claude asks, and the chat box. */
@Composable
fun ChatPage(session: Session, modifier: Modifier = Modifier, active: Boolean = true) {
    val state by session.state.collectAsState()
    val list = rememberLazyListState()
    val context = LocalContext.current
    val working = state.status == SessionStatus.Working || state.status == SessionStatus.Starting
    var controls by remember { mutableStateOf(false) }
    var controlsAtModel by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(false) }
    var openShell by remember { mutableStateOf<String?>(null) }
    var openAgent by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Draft?>(null) }
    val rows = state.items.size + if (working) 1 else 0
    val dictator = rememberDictator()
    // Settings over the session's own process, for the effort in the status line and in the controls.
    val scope = rememberCoroutineScope()
    val setupPanel = remember(session.id) { SetupPanel(SessionMcpHost(context.applicationContext, session), scope) }
    val alive by session.alive.collectAsState()
    val settings by setupPanel.state.collectAsState()
    LaunchedEffect(alive, state.model, setup) {
        if (alive && !state.demo && !setup) setupPanel.refreshSettings()
    }

    // Follow the conversation as it grows: animate for new items, jump (no animation per token) while text streams.
    val last = state.items.lastOrNull()
    val streamingLength = (last as? ChatItem.Assistant)?.takeIf { it.streaming }?.text?.length
    LaunchedEffect(rows, if (streamingLength != null) last.id else last) {
        list.followToEnd(animate = true)
    }
    // A permission or plan card takes room from the list; keep the latest line in view above it.
    LaunchedEffect(state.permission?.requestId) {
        if (state.permission != null) { delay(250); list.followToEnd(animate = true) }
    }
    LaunchedEffect(streamingLength) {
        if (streamingLength != null && !list.isScrollInProgress) list.followToEnd(animate = false)
    }

    val c = PrTheme.colors
    DictationOverlay(dictator, modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.items.isEmpty() && !working) item { EmptyChat() }
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.User -> {
                        // Edit and Retry rewind the conversation, so they need the message's id in Claude Code's
                        // transcript; while Claude works they stop it first.
                        val rewindable = item.uuid != null && !state.demo
                        val menu = listOfNotNull(
                            if (rewindable) "Edit" to { session.edit(item) } else null,
                            if (rewindable) "Retry" to { session.retry(item) } else null,
                        )
                        UserBubble(item.text, item.attachments, menu = menu)
                    }
                    is ChatItem.Assistant -> AssistantMessage(item.text)
                    is ChatItem.Tool -> if (item.agent != null) AgentCard(item, item.agent, onOpen = { openAgent = item.id }) else ToolCallCard(
                        item, onOpenOutput = { openShell = item.id }, onMoveToBackground = { session.moveToBackground(item.id) },
                    )
                    is ChatItem.Error -> ErrorRow(item.text)
                    is ChatItem.Notice -> NoticeRow(item.text, item.byRocket)
                }
            }
            if (working) item(key = "working") { WorkingRow(if (state.compacting) "Compacting" else "Working") }
            state.permission?.let { pending ->
                item(key = "permission:" + pending.requestId) {
                    if (pending.isPlan) {
                        PlanCard(pending, onAnswer = { approve, acceptEdits -> session.answerPlan(approve, acceptEdits) })
                    } else {
                        PermissionCard(pending, onAnswer = { allow, always ->
                            // A recognised sensitive command takes the fingerprint here too, or Review would be the way round it.
                            if (allow && SensitiveActions.isRecognisedSensitive(pending) && AssistantPrefs.settings(context).value.enabled) {
                                Approvals.allowPermission(context, session.id, pending.requestId, listen = false)
                            } else {
                                session.answerPermission(allow, always)
                            }
                        })
                    }
                }
            }
        }
        // The composer strip: chat box, then the status line (Session.dc.html: #141821, 8/12/10 padding, 8 gap).
        Column(
            Modifier.fillMaxWidth().background(c.bar).drawTopBorder(c.border).navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Sent while Claude works: waits here until Claude Code reads it, then joins the chat.
            AnimatedVisibility(state.queued.isNotEmpty()) { QueuedMessages(state.queued, onSendNow = session::sendNow) }
            // An Edit stays open to Undo for a few seconds, until the CLI is told.
            AnimatedVisibility(state.rewind != null) { UndoBar(onUndo = session::undoEdit) }
            // Claude Code's guess at the next message: tap sends it, long-press puts it in the box to edit.
            val suggestion = state.suggestion?.takeIf { !working && state.permission == null && state.rewind == null && state.queued.isEmpty() }
            AnimatedVisibility(suggestion != null) {
                SuggestionChip(
                    suggestion.orEmpty(),
                    onSend = { session.send(suggestion.orEmpty()) },
                    onEdit = { picked = Draft(suggestion.orEmpty(), (picked?.key ?: 0) + 1); session.dropSuggestion() },
                )
            }
            Composer(
                state.id, working = working, onStop = session::interrupt,
                onSend = session::send,
                commands = state.commands, draft = state.draft, insert = picked, active = active,
                dictator = dictator,
            )
            StatusLine(
                state,
                model = modelChipText(
                    state.modelName, settings.settings?.appliedEffort,
                    SetupChoices.effortLevels(state.models, settings.settings?.appliedModel ?: state.model),
                ),
                onOpenModel = { controlsAtModel = true; controls = true },
                onOpenControls = { controlsAtModel = false; controls = true }, onShowBackground = { background = true },
                onSwitchMode = { session.setMode(state.mode.tapNext) },
            )
        }
    }
    }
    // Leaving this chat (swiping to another session) keeps what was said, in this chat's box.
    LaunchedEffect(active) { if (!active) dictator.keep() }

    if (controls) {
        ControlsSheet(
            session, state,
            onDismiss = { controls = false; controlsAtModel = false },
            onCommand = { name -> picked = Draft("/$name ", (picked?.key ?: 0) + 1) },
            onRewind = session::edit,
            onOpenStatus = { controls = false; status = true },
            onOpenSetup = { controls = false; setup = true },
            setup = setupPanel,
            modelFirst = controlsAtModel,
        )
    }
    if (background) {
        BackgroundSheet(
            state, onDismiss = { background = false },
            onOpenShell = { openShell = it.id },
            onOpenAgent = { openAgent = it.id },
            onStop = session::stopTask,
        )
    }
    // Looked up on each change, so the screen follows the shell to its end.
    (state.items.firstOrNull { it.id == openShell } as? ChatItem.Tool)?.let { tool ->
        ShellOutputScreen(tool, onStop = session::stopTask, onDismiss = { openShell = null })
    }
    (state.items.firstOrNull { it.id == openAgent } as? ChatItem.Tool)?.takeIf { it.agent != null }?.let { tool ->
        AgentScreen(tool, onStop = session::stopTask, onDismiss = { openAgent = null })
    }
    if (status) SessionStatusSheet(session, state, onDismiss = { status = false })
    if (setup) SessionSetupSheet(session, state, onDismiss = { setup = false })
}

/** One suggested reply, as a pill at the left above the chat box: "↳ go ahead". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionChip(text: String, onSend: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Row(modifier.fillMaxWidth()) {
        Text(
            "↳  $text",
            style = PrType.body,
            color = c.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(shape).background(c.accentSurface).border(1.dp, c.accentBorder, shape)
                .combinedClickable(onClick = onSend, onLongClick = onEdit, onClickLabel = "Send", onLongClickLabel = "Edit first")
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Shown while an Edit can still be taken back, before the CLI is told (Session.edit). */
@Composable
private fun UndoBar(onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.raised).border(1.dp, c.border, shape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Rewound", style = PrType.body, color = c.text, modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo) { Text("Undo", style = PrType.body, color = c.accent) }
    }
}

/** Messages Claude hasn't read yet, as Claude Code shows them under its prompt. */
@Composable
private fun QueuedMessages(queued: List<ChatItem.User>, onSendNow: () -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.raised).border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClickLabel = "Post now", onClick = onSendNow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Queued · tap to post now", style = PrType.micro, color = c.textMuted)
        queued.forEach { message ->
            val text = message.text.ifEmpty { "📎 " + message.attachments.joinToString { it.name } }
            Text(text, style = PrType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyChat() {
    Text(
        "What should Claude work on?",
        style = PrType.title,
        color = PrTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
    )
}

/** A 1dp line along the top edge, like CSS `border-top`. */
internal fun Modifier.drawTopBorder(color: Color) = drawBehind {
    drawRect(color, size = Size(size.width, 1.dp.toPx()))
}

/**
 * Scrolls to the very end of the list, so the bottom of a tall last item (a long reply) is in view, not its top.
 * Waits a frame so the layout reflects the latest items.
 */
internal suspend fun LazyListState.followToEnd(animate: Boolean) {
    withFrameNanos { }
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return
    if (lastVisible.index == total - 1) {
        val overflow = (lastVisible.offset + lastVisible.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
        if (overflow > 0f) if (animate) animateScrollBy(overflow) else scrollBy(overflow)
    } else {
        if (animate) animateScrollToItem(total - 1)
        // An offset past the item's end is clamped by the list to "bottom of the last item at the bottom of the screen".
        scrollToItem(total - 1, END_OFFSET)
    }
}

private const val END_OFFSET = 1_000_000
