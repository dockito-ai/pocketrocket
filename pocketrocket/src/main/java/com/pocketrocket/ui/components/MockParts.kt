package com.pocketrocket.ui.components

import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Building blocks of the mockup screens (docs/ux-mockup, decision 23): sizes, radii and colours are the
 * mockup's CSS values, 1 CSS px = 1 dp/sp.
 */

/** A full-screen mockup page: the mockup background, clear of the system bars and the keyboard. */
@Composable
fun MockPage(content: @Composable ColumnScope.() -> Unit) {
    // Pages paint their own background, so they also set the content colour a Surface would.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides PrTheme.colors.text) {
        Column(Modifier.fillMaxSize().background(PrTheme.colors.background).systemBarsPadding().imePadding(), content = content)
    }
}

/** The top row of a pushed screen: a 44 dp icon button, a title (and mono subtitle), optional trailing content. */
@Composable
fun MockTopBar(
    title: String,
    icon: ImageVector,
    onIcon: () -> Unit,
    titleSize: TextUnit = 18.sp,
    titleWeight: FontWeight = FontWeight.SemiBold,
    subtitle: String? = null,
    subtitleSize: TextUnit = 12.sp,
    top: Dp = 18.dp,
    bottom: Dp = 6.dp,
    iconSize: Dp = if (icon == MockIcons.Close) 20.dp else 22.dp,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = top, bottom = bottom),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onIcon), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = if (icon == MockIcons.Close) "Close" else "Back", tint = c.text, modifier = Modifier.size(iconSize))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = mockText(titleSize, titleWeight, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = mockText(subtitleSize, color = c.textMuted, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

/** A small capitals label ("NAME", "CLAUDE ACCOUNT"). */
@Composable
fun Caps(text: String, size: TextUnit = 11.sp, spacing: TextUnit = 0.5.sp, modifier: Modifier = Modifier, extra: String? = null) {
    val c = PrTheme.colors
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            extra?.let {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Normal))
                append(" ")
                append(it)
                pop()
            }
        },
        style = mockText(size, FontWeight.SemiBold, c.textMuted, letterSpacing = spacing),
        modifier = modifier,
    )
}

/** The app's on/off switch: orange when on. */
@Composable
fun AccentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = PrTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
            uncheckedThumbColor = c.textMuted, uncheckedTrackColor = c.raised, uncheckedBorderColor = c.border,
        ),
    )
}

/** The orange call to action. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    radius: Dp = 16.dp,
    fontSize: TextUnit = 15.sp,
    enabled: Boolean = true,
    background: Color = PrTheme.colors.accent,
    content: Color = PrTheme.colors.onAccent,
) {
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(fontSize, FontWeight.SemiBold, content), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A quiet bordered button (44 dp, radius 12). */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = PrTheme.colors.text,
    border: Color = PrTheme.colors.border,
    fontSize: TextUnit = 14.sp,
    radius: Dp = 12.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(radius)).border(1.dp, border, RoundedCornerShape(radius)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(fontSize, color = if (enabled) color else color.copy(alpha = 0.4f)), maxLines = 1)
    }
}

/** A rounded box with a fill and a border, clickable when [onClick] is set. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MockBox(
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    background: Color = PrTheme.colors.card,
    border: BorderStroke? = BorderStroke(1.dp, PrTheme.colors.border),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    var m = modifier.clip(shape).background(background, shape)
    if (border != null) m = m.border(border, shape)
    if (onClick != null || onLongClick != null) m = m.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    Box(m) { content() }
}

/** A selectable card with a name and a note, for account pickers laid out two per row. */
@Composable
fun ChoiceCard(name: String, note: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, nameSize: TextUnit = 14.sp, noteSize: TextUnit = 11.sp) {
    val c = PrTheme.colors
    MockBox(
        modifier,
        background = if (selected) c.accent.copy(alpha = 0.10f) else c.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.5.dp, if (selected) c.accent else c.border),
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = mockText(nameSize, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = mockText(noteSize, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** [items] two to a row with 8 dp gaps, like the mockup's two-column grids. */
@Composable
fun <T> TwoColumns(items: List<T>, gap: Dp = 8.dp, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A radio circle: a thick orange ring when on, a thin grey one when off. */
@Composable
fun RadioDot(selected: Boolean, size: Dp = 16.dp, onWidth: Dp = 5.dp) {
    val c = PrTheme.colors
    Box(Modifier.size(size).border(if (selected) onWidth else 1.5.dp, if (selected) c.accent else c.textFaint, CircleShape))
}

/** A full-width radio row with a name and a note (workspace settings). */
@Composable
fun RadioRow(name: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    MockBox(
        Modifier.fillMaxWidth(),
        background = if (selected) c.accent.copy(alpha = 0.08f) else c.surface,
        border = BorderStroke(1.5.dp, if (selected) c.accent else c.iconTile),
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioDot(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = mockText(14.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, style = mockText(11.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Choices side by side in a rounded track; the chosen one filled with the text colour. */
@Composable
fun <T> Segmented(options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
    val c = PrTheme.colors
    val track = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().background(c.surface, track).border(1.dp, c.iconTile, track).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == current
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.text else Color.Transparent)
                    .clickable(role = Role.Button) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = mockText(13.sp, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) c.background else c.textSoft))
            }
        }
    }
}

/** A one-line text field in the mockup's style (44 dp, radius 12). */
@Composable
fun MockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    background: Color = PrTheme.colors.surface,
    singleLine: Boolean = true,
    height: Dp = 44.dp,
    mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    radius: Dp = 12.dp,
    sidePadding: Dp = 14.dp,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = PrTheme.colors
    val style = mockText(14.sp, color = c.text, mono = mono)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius)).background(background)
            .border(1.dp, c.border, RoundedCornerShape(radius)),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = if (singleLine) 0.dp else 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.textMuted))
                inner()
            }
        },
    )
}

/** A 1 dp dashed outline, like CSS `border: 1px dashed`. */
fun Modifier.dashedBorder(color: Color, radius: Dp, dash: Dp = 3.dp) = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), dash.toPx()))),
    )
}

/** The mockup's dashed "+ Add …" button: 50 dp, radius 14, orange 14 sp / 600 on a dashed #3A4150 edge. */
@Composable
fun DashedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 50.dp, radius: Dp = 14.dp) {
    val c = PrTheme.colors
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius)).dashedBorder(c.borderStrong, radius, 4.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(14.sp, FontWeight.SemiBold, c.accent), maxLines = 1)
    }
}
