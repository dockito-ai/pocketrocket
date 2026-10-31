package com.pocketrocket.sessions.attachments

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Turns a typed message and its attachments into the content blocks of one user turn: images and PDFs inline (so
 * Claude sees them at once), then the text, then a note listing every attachment's path on the phone (so Claude can
 * open other files with its own tools, or copy an image into the project). The note also lets a transcript replay
 * show the attachments again.
 */
object AttachmentMessage {
    const val NOTE_HEADER = "[Attached on this phone]"

    /** Inline PDFs above this go by path only. */
    private const val MAX_INLINE_PDF = 20L * 1024 * 1024

    fun content(text: String, attachments: List<Attachment>): JSONArray {
        val blocks = JSONArray()
        attachments.forEach { a ->
            when {
                a.isImage -> blocks.put(base64Block("image", a))
                a.isPdf && a.size <= MAX_INLINE_PDF -> blocks.put(base64Block("document", a))
            }
        }
        if (text.isNotBlank()) blocks.put(textBlock(text))
        blocks.put(textBlock(note(attachments)))
        return blocks
    }

    fun note(attachments: List<Attachment>): String =
        NOTE_HEADER + "\n" + attachments.joinToString("\n") { "- ${it.path}" }

    /** The paths in a note block, or null when [text] is not one: the header, then only "- /path" lines. */
    fun pathsIn(text: String): List<String>? {
        val lines = text.trimEnd().lines()
        if (lines.first() != NOTE_HEADER || lines.size < 2 || lines.drop(1).any { !it.startsWith("- /") }) return null
        return lines.drop(1).map { it.removePrefix("- ") }
    }

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

    private fun base64Block(type: String, a: Attachment): JSONObject {
        val data = Base64.encodeToString(File(a.path).readBytes(), Base64.NO_WRAP)
        val source = JSONObject().put("type", "base64").put("media_type", a.mime).put("data", data)
        return JSONObject().put("type", type).put("source", source)
    }
}
