package com.pocketrocket.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.pocketrocket.PocketRocket
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.nio.file.Files

/**
 * Shared storage and Termux's ~/storage links to it. Once access is given the app makes the links itself
 * (what termux-setup-storage triggers), so no terminal command is needed.
 */
object SharedStorage {

    private val storageDir get() = TermuxConstants.TERMUX_STORAGE_HOME_DIR
    private val shared get() = File(storageDir, "shared")

    fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** ~/storage/shared leads to a readable shared storage. */
    fun linked() = shared.exists() && runCatching { shared.canonicalFile.list() != null }.getOrDefault(false)

    /**
     * With access given and the links missing, makes them and waits briefly for them to appear. Idempotent.
     * Upstream clears ~/storage before linking, so this only runs when ~/storage is absent or holds nothing
     * but links: user files are never touched. Returns whether ~/storage is linked afterwards.
     */
    @Synchronized
    fun linkIfNeeded(context: Context): Boolean {
        if (!granted(context)) return false
        if (linked()) return true
        if (!onlyLinksIn(storageDir)) return false
        PocketRocket.storageLinker.link(context.applicationContext)
        repeat(20) {
            if (linked()) return true
            Thread.sleep(100)
        }
        return linked()
    }

    private fun onlyLinksIn(dir: File): Boolean {
        val path = dir.toPath()
        if (Files.isSymbolicLink(path)) return false
        if (!dir.exists()) return true
        if (!dir.isDirectory) return false
        return dir.listFiles()?.all { Files.isSymbolicLink(it.toPath()) } ?: false
    }
}
