package com.pocketrocket.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.sessions.ChatItem

/**
 * A compact tool call (Session.dc.html): the tool name and its target in JetBrains Mono, then ✓ (or ✗, or a spinner
 * while it runs). Edits show their added and removed line counts. Tap for the full input and output.
 */
@Composable
fun ToolCallCard(tool: ChatItem.Tool) {
    val c = PrTheme.colors
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val counts = remember(tool.inputJson) { editCounts(tool.name, tool.inputJson) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tool.name, style = PrType.code, color = c.textMuted, maxLines = 1)
            Text(tool.summary, style = PrType.code, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (counts != null && tool.result != null && !tool.isError) {
                Text("+${counts.first}", style = PrType.code, color = c.green)
                Text("−${counts.second}", style = PrType.code, color = c.red)
                Text("›", style = PrType.small, color = c.textMuted)
            } else {
                ResultMark(tool)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeBox("Input", tool.inputJson)
                tool.result?.let { CodeBox(if (tool.isError) "Error" else "Output", it.lines().take(40).joinToString("\n")) }
            }
        }
    }
}

/** Lines added and removed by an Edit, MultiEdit or Write call, from its input; null for other tools. */
internal fun editCounts(name: String, inputJson: String): Pair<Int, Int>? {
    val input = runCatching { JSONObject(inputJson) }.getOrNull() ?: return null
    fun lines(text: String) = if (text.isEmpty()) 0 else text.trimEnd('\n').count { it == '\n' } + 1
    return when (name) {
        "Edit" -> lines(input.optString("new_string")) to lines(input.optString("old_string"))
        "MultiEdit" -> {
            val edits = input.optJSONArray("edits") ?: return null
            var added = 0
            var removed = 0
            for (i in 0 until edits.length()) {
                val edit = edits.optJSONObject(i) ?: continue
                added += lines(edit.optString("new_string"))
                removed += lines(edit.optString("old_string"))
            }
            added to removed
        }
        "Write" -> lines(input.optString("content")) to 0
        else -> null
    }
}

/** ✓ in green when done, ✗ in red when it failed, a small blue spinner while it runs. */
@Composable
private fun ResultMark(tool: ChatItem.Tool) {
    val c = PrTheme.colors
    when {
        tool.result == null -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.blue)
        tool.isError -> Text("✗", style = PrType.small, color = c.red)
        else -> Text("✓", style = PrType.small, color = c.green)
    }
}

@Composable
private fun CodeBox(label: String, text: String) {
    Column {
        Text(label, style = PrType.micro, color = PrTheme.colors.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        Box(
            Modifier.fillMaxWidth().background(PrTheme.colors.background, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(text, style = PrType.codeSmall.copy(lineHeight = 16.sp), color = PrTheme.colors.textSoft)
        }
    }
}
