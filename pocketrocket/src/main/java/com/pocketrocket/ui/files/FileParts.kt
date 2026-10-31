package com.pocketrocket.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.files.FileEntry
import com.pocketrocket.files.FileFormat
import com.pocketrocket.files.FileRules
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.git.CodeStyle
import com.pocketrocket.ui.git.gitColors
import com.pocketrocket.ui.theme.PrTheme

/** One entry of a menu: what it reads and what it does. [danger] draws it red (Delete). */
internal class MenuItem(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** A "⋮" button opening [items]; [open] lets a long-press elsewhere open the same menu. */
@Composable
internal fun MoreMenu(items: List<MenuItem>, open: Boolean, onOpen: (Boolean) -> Unit, description: String = "More") {
    val c = PrTheme.colors
    Box {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = description) { onOpen(true) }, contentAlignment = Alignment.Center) {
            Text("⋮", style = mockText(20.sp, color = c.textSoft))
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = if (item.danger) c.redSoft else c.text) },
                    onClick = { onOpen(false); item.onClick() },
                )
            }
        }
    }
}

/**
 * The path bar: "~ / projects / work / app" in mono, each part opening its folder, the current one brighter.
 * Scrolls sideways when long, and starts scrolled to the end.
 */
@Composable
internal fun PathBar(crumbs: List<FileRules.Crumb>, onOpen: (String) -> Unit) {
    val c = PrTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 18.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text("/", style = mockText(13.sp, color = c.textFaint, mono = true), modifier = Modifier.padding(horizontal = 2.dp))
            val current = index == crumbs.lastIndex
            Text(
                crumb.label,
                style = mockText(13.sp, color = if (current) c.text else c.textSoft, mono = true),
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !current) { onOpen(crumb.path) }.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A row of the folder list (mockup Browse): the folder or file glyph, the name in mono, its size or item count
 * and when it changed, and a "⋮" with [actions]. Tap opens it; long-press opens the menu too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(entry: FileEntry, first: Boolean, last: Boolean, actions: List<MenuItem>, onOpen: () -> Unit) {
    val c = PrTheme.colors
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = if (first) 14.dp else 0.dp, topEnd = if (first) 14.dp else 0.dp, bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.bar, shape).combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    entry.isLink -> "↪"
                    entry.isDir -> "▸"
                    else -> "≡"
                },
                color = if (entry.isDir) gitColors.type else c.textMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp),
            )
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    entry.name + if (entry.isDir) "/" else "",
                    style = CodeStyle, fontSize = 13.sp, color = if (entry.hidden) c.textSoft else c.text,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
                Text(FileFormat.details(entry), style = mockText(11.sp, color = c.textMuted), maxLines = 1)
            }
            MoreMenu(actions, menu, { menu = it }, description = "Actions for ${entry.name}")
        }
        if (!last) HorizontalDivider(color = c.raised)
    }
}
