package com.pocketrocket.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/*
 * The account screens' parts, copied from mockup ClaudeAccounts / ClaudeAccount / GitHubAccounts:
 * #161A22 cards with a #232833 edge, #2A303C letter badges, the orange Default pill, 11 px capitals labels.
 */

/** A pushed page: back arrow and a 17 sp title, then scrolling content at 24 dp sides with [gap] between items. */
@Composable
internal fun AccountPage(title: String, onBack: () -> Unit, gap: Dp = 8.dp, top: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    MockPage {
        MockTopBar(title, MockIcons.Back, onBack, titleSize = 17.sp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = top, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

/** The quiet card: #161A22, a #232833 edge (orange 1.5 dp for [highlight]), radius 16, 16 dp padding. */
@Composable
internal fun QuietCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    radius: Dp = 16.dp,
    padding: Dp = 16.dp,
    gap: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(radius)
    val edge = if (highlight) BorderStroke(1.5.dp, c.accent) else BorderStroke(1.dp, c.iconTile)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(edge, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** A capitals section label ("USAGE", "IN USE BY"): 11 sp / 600, 0.5 sp tracking, muted. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) = Caps(text.uppercase(), modifier = modifier)

/** A round letter badge for an account: #2A303C, the letter at 14 sp / 600 (18 sp on the 48 dp header). */
@Composable
internal fun InitialBadge(text: String, size: Dp = 36.dp, fontSize: TextUnit = if (size >= 44.dp) 18.sp else 14.sp) {
    val c = PrTheme.colors
    Box(Modifier.size(size).background(c.border, CircleShape), contentAlignment = Alignment.Center) {
        Text(text.take(1).uppercase(), style = mockText(fontSize, FontWeight.SemiBold, c.text))
    }
}

/** The orange "Default" pill: 10.5 sp in a list row, 11 sp with more padding beside a header. */
@Composable
internal fun DefaultChip(large: Boolean = false) {
    val c = PrTheme.colors
    Text(
        "Default",
        style = mockText(if (large) 11.sp else 10.5.sp, FontWeight.SemiBold, c.accent),
        modifier = Modifier
            .background(c.accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = if (large) 9.dp else 7.dp, vertical = if (large) 4.dp else 2.dp),
    )
}

@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

/** A label and a value on one line, the value on the right ("Commits as   Shankar · noreply"), 12 sp. */
@Composable
internal fun KeyValue(key: String, value: String, mono: Boolean = false, valueColor: Color = PrTheme.colors.text) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = mockText(12.sp, color = c.textMuted))
        Spacer(Modifier.width(16.dp))
        Text(
            value, style = mockText(if (mono) 11.sp else 12.sp, color = valueColor, mono = mono),
            modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A tappable row on its own small card ("work/pocketrocket   3 sessions ›"): radius 12, 12/14 padding, 14 sp. */
@Composable
internal fun LinkRow(label: String, value: String, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.iconTile, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = mockText(14.sp, color = c.text, mono = mono), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = mockText(14.sp, color = c.textMuted), maxLines = 1)
    }
}

/** A settings line under a capitals label: 14 sp label, value or [trailing] on the right, 13 dp padding, divider below unless [last]. */
@Composable
internal fun SettingLine(label: String, last: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    val c = PrTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 2.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = mockText(14.sp, color = c.text), modifier = Modifier.weight(1f))
            trailing()
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

/** One usage bar: label and "42% · resets in 2h 14m" at 13 sp over a 6 dp orange bar on #2A303C, amber from 80%. */
@Composable
internal fun UsageBar(label: String, detail: String, fraction: Float) {
    val c = PrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = mockText(13.sp, color = c.text), modifier = Modifier.weight(1f))
            Text(detail, style = mockText(13.sp, color = c.textMuted))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.border)) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (fraction >= 0.8f) c.amber else c.accent),
            )
        }
    }
}

/** A dialog asking for one line of text (a name, an email). */
@Composable
internal fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    body: String? = null,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body?.let { Text(it, style = mockText(14.sp, color = PrTheme.colors.textMuted)) }
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A yes/no question before something that can't be undone. */
@Composable
internal fun Confirm(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
