package com.pocketrocket.sessions.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.pocketrocket.files.FileKinds
import com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH
import java.io.File

/**
 * Content URIs for files under `~/.pocketrocket/attachments/`, through PocketRocket's own FileProvider: the camera
 * writes a photo into one, and other apps open an attachment through one.
 */
object AttachmentFiles {
    private fun authority(context: Context) = context.packageName + ".pocketrocket.files"

    /**
     * [file] sits under Termux's `/data/data/com.termux/files`, the FileProvider's root under [Context.getFilesDir]
     * (`/data/user/0/...`). On some phones those are one folder bind-mounted twice, not a symlink, so their canonical
     * paths differ and FileProvider finds no root; the path is moved onto filesDir first.
     */
    fun uriFor(context: Context, file: File): Uri {
        val files = File(TERMUX_FILES_DIR_PATH).absolutePath
        val path = file.absolutePath
        val onFilesDir = if (path.startsWith("$files/")) File(context.filesDir, path.removePrefix("$files/")) else file
        return FileProvider.getUriForFile(context, authority(context), onFilesDir)
    }

    /** A fresh file for the camera to write into. */
    fun cameraTarget(sessionId: String): File =
        File(Attachments.dir(sessionId).apply { mkdirs() }, "${System.currentTimeMillis()}-photo.jpg")

    /** Opens [attachment] in whatever app handles its type. False when none does, or when it is an app package. */
    fun open(context: Context, attachment: Attachment): Boolean {
        // Viewing hands the file over read-only, so a script only shows as text. An APK would start Android's
        // installer, and this app may install apps (its own updates), so packages are never opened from a chat.
        if (FileKinds.isPackage(attachment.path, attachment.mime)) return false
        // A file outside the shared folder has no content URI; say no app could open it rather than crash.
        val uri = runCatching { uriFor(context, File(attachment.path)) }.getOrElse { return false }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.mime.takeUnless { it == "application/octet-stream" } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
