package com.pocketrocket.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.chat.MarkdownText
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * A read-only text file (the git file view and the file manager's viewer): numbered, coloured lines in the
 * mockup's code pane. [note] shows under the last line (a file cut off at the size limit).
 */
@Composable
fun CodePane(text: String, fileName: String, note: String?, modifier: Modifier = Modifier) {
    val lines = remember(text) { text.removeSuffix("\n").split('\n') }
    val comment = remember(fileName) { SyntaxColors.lineComment(fileName) }
    val c = gitColors
    // One Text per line: a number gutter, then the coloured code, wrapped lines hanging under the code.
    val digits = lines.size.toString().length
    val style = CodeStyle.copy(textIndent = TextIndent(restLine = monoCharWidth() * (digits + 2)))
    // The mockup's read-only pane: #0B0E13 inside a #2A303C edge, radius 14, 18 dp from the sides.
    val pane = RoundedCornerShape(14.dp)
    LazyColumn(
        modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp)
            .clip(pane).background(PrTheme.colors.codeBackground).border(1.dp, PrTheme.colors.border, pane),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val shown = remember(line, c) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.lineNumber)) { append((index + 1).toString().padStart(digits)); append("  ") }
                    val plain = line.replace("\t", "    ")
                    if (comment != null) append(SyntaxColors.highlight(plain, comment, c)) else append(plain)
                }
            }
            Text(shown, style = style, color = PrTheme.colors.text, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        }
        if (note != null) item {
            Text(note, style = mockText(13.sp), color = PrTheme.colors.textMuted, modifier = Modifier.padding(16.dp))
        }
    }
}

/** A Markdown file rendered for reading, as the git file view shows it. */
@Composable
fun MarkdownPane(text: String, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) { item { MarkdownText(remember(text) { forReading(text) }) } }
}

internal fun isMarkdown(path: String) = path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)
