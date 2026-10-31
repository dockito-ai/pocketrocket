package com.pocketrocket.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.sp
import com.pocketrocket.auth.ClaudeUsage
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/**
 * The user's message: a bubble on the right, up to 82% wide, with its attachments above the text. Long-press shows Copy, then [menu]
 * (Edit, Retry) when given.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    text: String,
    attachments: List<Attachment> = emptyList(),
    menu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val c = PrTheme.colors
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    val actions = listOfNotNull(if (text.isNotEmpty()) "Copy" to { clipboard.setText(AnnotatedString(text)) } else null) + menu
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = maxWidth * 0.82f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .clip(shape)
                    .background(c.userBubble)
                    .combinedClickable(enabled = actions.isNotEmpty(), onClick = {}, onLongClick = { open = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (attachments.isNotEmpty()) BubbleAttachments(attachments)
                    if (text.isNotEmpty()) Text(text, style = PrType.body.copy(lineHeight = 20.3.sp), color = c.text)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    actions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                    }
                }
            }
        }
    }
}

/** Claude's reply: full-width text, no bubble, like a document. Long-press selects text to copy. */
@Composable
fun AssistantMessage(text: String) {
    SelectionContainer { MarkdownText(text, Modifier.fillMaxWidth()) }
}

/** A quiet centred line, for things like "Allowed Bash". */
@Composable
fun NoticeRow(text: String, byRocket: Boolean = false) {
    Text(
        text,
        style = PrType.caption,
        color = if (byRocket) PrTheme.colors.accent else PrTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** An error, in the mockup's red: a tinted card with a red outline. */
@Composable
fun ErrorRow(text: String) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().background(c.red.copy(alpha = 0.08f).compositeOver(c.card), shape).border(1.dp, c.redBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = c.redSoft, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(ClaudeUsage.localizeUtcResets(text), style = PrType.small, color = c.text)
    }
}

/** Three dots that ripple while Claude works ([label] says what, like Compacting). */
@Composable
fun WorkingRow(label: String = "Working") {
    val color = PrTheme.colors.blue
    val transition = rememberInfiniteTransition(label = "working")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(alpha).background(color, CircleShape))
        }
        Text(label, style = PrType.caption, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}
