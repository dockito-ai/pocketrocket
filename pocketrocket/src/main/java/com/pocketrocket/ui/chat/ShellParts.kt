package com.pocketrocket.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.ShellOutput
import com.pocketrocket.sessions.ShellRun
import com.pocketrocket.sessions.shellRunning
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A foreground shell offers "Move to background" once it has run this long. */
const val MOVE_AFTER_MS = 10_000L

/** The wall clock, ticking each second while [running]; frozen otherwise. */
@Composable
fun tickingNow(running: Boolean): State<Long> = produceState(System.currentTimeMillis(), running) {
    value = System.currentTimeMillis()
    while (running) {
        delay(1_000)
        value = System.currentTimeMillis()
    }
}

/**
 * The last lines of a shell's output file, read again every [everyMs] while [running] (and once more after it ends).
 * Null until the file can be read.
 */
@Composable
fun shellTail(path: String?, running: Boolean, lines: Int, everyMs: Long = 1_000): State<List<String>?> =
    produceState<List<String>?>(null, path, running) {
        if (path == null) return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { ShellOutput.tail(path, lines) } ?: value
            if (!running) break
            delay(everyMs)
        }
    }

/** What a Bash call says it does ("Build debug APK"), else its summary (the command). */
fun shellTitle(tool: ChatItem.Tool): String =
    runCatching { JSONObject(tool.inputJson).optString("description") }.getOrNull()?.takeIf { it.isNotBlank() } ?: tool.summary

/** The command itself, for the Background sheet and the output screen. */
fun shellCommand(tool: ChatItem.Tool): String =
    runCatching { JSONObject(tool.inputJson).optString("command") }.getOrNull()?.takeIf { it.isNotBlank() } ?: tool.summary

/** How a shell's state reads: "background · 1:02", "✓ 2:14", "✗ exit 1 · 0:08", "stopped · 5:13". */
fun shellState(run: ShellRun, running: Boolean, now: Long, withExit: Boolean = false): String {
    val time = run.elapsedMs(now)?.let(::agentClock)
    return when {
        running -> listOfNotNull(if (run.background) "background" else null, time).joinToString(" · ").ifEmpty { "running" }
        run.stopped -> listOfNotNull("stopped", time).joinToString(" · ")
        run.failed -> listOfNotNull("✗ exit ${run.exitCode ?: "?"}", time).joinToString(" · ")
        else -> listOfNotNull(if (withExit) "✓ exit 0" else "✓", time).joinToString(" · ")
    }
}

@Composable
fun shellColor(run: ShellRun, running: Boolean): Color {
    val c = PrTheme.colors
    return when {
        running -> c.blue
        run.stopped -> c.textMuted
        run.failed -> c.red
        else -> c.green
    }
}

/** The right end of a shell's row: a spinner while it runs, then its state in its colour. */
@Composable
fun ShellMark(tool: ChatItem.Tool, run: ShellRun, withExit: Boolean = false) {
    val running = tool.shellRunning
    val now by tickingNow(running)
    val color = shellColor(run, running)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = color)
        Text(shellState(run, running, now, withExit), style = PrType.caption, color = color, maxLines = 1, softWrap = false)
    }
}

/** The latest line a background shell printed, under its row, with the quiet left rule agent steps use. */
@Composable
fun ShellTailLine(run: ShellRun, running: Boolean) {
    val tail by shellTail(run.outputFile, running, lines = 1, everyMs = 2_000)
    val last = tail?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val c = PrTheme.colors
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.border))
        Text(last, style = PrType.codeSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}

/** "Move to background": the turn goes on and the command keeps running (ctrl+b in the terminal). */
@Composable
fun MoveToBackgroundButton(onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Text(
        "⤓  Move to background",
        style = PrType.small,
        color = c.textSoft,
        modifier = Modifier.clip(shape).border(1.dp, c.borderStrong, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
