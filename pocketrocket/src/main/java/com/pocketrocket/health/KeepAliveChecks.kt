package com.pocketrocket.health

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.pocketrocket.keepalive.SessionKeepAlive
import com.pocketrocket.health.HealthState.CantCheck
import com.pocketrocket.health.HealthState.NeedsAttention
import com.pocketrocket.health.HealthState.Ok
import com.pocketrocket.health.HealthState.Optional

/** KEEPING SESSIONS ALIVE: what lets Android (and Xiaomi) stop sessions in the background. */
internal object KeepAliveChecks {

    fun run(context: Context): HealthGroup = HealthGroup(
        HealthReport.KEEP_ALIVE,
        listOfNotNull(
            batterySaver(context),
            autostart(context),
            batteryOptimisation(context),
            service(),
            wakeLock(),
            processLimit(context),
            lockInRecents(),
        ),
    )

    /** Background restriction ("Restricted" battery use, or Xiaomi's battery saver for this app). */
    private fun batterySaver(context: Context): HealthItem {
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val fix = HealthFix.Open(SystemScreen.AppBattery)
        return if (restricted) HealthItem("Battery saver", NeedsAttention, "Restricted. Set to No restrictions", fix)
        else HealthItem("Battery saver", Ok, if (SystemScreens.isXiaomi) "Not restricted" else "Not restricted in the background")
    }

    /**
     * Xiaomi's Autostart has no public API. MIUI and HyperOS keep it as app op 10008 (OP_AUTO_START), which
     * AppOpsManager answers through a hidden method; when that read fails the row says it can't be checked.
     */
    private fun autostart(context: Context): HealthItem {
        if (!SystemScreens.isXiaomi) return HealthItem("Autostart", Ok, "Not needed on this phone")
        val fix = HealthFix.Open(SystemScreen.Autostart)
        return when (miuiAutostart(context)) {
            true -> HealthItem("Autostart", Ok, "On in Xiaomi settings")
            false -> HealthItem("Autostart", NeedsAttention, "Off in Xiaomi settings", fix)
            null -> HealthItem("Autostart", CantCheck, "Can’t be read. Check it is on", fix.copy(label = "Show me"))
        }
    }

    private fun miuiAutostart(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val method = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        when (method.invoke(ops, 10008, Process.myUid(), context.packageName) as Int) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            else -> null
        }
    }.getOrNull()

    private fun batteryOptimisation(context: Context): HealthItem {
        val ignoring = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        return if (ignoring) HealthItem("Ignore battery optimisation", Ok, "Allowed")
        else HealthItem("Ignore battery optimisation", NeedsAttention, "Not allowed. Android may pause sessions", HealthFix.Open(SystemScreen.BatteryExemption))
    }

    private fun service(): HealthItem {
        val busy = SessionKeepAlive.busyCount()
        val running = SessionKeepAlive.serviceRunning()
        fun n(count: Int) = "$count session${if (count == 1) "" else "s"}"
        return when {
            running && busy > 0 -> HealthItem("Background service", Ok, "Running · ${n(busy)}")
            running -> HealthItem("Background service", Ok, "Running · no session working")
            busy > 0 -> HealthItem("Background service", NeedsAttention, "Not running with ${n(busy)} working")
            else -> HealthItem("Background service", Ok, "Starts when a session works")
        }
    }

    private fun wakeLock(): HealthItem {
        val busy = SessionKeepAlive.busyCount()
        val held = SessionKeepAlive.wakeLockHeld()
        return when {
            held -> HealthItem("Wake lock", Ok, "Held now · a session is working or waiting")
            busy > 0 -> HealthItem("Wake lock", NeedsAttention, "Not held while a session is busy")
            else -> HealthItem("Wake lock", Ok, "Held while a session is working")
        }
    }

    /**
     * Android 12+ kills an app's child processes past 32 in all ("phantom processes"), and Claude Code runs
     * many. The limit is lifted with `settings put global settings_enable_monitor_phantom_procs false` over
     * wireless debugging, or Developer options › "Disable child process restrictions" on Android 14+.
     * Both land in a global setting or its persist property, which the app can read. Optional: lifting it
     * needs Developer options, which Shankar keeps off.
     */
    private fun processLimit(context: Context): HealthItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return HealthItem("Android process limit", Ok, "No limit on this Android version")
        val flag = PHANTOM_FLAG
        val setting = runCatching { Settings.Global.getString(context.contentResolver, flag) }.getOrNull()
        val prop = getprop("persist.sys.fflag.override.$flag")
        val lifted = listOf(setting, prop).any { it?.trim().equals("false", true) }
        val guide = HealthFix.Guide(
            title = "Lift Android's process limit",
            steps = listOf(
                if (Build.VERSION.SDK_INT >= 34) "Easiest on Android 14 and later: Developer options › Disable child process restrictions, turn it on."
                else "Android 12 and 13 need a computer or a second terminal app with adb.",
                "Or over wireless debugging: turn on Developer options › Wireless debugging, pair a terminal with adb pair, then run:",
                "adb shell settings put global settings_enable_monitor_phantom_procs false",
                "adb shell device_config set_sync_disabled_for_tests persistent",
                "adb shell device_config put activity_manager max_phantom_processes 2147483647",
                "The change survives restarts. Come back here and tap Check again.",
            ),
            screen = SystemScreen.DeveloperOptions,
            screenLabel = "Open Developer options",
            label = "Fix",
        )
        return if (lifted) HealthItem("Android process limit", Ok, "Lifted through wireless debugging")
        else HealthItem("Android process limit", Optional, "On (optional): Android may stop sessions when many run at once", guide)
    }

    private fun lockInRecents() = HealthItem(
        "Lock in recent apps",
        CantCheck,
        "Can’t be checked automatically",
        HealthFix.Guide(
            title = "Lock PocketRocket in recent apps",
            steps = if (SystemScreens.isXiaomi) listOf(
                "Open recent apps (swipe up from the bottom and hold).",
                "Long-press the PocketRocket card.",
                "Tap the lock icon. A small lock shows on the card.",
                "Clear all then leaves PocketRocket running.",
            ) else listOf(
                "Open recent apps.",
                "Tap the PocketRocket icon above its card, or long-press the card.",
                "Choose Lock (or Pin / Keep open) if your phone offers it.",
            ),
        ),
    )

    private const val PHANTOM_FLAG = "settings_enable_monitor_phantom_procs"

    private fun getprop(name: String): String? = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifEmpty { null }
    }.getOrNull()
}
