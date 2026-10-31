package com.pocketrocket.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object FileImages {
    /**
     * Decodes [file] for showing inline, scaled down by powers of two until its longer side is at most about
     * [max] px, so a big photo can't run the app out of memory. Null when Android can't decode it. Reads the
     * disk: call it off the main thread.
     */
    fun decode(file: File, max: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = FileKinds.sampleSize(bounds.outWidth, bounds.outHeight, max) }
        return BitmapFactory.decodeFile(file.path, options)
    }
}
