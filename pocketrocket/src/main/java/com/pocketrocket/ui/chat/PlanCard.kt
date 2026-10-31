package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.pocketrocket.sessions.PendingPermission

/**
 * Claude's plan, when it asks to leave plan mode (ExitPlanMode). Approve lets it start (the CLI goes back to
 * Manual); "Approve and accept edits" also switches to Accept edits; Keep planning ends the turn so you can say
 * what to change. Drawn like the permission card, in Plan mode's teal.
 */
@Composable
fun PlanCard(permission: PendingPermission, onAnswer: (approve: Boolean, acceptEdits: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val teal = c.teal
    val shape = RoundedCornerShape(16.dp)
    val plan = permission.input.optString("plan").ifBlank { "Claude is ready to start." }
    val line = teal.copy(alpha = 0.35f).compositeOver(c.card)
    Column(
        modifier
            .fillMaxWidth()
            .background(teal.copy(alpha = 0.08f).compositeOver(c.card), shape)
            .border(1.5.dp, teal, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ready to start? Here is Claude's plan", style = PrType.body, fontWeight = FontWeight.SemiBold, color = c.text)
        Box(
            Modifier.fillMaxWidth().heightIn(max = 260.dp)
                .background(c.background, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            MarkdownText(plan)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Keep planning", Modifier.weight(1f), border = line, content = c.text) { onAnswer(false, false) }
            CardButton("Approve", Modifier.weight(1f), fill = teal, content = c.onAccent, bold = true) { onAnswer(true, false) }
        }
        CardButton("Approve and accept edits", Modifier.fillMaxWidth(), height = 40.dp, border = line, content = teal, style = PrType.small) {
            onAnswer(true, true)
        }
    }
}
