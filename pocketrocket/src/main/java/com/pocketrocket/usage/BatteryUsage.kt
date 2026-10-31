package com.pocketrocket.usage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.pocketrocket.health.HealthFix
import com.pocketrocket.health.SystemScreen
import com.pocketrocket.health.SystemScreens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** What Android says about the app's battery use. */
sealed interface BatteryReading {
    /** Android refused: the adb grants are missing. */
    data object NeedsGrant : BatteryReading

    /** Android's estimate since the last charge. [capacityMah] and [onBattery] are null if it didn't say. */
    data class Figure(val mah: Double, val capacityMah: Int?, val onBattery: String?) : BatteryReading

    /** Android answered and has no line for this app, so nothing has been attributed to it since the last charge. */
    data class NothingRecorded(val onBattery: String?) : BatteryReading

    /** Android answered with something this code can't read, so no figure is shown rather than a wrong one. */
    data object Unreadable : BatteryReading
}

/**
 * Battery use comes from `dumpsys batterystats`, the one route that works from a normal app (decision 47):
 * it needs DUMP and PACKAGE_USAGE_STATS, granted once over adb. BatteryStatsManager's getBatteryUsageStats is
 * a hidden API that Android blocks for apps, so it is not used.
 */
object BatteryUsage {

    private const val DUMP = "android.permission.DUMP"
    private const val USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"
    private const val TIMEOUT_MS = 15_000L

    /** Runs dumpsys off the main thread and never throws. Android's refusal comes back as [BatteryReading.NeedsGrant]. */
    suspend fun read(context: Context): BatteryReading = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(DUMP) != PackageManager.PERMISSION_GRANTED) return@withContext BatteryReading.NeedsGrant
        runCatching {
            coroutineScope {
                val process = ProcessBuilder("/system/bin/dumpsys", "batterystats", "--charged", context.packageName)
                    .redirectErrorStream(true).start()
                val watchdog = launch {
                    delay(TIMEOUT_MS)
                    process.destroy()
                }
                try {
                    runInterruptible { process.inputStream.bufferedReader().useLines { parse(it, uidLabel(Process.myUid())) } }
                } finally {
                    watchdog.cancel()
                    process.destroy()
                }
            }
        }.getOrDefault(BatteryReading.Unreadable)
    }

    /** The name dumpsys gives a uid: u0a221 for app 10221 of user 0. */
    internal fun uidLabel(uid: Int): String = "u${uid / 100_000}a${uid % 100_000 - 10_000}"

    /** Reads the output as it streams and stops at this app's line, so the long report is never held in memory. */
    internal fun parse(lines: Sequence<String>, uid: String): BatteryReading {
        val own = Regex("^\\s*UID ${Regex.escape(uid)}: ([0-9.]+)")
        var sinceCharge = false
        var inPowerSection = false
        var capacity: Int? = null
        var onBattery: String? = null
        for (line in lines) {
            val text = line.trim()
            when {
                text.startsWith("Permission Denial") -> return BatteryReading.NeedsGrant
                text.startsWith("Statistics since last charge") -> sinceCharge = true
                text.startsWith("Time on battery:") && onBattery == null -> onBattery = duration(text)
                text.startsWith("Estimated power use") -> inPowerSection = true
                inPowerSection && text.startsWith("Capacity:") -> capacity = CAPACITY.find(text)?.groupValues?.get(1)?.toIntOrNull()
                inPowerSection -> {
                    val mah = own.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                    return if (sinceCharge) BatteryReading.Figure(mah, capacity?.takeIf { it > 0 }, onBattery) else BatteryReading.Unreadable
                }
            }
        }
        return if (sinceCharge && inPowerSection) BatteryReading.NothingRecorded(onBattery) else BatteryReading.Unreadable
    }

    /** "2m 16s" from "Time on battery: 2m 16s 378ms (100.0%) realtime, ...". */
    private fun duration(line: String): String? =
        line.substringAfter(':').substringBefore('(').trim().split(' ').filterNot { it.endsWith("ms") }.joinToString(" ").ifBlank { null }

    private val CAPACITY = Regex("Capacity: ([0-9]+)")

    /** The steps for the grants, copyable. They hold what granting really opens up, and how to undo it. */
    fun guide(context: Context): HealthFix.Guide {
        val pkg = context.packageName
        return HealthFix.Guide(
            title = "Show battery use",
            steps = listOfNotNull(
                "Android only shares per-app battery figures with apps granted two permissions over adb, once.",
                if (SystemScreens.isXiaomi) "On Xiaomi phones first turn on Developer options › USB debugging (Security settings), or the grant may be refused." else null,
                "Turn on Developer options › Wireless debugging (or USB debugging with a computer), connect adb, then run:",
                "adb shell pm grant $pkg $DUMP",
                "adb shell pm grant $pkg $USAGE_STATS",
                "These apply to everything running in PocketRocket, Claude Code included: any of it could then read Android’s system reports and app usage history. To undo:",
                "adb shell pm revoke $pkg $DUMP",
                "adb shell pm revoke $pkg $USAGE_STATS",
                "Come back here and the figure appears.",
            ),
            screen = SystemScreen.DeveloperOptions,
            screenLabel = "Open Developer options",
        )
    }
}
