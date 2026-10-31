package com.pocketrocket.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.AgentRun
import com.pocketrocket.sessions.AgentStep
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.agentRunning
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * A Task/Agent tool call as a live card: what the subagent was asked to do, what it is doing now, its time, tokens and
 * tool calls (ticking while it runs, final once it ends), its own tool calls (nested, collapsed by default) and its
 * final report.
 */
@Composable
fun AgentCard(tool: ChatItem.Tool, agent: AgentRun) {
    val c = PrTheme.colors
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val running = tool.agentRunning
    val finished = !running
    val failed = tool.isError || agent.status == "failed" || agent.status == "killed"
    val stopped = agent.status == "stopped"
    val steps = agent.steps.size
    val status = when {
        running -> agent.activity ?: "Working"
        failed -> "Failed"
        stopped -> "Stopped"
        else -> "Done"
    }
    val statusColor = when {
        running -> c.blue
        failed -> c.red
        stopped -> c.textMuted
        else -> c.green
    }
    // A second's tick while it runs; a finished agent shows its final numbers.
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val numbers = agentNumbers(agent.elapsedMs(running, now), agent.tokens, agent.tools)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.border, shape)
            .clickable { expanded = !expanded }.animateContentSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Agent",
                style = PrType.micro,
                fontWeight = FontWeight.SemiBold,
                color = c.lilac,
                modifier = Modifier.background(c.lilac.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Text(agent.description, style = PrType.small, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = statusColor)
        }
        // Status, then the numbers: `Done · 1:12 · 12.2k tokens · 7 tools`. A long activity gives way to the numbers.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, style = PrType.caption, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (numbers.isNotEmpty()) Text(" · $numbers", style = PrType.caption, color = c.textMuted, maxLines = 1, softWrap = false)
        }
        if (steps > 0) {
            // Finished: the first steps then "+N more", as in the mockup. Running: the latest steps, with what came before.
            val limit = 2
            val shown = when {
                expanded -> agent.steps
                finished -> agent.steps.take(limit)
                else -> agent.steps.takeLast(limit)
            }
            val hidden = steps - shown.size
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(c.border))
                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hidden > 0 && !finished) StepText("+$hidden earlier", c.textMuted)
                    shown.forEach { StepRow(it) }
                    if (hidden > 0 && finished) StepText("+$hidden more", c.textMuted)
                }
            }
        }
        AnimatedVisibility(expanded) {
            val report = agent.summary ?: tool.result?.let(::handBack)
            if (report != null) {
                Box(Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MarkdownText(report)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: AgentStep) {
    val mark = when {
        !step.done -> " …"
        step.isError -> " ✗"
        else -> ""
    }
    StepText("${step.name} ${step.summary.takeIf { it != step.name }.orEmpty()}".trim() + mark, PrTheme.colors.textMuted)
}

@Composable
private fun StepText(text: String, color: Color) {
    Text(text, style = PrType.codeSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** `1:12 · 12.2k tokens · 7 tools`; each part only when known. */
internal fun agentNumbers(elapsedMs: Long?, tokens: Long?, tools: Int): String = listOfNotNull(
    elapsedMs?.let(::agentClock),
    tokens?.let { "${shortTokens(it)} tokens" },
    tools.takeIf { it > 0 }?.let { "$it ${if (it == 1) "tool" else "tools"}" },
).joinToString(" · ")

/** m:ss, or h:mm:ss from an hour. */
internal fun agentClock(ms: Long): String {
    val s = ms / 1_000
    return if (s >= 3_600) String.format(Locale.US, "%d:%02d:%02d", s / 3_600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** 950, 8.1k, 12.2k, 140k, 1.2M: one decimal where it still says something. */
internal fun shortTokens(n: Long): String = when {
    n >= 1_000_000 -> oneDecimal(n / 1_000_000.0) + "M"
    n >= 100_000 -> "${Math.round(n / 1_000.0)}k"
    n >= 1_000 -> oneDecimal(n / 1_000.0) + "k"
    else -> n.toString()
}

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

/** The tool result wraps the subagent's report in a frame; keep only the report. */
private fun handBack(result: String): String {
    val body = result.substringAfter("The report follows:\n", result)
    return body.substringBefore("\nagentId:").lines().joinToString("\n") { it.removePrefix("  ") }.trim()
}
