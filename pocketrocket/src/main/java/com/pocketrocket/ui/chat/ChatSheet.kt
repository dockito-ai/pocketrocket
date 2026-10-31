package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pocketrocket.ui.theme.PrTheme

/**
 * The chat's bottom sheet (Controls.dc.html): card colour, rounded top with a 1px border line, a grab handle, and a
 * scrolling column for [content]. Used by the Controls and the attach ("+") sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = PrTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.card,
        contentColor = c.text,
        scrimColor = c.scrim.copy(alpha = 0.85f),
        tonalElevation = 0.dp,
        dragHandle = null,
        modifier = Modifier.drawBehind {
            // A 1px #2A303C line along the sheet's rounded top edge.
            drawRoundRect(
                c.border,
                cornerRadius = CornerRadius(24.dp.toPx()),
                size = Size(size.width, size.height + 24.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp)))
            content()
        }
    }
}
