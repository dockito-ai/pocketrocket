package com.pocketrocket.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrColors
import com.pocketrocket.ui.theme.PrTheme

/*
 * Pieces of Settings → Tools and a tool's page (docs/ux-mockup/Tools.dc.html). A tool is its status
 * (a [ToolStatus]) plus its own page; these are shared by every tool.
 */

/** What a tool's status line, icon tint and pill say: idle grey, busy blue, installed green, failed red, update orange. */
enum class ToolTone { Idle, Busy, Installed, Failed, Update }

/** A tool's one-line status on its tile ("Installed · 840 MB") and the short form for its pill and Settings ("Installed"). */
data class ToolStatus(val line: String, val short: String, val tone: ToolTone)

private fun PrColors.tone(tone: ToolTone): Color = when (tone) {
    ToolTone.Idle -> textMuted
    ToolTone.Busy -> blue
    ToolTone.Installed -> green
    ToolTone.Failed -> redSoft
    ToolTone.Update -> accent
}

/** The icon square: grey when idle, tinted with the status colour otherwise. */
@Composable
fun ToolIcon(icon: ImageVector, tone: ToolTone, big: Boolean = false) {
    val c = PrTheme.colors
    val idle = tone == ToolTone.Idle
    Box(
        Modifier.size(if (big) 48.dp else 36.dp).clip(RoundedCornerShape(if (big) 14.dp else 10.dp))
            .background(if (idle) c.iconTile else c.tone(tone).copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (idle) c.text else c.tone(tone), modifier = Modifier.size(if (big) 22.dp else 18.dp))
    }
}

/** One tile on the Tools list: icon, name, status line, chevron. */
@Composable
fun ToolTile(name: String, icon: ImageVector, status: ToolStatus, onClick: () -> Unit) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.iconTile, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolIcon(icon, status.tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = mockText(14.sp, FontWeight.SemiBold), color = c.text)
            Text(status.line, style = mockText(12.sp), color = if (status.tone == ToolTone.Idle || status.tone == ToolTone.Installed) c.textMuted else c.tone(status.tone))
        }
        Text("›", style = mockText(14.sp), color = c.textMuted)
    }
}

/** The top of a tool page: big icon, name, a sub line, and the status pill. */
@Composable
fun ToolHeader(name: String, icon: ImageVector, sub: String, status: ToolStatus) {
    val c = PrTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ToolIcon(icon, status.tone, big = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = mockText(15.sp, FontWeight.SemiBold), color = c.text)
            Text(sub, style = mockText(12.sp), color = c.textMuted)
        }
        val tint = c.tone(status.tone)
        Text(
            status.short,
            style = mockText(11.sp, FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            modifier = Modifier.clip(CircleShape).background(if (status.tone == ToolTone.Idle) c.iconTile else tint.copy(alpha = 0.14f))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

/** A card with a small capitals label ("WHAT IT DOES"). */
@Composable
fun ToolCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    val c = PrTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(1.dp, c.iconTile, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Caps(label)
        content()
    }
}

/** Card body text (13 px, line height 1.5). */
@Composable
fun ToolText(text: String) {
    Text(text, style = mockText(13.sp).copy(lineHeight = 19.5.sp), color = PrTheme.colors.textSoft)
}

/** A bulleted list inside a card. */
@Composable
fun ToolBullets(items: List<String>) {
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = mockText(13.sp).copy(lineHeight = 20.sp), color = PrTheme.colors.textSoft)
                Text(item, style = mockText(13.sp).copy(lineHeight = 20.sp), color = PrTheme.colors.textSoft)
            }
        }
    }
}

/** Key and value rows with a hairline between them, under an optional label. */
@Composable
fun ToolDetails(rows: List<Pair<String, String>>, label: String? = null, monoValues: Set<String> = emptySet()) {
    val c = PrTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        label?.let { Caps(it, modifier = Modifier.padding(bottom = 6.dp)) }
        rows.forEachIndexed { i, (key, value) ->
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(key, style = mockText(14.sp), color = c.text)
                    Text(
                        value,
                        style = if (key in monoValues) mockText(12.sp, mono = true) else mockText(14.sp),
                        color = c.textMuted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    )
                }
                if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            }
        }
    }
}

/** The script's output in a box with a Show / Hide header; error lines in red. */
@Composable
fun ToolLog(lines: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    val c = PrTheme.colors
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = c.codeBackground, border = BorderStroke(1.dp, c.iconTile)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("Log", style = mockText(13.sp), color = c.textMuted, modifier = Modifier.weight(1f))
                Text(if (expanded) "Hide ⌄" else "Show ›", style = mockText(13.sp), color = c.textMuted)
            }
            if (expanded) {
                SelectionContainer(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    Text(
                        buildAnnotatedString {
                            if (lines.isEmpty()) append("No output yet.")
                            lines.forEachIndexed { i, line ->
                                if (i > 0) append("\n")
                                val error = line.startsWith("[error]") || line.startsWith("E: ")
                                if (error) withStyle(SpanStyle(color = c.redSoft)) { append(line) } else append(line)
                            }
                        },
                        style = mockText(11.sp, mono = true).copy(lineHeight = 17.sp),
                        color = c.textPreview,
                    )
                }
            }
        }
    }
}

/** The page's main button while a job runs: grey, disabled. */
@Composable
fun BusyButton(text: String) {
    val c = PrTheme.colors
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(c.border), contentAlignment = Alignment.Center) {
        Text(text, style = mockText(15.sp, FontWeight.SemiBold), color = c.textMuted)
    }
}

/** A destructive bordered button (Uninstall). */
@Composable
fun DangerButton(text: String, onClick: () -> Unit) {
    OutlineButton(text, onClick, Modifier.fillMaxWidth(), color = PrTheme.colors.redSoft, border = PrTheme.colors.redBorder)
}

/** A tool page's bottom sheet: its look and its drag handle; [content] is what it says. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = PrTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = c.sheet,
        contentColor = c.text,
        scrimColor = Color(0x9905070A),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp).size(width = 36.dp, height = 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp)))
            content()
        }
    }
}

/** The confirm sheet for a destructive action: title, what happens, the filled red button, Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    ToolSheet(onDismiss) {
        Text(title, style = mockText(17.sp, FontWeight.Bold), color = c.text)
        Text(body, style = mockText(14.sp).copy(lineHeight = 21.sp), color = c.textSoft)
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(c.redBorder).clickable(onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            Text(confirm, style = mockText(15.sp, FontWeight.SemiBold), color = c.diffRemovedText)
        }
        OutlineButton("Cancel", onDismiss, Modifier.fillMaxWidth())
    }
}
