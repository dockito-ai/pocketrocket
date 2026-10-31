package com.pocketrocket.termux

import android.content.Context

/** Makes ~/storage and its links to shared storage (in the background). Backed by TermuxInstaller.setupStorageSymlinks. */
fun interface StorageLinker {
    fun link(context: Context)
}
