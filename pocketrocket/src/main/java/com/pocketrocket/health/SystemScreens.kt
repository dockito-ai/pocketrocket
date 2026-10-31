package com.pocketrocket.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** The system screens a Health row can open. */
sealed interface SystemScreen {
    /** The per-app battery screen (Xiaomi: "Battery saver" for this app, set to No restrictions). */
    data object AppBattery : SystemScreen

    /** Xiaomi / HyperOS Autostart list. */
    data object Autostart : SystemScreen

    /** Android's "Let app always run in background?" dialog. */
    data object BatteryExemption : SystemScreen

    /** App info (permissions live there). */
    data object AppDetails : SystemScreen

    data object AppNotifications : SystemScreen

    /** One notification channel's settings. */
    data class Channel(val id: String) : SystemScreen

    data object DeveloperOptions : SystemScreen

    data object Storage : SystemScreen
}

/**
 * Turns a [SystemScreen] into intents, the phone maker's own first, Android's generic ones after, and opens
 * the first one the phone has. Xiaomi's components are the ones MIUI and HyperOS use (security centre and
 * power keeper); they cannot be tried on the emulator.
 */
object SystemScreens {

    private const val MIUI_SECURITY = "com.miui.securitycenter"
    private const val MIUI_POWER = "com.miui.powerkeeper"

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun intents(context: Context, screen: SystemScreen): List<Intent> {
        val pkg = context.packageName
        val pkgUri = Uri.fromParts("package", pkg, null)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        return when (screen) {
            SystemScreen.AppBattery -> listOfNotNull(
                if (isXiaomi) Intent().setComponent(ComponentName(MIUI_POWER, "$MIUI_POWER.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", pkg).putExtra("package_label", label) else null,
                if (isXiaomi) Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                    .putExtra("package_name", pkg).putExtra("package_label", label) else null,
                // Android 14+: the app's own battery page (Unrestricted / Optimised / Restricted).
                if (Build.VERSION.SDK_INT >= 34) Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", pkgUri) else null,
                details,
            )
            SystemScreen.Autostart -> listOf(
                Intent().setComponent(ComponentName(MIUI_SECURITY, "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                details,
            )
            SystemScreen.BatteryExemption -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            SystemScreen.AppDetails -> listOfNotNull(
                if (isXiaomi) Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(
                    ComponentName(MIUI_SECURITY, "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                ).putExtra("extra_pkgname", pkg) else null,
                details,
            )
            SystemScreen.AppNotifications -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
                details,
            )
            is SystemScreen.Channel -> listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, screen.id),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
                details,
            )
            SystemScreen.Storage -> listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), details)
            SystemScreen.DeveloperOptions -> listOf(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            )
        }
    }

    /** True when the phone has some screen for [screen] beyond plain App info. */
    fun has(context: Context, screen: SystemScreen): Boolean = intents(context, screen).any { resolves(context, it) }

    /** Opens the first intent that resolves and starts. Returns false when none did. */
    fun open(context: Context, screen: SystemScreen): Boolean {
        for (intent in intents(context, screen)) {
            if (!resolves(context, intent)) continue
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return true
        }
        return false
    }

    private fun resolves(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
