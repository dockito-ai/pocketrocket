package com.pocketrocket.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Android's own screens for the assistant role and for opening over other apps. An app can't take the role itself: only the person can pick it there. */
object AndroidAssist {
    /** Whether PocketRocket is the phone's assistant; null before Android 10, which can't say. */
    fun isDefault(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java)
        return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /** The page with "Digital assistant app"; phones that lack it get their default apps page, then Settings itself. */
    fun openSetting(context: Context) {
        val tried = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
        tried.firstOrNull { action -> runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }

    fun openOverlaySetting(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openSetting(context) }
    }
}
