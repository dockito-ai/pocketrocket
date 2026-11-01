package com.pocketrocket.apk

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pocketrocket.files.FileIntents
import java.io.File

/**
 * What an APK card's buttons do (decision 99). Each returns what to tell the user: an error, or for [saveToDownloads]
 * where the copy went; null when there is nothing to say.
 */
object ApkActions {
    const val MIME = "application/vnd.android.package-archive"
    /** How [saveToDownloads] starts when it worked. */
    const val SAVED = "Saved to Downloads/"

    /**
     * Android's package installer, through the same view intent `termux-open` sends (package-archive type, read
     * grant). The URI is the documents provider's, as in the file manager: the `.files` provider `termux-open` uses
     * refuses files unless `allow-external-apps` is on.
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.isFile) return gone(apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileIntents.uri(apk), MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(view)
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this phone installs APKs."
        } catch (e: SecurityException) {
            e.message ?: "Android didn't allow it."
        }
    }

    /** The share sheet, like `termux-open --send`. */
    fun share(context: Context, apk: File): String? = if (!apk.isFile) gone(apk) else FileIntents.share(context, apk)

    /**
     * Copies [apk] into the public Downloads folder: through MediaStore from Android 10, into the folder itself before.
     * Android picks a free name when one is taken. Returns "Saved to Downloads/<name>" or why it failed. Disk work: call
     * it off the main thread.
     */
    fun saveToDownloads(context: Context, apk: File): String {
        if (!apk.isFile) return gone(apk)
        return runCatching {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) viaMediaStore(context, apk) else intoFolder(apk)
            SAVED + name
        }.getOrElse { "Couldn't save it to Downloads: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun viaMediaStore(context: Context, apk: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Android didn't make the file")
        try {
            val out = resolver.openOutputStream(uri) ?: error("Android didn't open the file")
            out.use { sink -> apk.inputStream().use { it.copyTo(sink) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: apk.name
    }

    @Suppress("DEPRECATION")
    private fun intoFolder(apk: File): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        val target = freeName(downloads, apk.name)
        apk.copyTo(target)
        return target.name
    }

    /** [name] in [dir], or "name (1).apk", "name (2).apk" … when taken, as MediaStore does. */
    internal fun freeName(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(0) { it + 1 }.map { File(dir, if (it == 0) name else "$stem ($it)$ext") }.first { !it.exists() }
    }

    private fun gone(apk: File) = "${apk.name} isn't there any more."
}
