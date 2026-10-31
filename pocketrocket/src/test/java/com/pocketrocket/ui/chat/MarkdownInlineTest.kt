package com.pocketrocket.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInlineTest {
    private val styles = InlineStyles(Color.Gray, TextLinkStyles())

    /** Each link as (its text, its URL). */
    private fun links(text: AnnotatedString) = text.getLinkAnnotations(0, text.length).map {
        text.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url
    }

    @Test fun bareUrlBecomesALinkWithoutTrailingPunctuation() {
        val out = inline("See https://example.com/page.", styles)
        assertEquals("See https://example.com/page.", out.text)
        assertEquals(listOf("https://example.com/page" to "https://example.com/page"), links(out))
    }

    @Test fun markdownLinkShowsItsLabel() {
        val out = inline("Open [the BRD](https://example.com/doc) now", styles)
        assertEquals("Open the BRD now", out.text)
        assertEquals(listOf("the BRD" to "https://example.com/doc"), links(out))
    }

    @Test fun linkInsideBoldStillWorks() {
        val out = inline("Here: **https://dockito.ai/x**", styles)
        assertEquals("Here: https://dockito.ai/x", out.text)
        assertEquals(listOf("https://dockito.ai/x" to "https://dockito.ai/x"), links(out))
    }

    @Test fun urlInInlineCodeIsNotALink() {
        val out = inline("Run `curl https://example.com`", styles)
        assertEquals(emptyList<Pair<String, String>>(), links(out))
    }
}
