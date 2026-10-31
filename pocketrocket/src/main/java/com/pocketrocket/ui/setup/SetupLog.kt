package com.pocketrocket.ui.setup

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.components.mockText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketrocket.ui.theme.LogTextStyle

/** The script's raw output, newest at the bottom, following along as lines arrive. Selectable for copying. */
@Composable
fun SetupLog(lines: List<String>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }

    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = PrTheme.colors.codeBackground, border = androidx.compose.foundation.BorderStroke(1.dp, PrTheme.colors.border)) {
        SelectionContainer(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).heightIn(max = 256.dp).verticalScroll(scroll)) {
            Text(
                if (lines.isEmpty()) "No output yet." else lines.joinToString("\n"),
                style = LogTextStyle,
                color = PrTheme.colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
