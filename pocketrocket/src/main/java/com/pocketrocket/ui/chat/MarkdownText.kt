package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Just enough Markdown for Claude's replies: code fences, bullets, quotes, headings, **bold**,
 * `inline code` and links (`[label](url)` or a bare http(s) URL; tapping one opens the browser).
 * Anything else shows as plain text, which is fine for a chat.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = PrTheme.colors.textReply) {
    val c = PrTheme.colors
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    val spans = InlineStyles(codeBackground = c.raised, link = TextLinkStyles(SpanStyle(color = c.blue, textDecoration = TextDecoration.Underline)))
    // Session.dc.html: replies are 14px Inter at line-height 1.5.
    val body = PrType.body.copy(lineHeight = 21.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlocks.Block.Code -> Box(
                    Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(block.text, style = PrType.codeSmall.copy(lineHeight = 17.sp), color = color)
                }
                is MarkdownBlocks.Block.Bullet -> Row {
                    Text("•", style = body, color = c.accent, modifier = Modifier.width(16.dp))
                    Text(inline(block.text, spans), style = body, color = color)
                }
                is MarkdownBlocks.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(c.border))
                    Text(inline(block.text, spans), style = body, color = c.textMuted, modifier = Modifier.padding(start = 10.dp))
                }
                is MarkdownBlocks.Block.Heading -> Text(inline(block.text, spans), style = body.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold, color = c.text)
                is MarkdownBlocks.Block.Paragraph -> Text(inline(block.text, spans), style = body, color = color)
            }
        }
    }
}

/** How [inline] draws code and links. */
internal class InlineStyles(val codeBackground: Color, val link: TextLinkStyles)

private val InlinePattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(https?://[^\\s<>()\\[\\]`*]+)")

/** Punctuation that ends a sentence rather than a bare URL. */
private const val UrlTrailing = ".,;:!?'\""

/** **bold**, `code` and link spans. Bold may hold links; code never does. */
internal fun inline(text: String, styles: InlineStyles): AnnotatedString = buildAnnotatedString {
    var last = 0
    InlinePattern.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        val (bold, code, label, url, bare) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(bold, styles)) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = JetBrainsMono, background = styles.codeBackground, fontSize = 13.sp)) { append("\u2009$code\u2009") }
            label.isNotEmpty() -> withLink(LinkAnnotation.Url(url, styles.link)) { append(label) }
            else -> {
                val link = bare.trimEnd { it in UrlTrailing }
                withLink(LinkAnnotation.Url(link, styles.link)) { append(link) }
                append(bare.substring(link.length))
            }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

/** Splits Markdown into the blocks [MarkdownText] draws. */
object MarkdownBlocks {
    sealed interface Block {
        data class Paragraph(val text: String) : Block
        data class Heading(val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
    }

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var code: StringBuilder? = null

        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }

        text.lines().forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> code = if (code == null) {
                    flush(); StringBuilder()
                } else {
                    blocks += Block.Code(code.toString().trimEnd()); null
                }
                code != null -> code!!.append(line).append('\n')
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> { flush(); blocks += Block.Bullet(trimmed.drop(2)) }
                trimmed.startsWith("> ") -> { flush(); blocks += Block.Quote(trimmed.drop(2)) }
                trimmed.startsWith("#") -> { flush(); blocks += Block.Heading(trimmed.trimStart('#').trim()) }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
        }
        code?.let { blocks += Block.Code(it.toString().trimEnd()) }
        flush()
        return blocks
    }
}
