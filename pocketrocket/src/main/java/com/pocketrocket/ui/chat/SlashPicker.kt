package com.pocketrocket.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.SlashCommand

/**
 * The commands matching what is typed after `/`, above the chat box: built-ins, skills and plugin commands, as the
 * CLI reported them. Shows while the box holds a lone `/word`; picking one puts `/name ` in the box.
 */
@Composable
fun SlashPicker(text: String, commands: List<SlashCommand>, onPick: (String) -> Unit) {
    val query = text.takeIf { it.startsWith("/") && it.none(Char::isWhitespace) }?.drop(1)
    val matches = if (query == null) emptyList() else matching(commands, query)
    AnimatedVisibility(matches.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val c = PrTheme.colors
        val shape = RoundedCornerShape(12.dp)
        LazyColumn(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(max = 260.dp).clip(shape)
                .background(c.card)
                .border(1.dp, c.border, shape),
        ) {
            items(matches, key = { it.name }) { command ->
                Column(Modifier.fillMaxWidth().clickable { onPick(command.name) }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(
                        buildAnnotatedString {
                            append("/${command.name}")
                            if (command.argumentHint.isNotBlank()) {
                                withStyle(SpanStyle(color = c.textMuted, fontWeight = FontWeight.Normal)) { append(" ${command.argumentHint}") }
                            }
                        },
                        style = PrType.code,
                        color = c.text,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (command.description.isNotBlank()) {
                        Text(
                            command.description,
                            style = PrType.caption,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Names starting with the query first, then names containing it. */
internal fun matching(commands: List<SlashCommand>, query: String): List<SlashCommand> {
    val q = query.lowercase()
    val starts = commands.filter { it.name.lowercase().startsWith(q) }
    val contains = commands.filter { q.isNotEmpty() && q in it.name.lowercase() && it !in starts }
    return starts + contains
}
