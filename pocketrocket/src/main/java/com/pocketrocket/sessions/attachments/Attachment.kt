package com.pocketrocket.sessions.attachments

import java.io.File

/** A file attached to a chat message: a copy under `~/.pocketrocket/attachments/`, so Claude can open it by [path]. */
data class Attachment(val path: String, val name: String, val mime: String, val size: Long) {
    val isImage: Boolean get() = mime in INLINE_IMAGES
    val isPdf: Boolean get() = mime == "application/pdf"

    companion object {
        /** Image types the API takes inline; anything else is converted to JPEG on import. */
        val INLINE_IMAGES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        /** Rebuilds one from a file on disk, for messages replayed from a transcript. Null when the file is gone. */
        fun fromFile(path: String): Attachment? {
            val file = File(path)
            if (!file.isFile) return null
            return Attachment(file.path, file.name.substringAfter('-'), mimeFor(file.name), file.length())
        }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
