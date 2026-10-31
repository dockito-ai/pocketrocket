package com.pocketrocket

import android.content.Context
import com.pocketrocket.health.LastStop
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.storage.SharedStorage
import kotlin.concurrent.thread
import com.pocketrocket.termux.BootstrapInstaller
import com.pocketrocket.termux.StorageLinker

/**
 * The few hooks the Termux app module hands to PocketRocket at startup.
 * Set once from TermuxApplication.onCreate().
 */
object PocketRocket {

    @Volatile
    lateinit var bootstrapInstaller: BootstrapInstaller
        private set

    @JvmStatic
    fun setBootstrapInstaller(installer: BootstrapInstaller) {
        bootstrapInstaller = installer
    }

    @Volatile
    lateinit var storageLinker: StorageLinker
        private set

    @JvmStatic
    fun setStorageLinker(linker: StorageLinker) {
        storageLinker = linker
    }

    /**
     * App start: brings back saved sessions (paused) and their notifications and keep-alive, and
     * re-generates the account wiring (git and shell config) in case an older version wrote it,
     * and remakes ~/storage if storage access is given but the links are missing.
     */
    @JvmStatic
    fun init(context: Context) {
        LastStop.onProcessStart(context)
        com.pocketrocket.backup.BackupManager.recoverInterrupted()
        SessionManager.init(context)
        com.pocketrocket.notifications.CloneNotifier.init(context)
        com.pocketrocket.assistant.WakeWord.init(context)
        thread(name = "pocketrocket-accounts", isDaemon = true) { AccountWiring.refresh() }
        thread(name = "pocketrocket-storage", isDaemon = true) { SharedStorage.linkIfNeeded(context) }
    }
}
