package com.pocketrocket.usage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Where the app's bytes are. */
enum class StorageKind(val label: String, val note: String) {
    ClaudeCode("Claude Code", "Versions, accounts and conversations"),
    Packages("Packages", "Installed tools and their libraries"),
    Home("Projects and home", "Your projects and other files at home"),
    Cache("Cache", "Downloads that can be fetched again"),
    Other("App and other", "The app itself and its settings. Shared storage isn’t counted"),
}

/**
 * The split of the app's storage. [totalBytes] is Android's figure when it has one. [Other][StorageKind.Other]
 * is what's left of the total after the walked kinds, so the rows add up to it. [unreadableFolders] counts
 * folders the walk had to skip, which makes the split a little low. [links] carries the walk's hard link choices over
 * to [StorageTree], so what a row opens to adds up to the row.
 */
data class StorageBreakdown(
    val totalBytes: Long,
    val bytes: Map<StorageKind, Long>,
    val unreadableFolders: Int,
    val links: HardLinks = HardLinks(),
)

object StorageUsage {

    /**
     * Android's own count of the app's storage (APK, data and cache; data already includes cache), which
     * needs no permission for the app's own package. Null before Android 8 or if Android won't say.
     */
    fun totalBytes(context: Context): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            val stats = context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
            stats.appBytes + stats.dataBytes
        }.getOrNull()
    }

    /** The storage the app's data lives on, for the whole phone. Needs no permission. Null if Android won't say. */
    fun phone(context: Context): PhoneShare? = runCatching {
        val stat = StatFs(context.applicationInfo.dataDir)
        PhoneShare(stat.totalBytes, stat.totalBytes - stat.availableBytes)
    }.getOrNull()

    /** Walks the app's data folder, which can take a while with big projects. Call off the main thread; cancellable. */
    suspend fun breakdown(context: Context): StorageBreakdown {
        val job = currentCoroutineContext()
        val links = HardLinks()
        val walked = walkDisk(AndroidWalkFs, context.applicationInfo.dataDir, StorageKind.Other, FOLDERS, links) { job.ensureActive() }
        val named = StorageKind.entries.filter { it != StorageKind.Other }.associateWith { walked.bytes[it] ?: 0L }
        val namedTotal = named.values.sum()
        // Before Android 8 there's no system total: what the walk found plus the APK.
        val total = totalBytes(context)
            ?: (namedTotal + (walked.bytes[StorageKind.Other] ?: 0L) + File(context.applicationInfo.sourceDir).length())
        return StorageBreakdown(total, named + (StorageKind.Other to (total - namedTotal).coerceAtLeast(0)), walked.unreadable, links)
    }

    /** What's inside the rows of [breakdown], opened a level at a time. */
    fun tree(context: Context, breakdown: StorageBreakdown): StorageTree =
        StorageTree(AndroidWalkFs, context.applicationInfo.dataDir, breakdown.bytes, breakdown.links)

    /** The real install layout, relative to the app's data folder. Anything not listed takes its parent's kind. */
    internal val FOLDERS = mapOf(
        "files/usr" to StorageKind.Packages,
        "files/usr/tmp" to StorageKind.Cache,
        "files/home" to StorageKind.Home,
        "files/home/.pocketrocket/claude" to StorageKind.ClaudeCode,
        "files/home/.pocketrocket/accounts" to StorageKind.ClaudeCode,
        "files/home/.claude" to StorageKind.ClaudeCode,
        "files/home/.claude.json" to StorageKind.ClaudeCode,
        "files/home/.cache" to StorageKind.Cache,
        "files/home/.npm" to StorageKind.Cache,
        "cache" to StorageKind.Cache,
        "code_cache" to StorageKind.Cache,
    )
}
