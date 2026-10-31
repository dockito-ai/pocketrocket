package com.pocketrocket.sessions.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketrocket.profiles.AccountPaths
import java.io.File
import java.util.UUID

/**
 * Copies picked files into `~/.pocketrocket/attachments/<session>/`. Photos are shrunk to [MAX_EDGE] on the long side
 * (phone photos are far bigger than Claude needs and would eat usage); types the API cannot take inline, like HEIC,
 * become JPEG. Blocking: call off the main thread.
 */
object Attachments {
    private const val MAX_EDGE = 1568
    private const val MAX_IMAGE_BYTES = 3_500_000L

    fun dir(sessionId: String) = File(AccountPaths.home, ".pocketrocket/attachments/$sessionId")

    /** A session id for files attached before the session exists; pass it to `SessionManager.startClaude`. */
    fun newSessionId() = UUID.randomUUID().toString().take(8)

    fun import(context: Context, uri: Uri, sessionId: String): Attachment {
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val mime = resolver.getType(uri) ?: Attachment.mimeFor(name)
        val folder = dir(sessionId).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val target = File(folder, "$stamp-$name")
        resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (!mime.startsWith("image/")) return Attachment(target.path, name, mime, target.length())
        return shrink(target, mime, name)
    }

    /** A photo the camera wrote straight into the session's folder ([AttachmentFiles.cameraTarget]). */
    fun adoptPhoto(file: File): Attachment = shrink(file, "image/jpeg", file.name.substringAfter('-'))

    /** Keeps a small image as it is; otherwise writes a scaled JPEG beside it and drops the original. */
    private fun shrink(original: File, mime: String, name: String): Attachment {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return Attachment(original.path, name, "application/octet-stream", original.length())
        // Camera photos are often stored sideways with an EXIF tag saying so; the API ignores the tag, so turn the pixels.
        val rotation = rotationOf(original)
        if (rotation == 0 && mime in Attachment.INLINE_IMAGES && longEdge <= MAX_EDGE && original.length() <= MAX_IMAGE_BYTES) {
            return Attachment(original.path, name, mime, original.length())
        }
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return Attachment(original.path, name, "application/octet-stream", original.length())
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val scaled = if (scale < 1f || rotation != 0) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) else decoded
        val jpegName = name.substringBeforeLast('.') + ".jpg"
        val jpeg = File(original.parentFile, original.name.substringBefore('-') + "-" + jpegName)
        jpeg.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (jpeg != original) original.delete()
        return Attachment(jpeg.path, jpegName, "image/jpeg", jpeg.length())
    }

    private fun rotationOf(file: File): Int = runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val raw = queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        // One path segment, no characters that trip up a shell or Claude's path handling.
        return raw.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "_").take(80).ifBlank { "file" }
    }
}
