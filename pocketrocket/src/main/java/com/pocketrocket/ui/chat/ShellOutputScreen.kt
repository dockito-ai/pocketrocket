package com.pocketrocket.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.sessions.ShellOutput
import com.pocketrocket.sessions.shellRunning
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A background shell's output, full screen (BackgroundTasks.html): its last [ShellOutput.TAIL_LINES] lines, read again
 * each second while it runs and followed to the bottom unless scrolled up ("↓ Follow output" goes back). Copy all
 * takes the whole file; Stop ends the shell while it runs.
 */
@Composable
fun ShellOutputScreen(tool: ChatItem.Tool, onStop: (taskId: String) -> Unit, onDismiss: () -> Unit) {
    val run = tool.shell ?: return
    val c = PrTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running = tool.shellRunning
    val lines by shellTail(run.outputFile, running, ShellOutput.TAIL_LINES)
    val list = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 4)
        }
    }
    val shown = lines.orEmpty()
    // Follow new lines while the end is in view.
    LaunchedEffect(shown.size, shown.lastOrNull()) {
        if (shown.isNotEmpty() && (atEnd || list.firstVisibleItemIndex == 0)) list.scrollToItem(shown.size - 1, Int.MAX_VALUE / 2)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Back", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ChatIcons.Close, contentDescription = "Back", tint = c.textSoft, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(shellTitle(tool), style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$ " + shellCommand(tool), style = PrType.codeSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ShellMark(tool, run, withExit = true)
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(c.codeBackground)) {
                LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
                    if (lines == null) item { Text("Waiting for output…", style = PrType.codeSmall, color = c.textMuted) }
                    itemsIndexed(shown) { _, line -> Text(line.ifEmpty { " " }, style = PrType.codeSmall, color = lineColor(line)) }
                }
                if (!atEnd && shown.isNotEmpty()) {
                    val shape = RoundedCornerShape(16.dp)
                    Text(
                        "↓ Follow output",
                        style = PrType.small,
                        color = c.textSoft,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(shape).background(c.raised).border(1.dp, c.borderStrong, shape)
                            .clickable { scope.launch { list.scrollToItem(shown.size - 1, Int.MAX_VALUE / 2) } }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().background(c.bar).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (running) "Live · last ${ShellOutput.TAIL_LINES} lines" else "${shown.size} lines",
                    style = PrType.caption, color = c.textMuted, modifier = Modifier.weight(1f),
                )
                OutlineButton("Copy all", c.text, c.borderStrong) {
                    val path = run.outputFile ?: return@OutlineButton
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { ShellOutput.all(path) }
                        if (text != null) copy(context, shellTitle(tool), text)
                    }
                }
                if (running) OutlineButton("Stop", c.redSoft, c.redBorder) { onStop(run.taskId) }
            }
        }
    }
}

@Composable
private fun OutlineButton(label: String, color: Color, border: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        style = PrType.body,
        color = color,
        modifier = Modifier.clip(shape).border(1.dp, border, shape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

/** Warnings amber, errors red, success green: a glance is enough to see how a build went. */
@Composable
private fun lineColor(line: String): Color {
    val c = PrTheme.colors
    return when {
        line.startsWith("[exited with code") -> c.textMuted
        warning.containsMatchIn(line) -> c.amber
        error.containsMatchIn(line) -> c.red
        success.containsMatchIn(line) -> c.green
        else -> c.textSoft
    }
}

private val warning = Regex("""^w: |\bwarning\b""", RegexOption.IGNORE_CASE)
private val error = Regex("""^e: |\berror\b|\bFAILED\b|\bFAILURE\b""", RegexOption.IGNORE_CASE)
private val success = Regex("""BUILD SUCCESSFUL|\bPASSED\b|\bready in\b""")

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied the output", Toast.LENGTH_SHORT).show()
}
