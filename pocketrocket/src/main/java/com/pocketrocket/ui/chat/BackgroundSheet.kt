package com.pocketrocket.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.SessionState
import com.pocketrocket.sessions.agentRunning
import com.pocketrocket.sessions.backgroundShell
import com.pocketrocket.sessions.shellRunning
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/**
 * What runs beside the conversation (BackgroundTasks.html), from the counts on the status line: running agents and
 * background shells, each shell with Stop, then the shells that ended in this session, dimmed. A shell opens its
 * output ([onOpenShell]), an agent opens full screen ([onOpenAgent]); a running agent has Stop too.
 */
@Composable
fun BackgroundSheet(
    state: SessionState,
    onDismiss: () -> Unit,
    onOpenShell: (ChatItem.Tool) -> Unit,
    onOpenAgent: (ChatItem.Tool) -> Unit,
    onStop: (taskId: String) -> Unit,
) {
    val c = PrTheme.colors
    val tools = state.items.filterIsInstance<ChatItem.Tool>()
    val running = tools.filter { it.agentRunning || (it.backgroundShell && it.shellRunning) }
    val ended = tools.filter { it.backgroundShell && !it.shellRunning }.reversed()
    ChatSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Background", style = PrType.title, color = c.text)
            Text("  ·  ${running.size} running", style = PrType.body, color = c.textMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (running.isEmpty()) Text("Nothing running in the background.", style = PrType.body, color = c.textMuted)
            running.forEach { tool -> TaskRow(tool, onOpenShell, onOpenAgent, onStop) }
        }
        if (ended.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("FINISHED THIS SESSION", style = PrType.micro, fontWeight = FontWeight.SemiBold, color = c.textMuted)
                ended.forEach { tool -> TaskRow(tool, onOpenShell, onOpenAgent, onStop, modifier = Modifier.alpha(0.6f)) }
            }
        }
    }
}

@Composable
private fun TaskRow(
    tool: ChatItem.Tool,
    onOpenShell: (ChatItem.Tool) -> Unit,
    onOpenAgent: (ChatItem.Tool) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PrTheme.colors
    val shell = tool.shell
    val agent = tool.agent
    val tileShape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable { if (shell != null) onOpenShell(tool) else onOpenAgent(tool) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(c.iconTile, tileShape), contentAlignment = Alignment.Center) {
            Text(if (shell != null) ">_" else "A", style = PrType.code, color = if (shell != null) c.teal else c.lilac)
        }
        Column(Modifier.weight(1f)) {
            val title = if (shell != null) shellTitle(tool) else agent?.description.orEmpty()
            val sub = if (shell != null) shellCommand(tool) else "Agent" + (agent?.type?.let { " · $it" } ?: "")
            Text(title, style = PrType.body, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = PrType.codeSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            shell != null && tool.shellRunning -> {
                val now by tickingNow(true)
                Text(shell.elapsedMs(now)?.let(::agentClock).orEmpty(), style = PrType.codeSmall, color = c.textMuted)
                StopButton { onStop(shell.taskId) }
            }
            shell != null -> ShellMark(tool, shell, withExit = true)
            agent != null -> {
                val now by tickingNow(true)
                Text(agent.elapsedMs(true, now)?.let(::agentClock).orEmpty(), style = PrType.codeSmall, color = c.lilac)
                agent.taskId?.let { task -> StopButton { onStop(task) } }
            }
        }
    }
}

/** A small red-outlined Stop. */
@Composable
fun StopButton(onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Text(
        "Stop",
        style = PrType.small,
        fontWeight = FontWeight.Medium,
        color = c.redSoft,
        modifier = Modifier.clip(shape).border(1.dp, c.redBorder, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
