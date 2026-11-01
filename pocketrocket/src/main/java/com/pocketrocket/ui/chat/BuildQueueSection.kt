package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.builds.BuildEntry
import com.pocketrocket.builds.BuildQueueState
import com.pocketrocket.builds.BuildSlots
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The phone's build queue (decision 97), read every two seconds while shown. */
@Composable
private fun buildQueue(): State<BuildQueueState> = produceState(BuildQueueState.EMPTY) {
    while (true) {
        value = withContext(Dispatchers.IO) { runCatching { BuildSlots.queue() }.getOrDefault(BuildQueueState.EMPTY) }
        delay(2_000)
    }
}

/**
 * Builds on this phone, from every session and terminal: the ones in a slot, then the ones waiting their turn. Shown in
 * the Background sheet only while there are any; [sessionFolder] marks this session's own.
 */
@Composable
fun BuildQueueSection(sessionFolder: String) {
    val queue by buildQueue()
    if (queue.isEmpty) return
    val c = PrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val slots = if (queue.slots > 0) " · ${queue.slots} AT A TIME" else ""
        Text("BUILDS ON THIS PHONE$slots", style = PrType.micro, fontWeight = FontWeight.SemiBold, color = c.textMuted)
        queue.running.forEach { BuildRow(it, null, it.session == sessionFolder) }
        queue.waiting.forEachIndexed { i, entry -> BuildRow(entry, i + 1, entry.session == sessionFolder) }
    }
}

@Composable
private fun BuildRow(entry: BuildEntry, place: Int?, ours: Boolean) {
    val c = PrTheme.colors
    val now by tickingNow(true)
    val tint = if (place == null) c.teal else c.amber
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(c.iconTile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(if (place == null) "▶" else "#$place", style = PrType.code, color = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.command, style = PrType.codeSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val state = if (place == null) "Building" else "Waiting for a slot"
            val where = listOfNotNull(entry.folderName.ifEmpty { null }, "this session".takeIf { ours }).joinToString(" · ")
            Text(listOf(state, where).filter { it.isNotEmpty() }.joinToString(" · "), style = PrType.small, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val since = (if (place == null) entry.startedAt else null) ?: entry.queuedAt
        if (since > 0) Text(agentClock((now - since * 1_000).coerceAtLeast(0)), style = PrType.codeSmall, color = tint)
    }
}
