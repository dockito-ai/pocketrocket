package com.pocketrocket.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Handing a file to another app from the file manager: Open with and Share, read-only, through the app's
 * documents provider (`TermuxDocumentsProvider`, which serves everything under home by its absolute path),
 * and Copy path. Each returns an error message, or null when it worked.
 */
object FileIntents {
    private val AUTHORITY = TermuxConstants.TERMUX_PACKAGE_NAME + ".documents"

    fun uri(file: File): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, file.absolutePath)

    /** The file's type from its extension, or null when unknown. */
    fun mime(name: String): String? =
        FileKinds.extension(name).takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    /** Lets the user pick an app to view [file] in. App packages are refused: they would start Android's installer. */
    fun openWith(context: Context, file: File): String? {
        val mime = mime(file.name)
        if (FileKinds.isPackage(file.name, mime)) return "App packages aren't opened from here. Share it instead."
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(file), mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(view, "Open with"))
    }

    /** The share sheet for [file]. */
    fun share(context: Context, file: File): String? {
        val uri = uri(file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime(file.name) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return start(context, Intent.createChooser(send, null))
    }

    fun copyPath(context: Context, path: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Path", path))
    }

    private fun start(context: Context, intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "No app can open this."
    } catch (e: SecurityException) {
        e.message ?: "Android didn't allow it."
    }
}
