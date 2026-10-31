package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/*
 * The pieces of a [ChatSheet] controls sheet, shared by the session's controls and Rocket's quick controls.
 */

/** MODE, MODEL...: 12px / 600 muted capitals, with an optional JetBrains Mono value on the right. */
@Composable
internal fun SheetHeading(text: String, trailing: String? = null) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Text(text, style = PrType.section, color = c.textMuted, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = PrType.codeSmall, color = c.textMuted) }
    }
}

/** A card with a name and a line; when [on], tinted and outlined in orange. */
@Composable
internal fun SheetTile(title: String, note: String, on: Boolean, modifier: Modifier = Modifier, minHeight: Dp = 0.dp, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.heightIn(min = minHeight).clip(shape)
            .background(if (on) c.accentTint else c.background)
            .border(1.5.dp, if (on) c.accent else c.border, shape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(note, style = PrType.caption.copy(lineHeight = 16.sp), color = c.textMuted)
    }
}

/** One entry of a [SheetDropdown]. */
internal class SheetOption(val label: String, val note: String, val selected: Boolean, val onPick: () -> Unit)

/** One row with the current choice, opening a menu of all of them. */
@Composable
internal fun SheetDropdown(title: String, note: String?, options: List<SheetOption>) {
    val c = PrTheme.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.background).border(1.dp, c.border, shape)
                .clickable(role = Role.Button) { open = true }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = PrType.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("▾", style = PrType.body, color = c.textMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                option.label, style = PrType.body,
                                fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal, color = if (option.selected) c.accent else c.text,
                            )
                            if (option.note.isNotBlank()) Text(option.note, style = PrType.caption, color = c.textMuted)
                        }
                    },
                    trailingIcon = if (option.selected) ({ Text("✓", style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.accent) }) else null,
                    onClick = { open = false; option.onPick() },
                )
            }
        }
    }
}

/** 40dp outlined button, 10dp corners, 13px text; Stop in the soft red. Disabled ones fade. */
@Composable
internal fun SheetButton(label: String, modifier: Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(40.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .border(1.dp, if (danger) c.redBorder else c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = PrType.small, color = if (danger) c.redSoft else c.text)
    }
}
