package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/**
 * Claude's request to use a tool (Session.dc.html): what it wants to do, the command or file, Deny and Allow, and,
 * when the CLI suggested a rule, "Always allow <rule>". The last item in the chat until answered.
 */
@Composable
fun PermissionCard(permission: PendingPermission, onAnswer: (allow: Boolean, always: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val detail = permission.input.optString("command").ifBlank { permission.input.optString("file_path") }.ifBlank { permission.summary }
    Column(
        modifier
            .fillMaxWidth()
            .background(c.amberSurface, shape)
            .border(1.5.dp, c.amber, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(askTitle(permission.toolName), style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.text)
        CodeBlock(detail)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Deny", Modifier.weight(1f), border = c.amberBorder, content = c.text) { onAnswer(false, false) }
            CardButton("Allow", Modifier.weight(1f), fill = c.amber, content = c.onAmber, bold = true) { onAnswer(true, false) }
        }
        permission.alwaysAllow?.let { always ->
            CardButton(
                withCode(always.label), Modifier.fillMaxWidth(), height = 40.dp, border = c.amberBorder, content = c.amberText,
                style = PrType.small,
            ) { onAnswer(true, true) }
        }
    }
}

/** The card's heading, in the words the mockup uses. */
internal fun askTitle(tool: String): String = when (tool) {
    "Bash" -> "Claude wants to run a command"
    "Edit", "MultiEdit", "Write", "NotebookEdit" -> "Claude wants to edit a file"
    "Read" -> "Claude wants to read a file"
    "WebFetch" -> "Claude wants to open a web page"
    "WebSearch" -> "Claude wants to search the web"
    else -> "Claude wants to use $tool"
}

/** The command or path, on the page colour, in JetBrains Mono 13. */
@Composable
internal fun CodeBlock(text: String) {
    Box(
        Modifier.fillMaxWidth().background(PrTheme.colors.background, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, style = PrType.code, color = PrTheme.colors.text)
    }
}

/** A 12dp-rounded card button: outlined when [fill] is null, filled otherwise. 44dp tall unless [height] says. */
@Composable
internal fun CardButton(
    label: String,
    modifier: Modifier,
    height: Dp = 44.dp,
    fill: Color? = null,
    border: Color? = null,
    content: Color,
    bold: Boolean = false,
    style: TextStyle = PrType.body,
    onClick: () -> Unit,
) = CardButton(AnnotatedString(label), modifier, height, fill, border, content, bold, style, onClick)

@Composable
internal fun CardButton(
    label: AnnotatedString,
    modifier: Modifier,
    height: Dp = 44.dp,
    fill: Color? = null,
    border: Color? = null,
    content: Color,
    bold: Boolean = false,
    style: TextStyle = PrType.body,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(height).clip(shape)
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style, color = content, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `backticked` parts in JetBrains Mono, as in the chat. */
private fun withCode(text: String): AnnotatedString = buildAnnotatedString {
    text.split('`').forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontFamily = JetBrainsMono)) { append(part) } else append(part)
    }
}
