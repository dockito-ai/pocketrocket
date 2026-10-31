package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.Unseen
import com.pocketrocket.ui.theme.status
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/**
 * A row of chips, one per session with its status dot, then a dashed + for a new session. The open session is filled
 * and outlined in its status colour. Long-press a chip to rename or end its session.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionSwitcher(
    sessions: List<Session>,
    current: Int,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onRename: (Session) -> Unit,
    onEnd: (Session) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PrTheme.colors
    val list = rememberLazyListState()
    LaunchedEffect(current) { list.animateScrollToItem(current.coerceAtLeast(0)) }
    val shape = RoundedCornerShape(10.dp)
    var menuFor by remember { mutableStateOf<String?>(null) }
    val unseen by Unseen.ids.collectAsState()
    LazyRow(
        state = list,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(sessions, key = { _, s -> s.id }) { index, session ->
            val state = session.state.collectAsState().value
            val selected = index == current
            val dot = c.status(state.shownStatus)
            Box {
                Row(
                    Modifier.clip(shape)
                        .then(if (selected) Modifier.background(c.raised).border(1.5.dp, dot, shape) else Modifier.border(1.dp, c.border, shape))
                        .combinedClickable(role = Role.Tab, onLongClickLabel = "Rename or end", onLongClick = { menuFor = session.id }) { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).background(dot, CircleShape))
                    Text(
                        state.title,
                        style = PrType.small,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) c.text else c.textSoft,
                        maxLines = 1,
                    )
                }
                // News you have not looked at yet: an orange badge on the chip's corner until you open it.
                if (session.id in unseen && !selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(10.dp)
                            .background(c.background, CircleShape).padding(2.dp).background(c.accent, CircleShape),
                    )
                }
                DropdownMenu(expanded = menuFor == session.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; onRename(session) })
                    DropdownMenuItem(text = { Text("End session") }, onClick = { menuFor = null; onEnd(session) })
                }
            }
        }
        item(key = "new") {
            Box(
                Modifier.clip(shape).dashedBorder(c.borderStrong, 10.dp).clickable(role = Role.Button, onClickLabel = "New session", onClick = onNew)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(ChatIcons.Plus, contentDescription = "New session", tint = c.textMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A 1dp dashed outline, like CSS `border: 1px dashed`. */
private fun Modifier.dashedBorder(color: androidx.compose.ui.graphics.Color, radius: androidx.compose.ui.unit.Dp) = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
    )
}
