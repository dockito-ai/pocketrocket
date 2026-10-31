package com.pocketrocket.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketrocket.sessions.AgentState
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.Subagents
import com.pocketrocket.sessions.agentState
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/**
 * One subagent full screen, view only (decision 78), shaped like a shell's output screen: its type and task, state,
 * time, tokens and tool calls in the header; then the prompt it was given, its tool calls and messages as the main chat
 * shows them, live, and its final answer once it hands one back. Copy all takes the lot as text. Stop is Claude Code's
 * own `stop_task` for this agent's task, which ends the agent alone and lets the turn go on.
 */
@Composable
fun AgentScreen(tool: ChatItem.Tool, onStop: (taskId: String) -> Unit, onDismiss: () -> Unit) {
    val run = tool.agent ?: return
    val state = tool.agentState ?: return
    val c = PrTheme.colors
    val context = LocalContext.current
    val running = state == AgentState.Running
    val now by tickingNow(running)
    val numbers = agentNumbers(run.elapsedMs(running, now), run.tokens, run.tools)
    val final = Subagents.finalAnswer(tool)
    val list = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 4)
        }
    }
    // Follow new steps while the end is in view.
    LaunchedEffect(run.log.size, final != null) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0 && atEnd) list.animateScrollToItem(count - 1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Back", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ChatIcons.Back, contentDescription = "Back", tint = c.textSoft, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentBadge()
                        run.type?.let { Text(it, style = PrType.codeSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Text(run.description, style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(agentStateLabel(state, run), style = PrType.caption, color = agentStateColor(state), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (numbers.isNotEmpty()) Text(" · $numbers", style = PrType.caption, color = c.textMuted, maxLines = 1, softWrap = false)
                    }
                }
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                run.prompt?.let { prompt -> item(key = "prompt") { PromptBox(tool.id, prompt) } }
                if (run.dropped > 0) item(key = "dropped") { NoticeRow(Subagents.earlier(run.dropped)) }
                items(run.log, key = { it.id }) { entry ->
                    when (entry) {
                        is ChatItem.Assistant -> AssistantMessage(entry.text)
                        is ChatItem.Tool -> ToolCallCard(entry)
                        else -> Unit
                    }
                }
                if (running) item(key = "working") { WorkingRow(run.activity ?: "Working") }
                if (!running && run.log.isEmpty() && final == null) {
                    item(key = "nothing") { NoticeRow("Its steps weren't seen in this session.") }
                }
                final?.let { answer -> item(key = "final") { FinalAnswer(answer) } }
            }
            Row(
                Modifier.fillMaxWidth().background(c.bar).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (running) "Live · view only" else "View only", style = PrType.caption, color = c.textMuted, modifier = Modifier.weight(1f))
                OutlineButton("Copy all", c.text, c.borderStrong) {
                    copyText(context, run.description, Subagents.transcript(tool), "Copied the agent")
                }
                val task = run.taskId
                if (running && task != null) OutlineButton("Stop", c.redSoft, c.redBorder) { onStop(task) }
            }
        }
    }
}

/** The prompt the agent was given; a long one shows its first lines until tapped. */
@Composable
private fun PromptBox(key: String, prompt: String) {
    val c = PrTheme.colors
    val long = prompt.length > PROMPT_SHORT_CHARS || prompt.lines().size > PROMPT_SHORT_LINES
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.border, shape)
            .then(if (long) Modifier.clickable(onClickLabel = if (open) "Show less" else "Show all") { open = !open } else Modifier)
            .animateContentSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("PROMPT", style = PrType.micro, fontWeight = FontWeight.SemiBold, color = c.textMuted)
        if (long && !open) {
            Text(prompt.trim(), style = PrType.small, color = c.textSoft, maxLines = PROMPT_SHORT_LINES, overflow = TextOverflow.Ellipsis)
        } else {
            SelectionContainer { Text(prompt.trim(), style = PrType.small, color = c.textSoft) }
        }
        if (long) Text(if (open) "Show less" else "Show all", style = PrType.caption, color = c.accent)
    }
}

/** What the agent handed back, set apart from its steps. */
@Composable
private fun FinalAnswer(text: String) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.green.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("FINAL ANSWER", style = PrType.micro, fontWeight = FontWeight.SemiBold, color = c.green)
        AssistantMessage(text)
    }
}

private const val PROMPT_SHORT_LINES = 6
private const val PROMPT_SHORT_CHARS = 420
