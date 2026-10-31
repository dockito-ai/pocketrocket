package com.pocketrocket.ui.git

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * The bottom sheet of the branch and PR screens (BranchesPRs.html `.sheet`): radius 22 at the top, the sheet
 * colour, a 36 x 4 grab handle and 10/16/18 padding. [content] decides whether it scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = PrTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = c.sheet,
        contentColor = c.text,
        scrimColor = c.scrim.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(width = 36.dp, height = 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp)))
            content()
        }
    }
}

/** A section label in a sheet or a PR (`.lbl`): 11 sp, uppercase, muted, spaced out. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = mockText(11.sp, color = PrTheme.colors.textMuted, letterSpacing = 0.66.sp),
        modifier = modifier.padding(start = 2.dp, end = 2.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** One choice in an action sheet (`.br` at 14 sp), with a divider under it unless [last]. */
@Composable
fun SheetItem(text: String, enabled: Boolean = true, last: Boolean = false, mono: String? = null, onClick: () -> Unit) {
    val c = PrTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = mockText(14.sp, color = if (enabled) c.text else c.textMuted))
            if (mono != null) Text(mono, style = mockText(14.sp, color = if (enabled) c.text else c.textMuted, mono = true), modifier = Modifier.padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!last) HorizontalDivider(color = c.raised)
    }
}

/** A filter chip (`.chips span`): outlined, or orange on a dark-orange fill when [on]. */
@Composable
fun GitChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style = mockText(12.sp, color = if (on) c.accent else c.textSoft),
        maxLines = 1,
        modifier = Modifier.clip(shape)
            .background(if (on) c.accentSurface else Color.Transparent)
            .border(1.dp, if (on) c.accentBorder else c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A small label (`.pill`): 11 sp semibold on a raised background, radius 6. */
@Composable
fun Pill(text: String, color: Color, background: Color = PrTheme.colors.raised, mono: Boolean = false) {
    Text(
        text,
        style = mockText(if (mono) 10.sp else 11.sp, if (mono) FontWeight.Normal else FontWeight.SemiBold, color, mono = mono),
        maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(if (mono) 4.dp else 6.dp)).padding(horizontal = if (mono) 5.dp else 7.dp, vertical = 2.dp),
    )
}

/** The dialogs of the branch and PR screens (`.dialog`): radius 22 on the dialog colour. */
@Composable
fun GitDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PrTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.dialog,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = mockText(17.sp, FontWeight.Bold, c.text)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirm, style = mockText(14.sp, FontWeight.SemiBold, if (confirmEnabled) c.accent else c.textMuted))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = mockText(14.sp, FontWeight.SemiBold, c.textMuted)) } },
    )
}

/** A dialog asking for one name (New branch, New session on a branch). */
@Composable
fun NameDialog(title: String, note: String?, placeholder: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && !name.any { it.isWhitespace() }
    GitDialog(title, onDismiss, confirm, onConfirm = { onConfirm(name.trim()) }, confirmEnabled = valid) {
        if (note != null) Text(note, style = mockText(13.5.sp, color = PrTheme.colors.textSoft))
        MockTextField(name, { name = it }, placeholder = placeholder, background = PrTheme.colors.background, mono = true)
    }
}

/** The amber note (`.warn`) shown when a session is working in the folder. */
@Composable
fun WarnBox(text: String) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        style = mockText(12.5.sp, color = c.amberText),
        modifier = Modifier.fillMaxWidth().background(c.amberSurface, shape).border(1.dp, c.amberBorder, shape).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A choice in a dialog (`.opt`), outlined in orange when picked. */
@Composable
fun OptionBox(title: String, note: String, picked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (picked) c.accentSurface else Color.Transparent)
            .border(1.dp, if (picked) c.accentBorder else c.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, style = mockText(13.5.sp, color = if (enabled) c.text else c.textMuted))
        Text(note, style = mockText(12.sp, color = c.textMuted), modifier = Modifier.padding(top = 3.dp))
    }
}

/** Puts [text] on the clipboard and says so. */
fun copyText(context: Context, text: String, what: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(what, text))
    Toast.makeText(context, "Copied $what", Toast.LENGTH_SHORT).show()
}
