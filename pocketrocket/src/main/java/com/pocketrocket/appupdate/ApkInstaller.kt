package com.pocketrocket.appupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Hands a verified APK to Android's installer through a PackageInstaller session. Android asks Shankar to
 * confirm, then replaces the app, which ends this process; sessions come back Paused and resume.
 */
object ApkInstaller {
    private const val ACTION_STATUS = "com.pocketrocket.appupdate.INSTALL_STATUS"

    /** Whether "Install unknown apps" is allowed for this app (always true before Android 8). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system screen where "Install unknown apps" is turned on for this app. */
    fun unknownAppsSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** The package name and versionCode inside [apk], or null when Android can't read it. */
    fun inspect(context: Context, apk: File): Pair<String, Long>? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return null
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return info.packageName to code
    }

    fun installedVersionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    /** Writes [apk] into a new installer session and commits it. Blocking (copies the file). */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val intent = Intent(ACTION_STATUS).setClass(context, InstallStatusReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Installer status: opens Android's confirm screen when it asks for one, reports anything else. */
    class InstallStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    AppUpdater.onInstallerStatus(waiting = true, message = null)
                }
                PackageInstaller.STATUS_SUCCESS -> AppUpdater.onInstallerStatus(waiting = false, message = null)
                else -> {
                    val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    val message = when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled."
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android refused the update: it conflicts with the installed app${detail?.let { " ($it)" } ?: ""}."
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android refused the update: it isn't compatible with this phone${detail?.let { " ($it)" } ?: ""}."
                        else -> "Android couldn't install it${detail?.let { ": $it" } ?: "."}"
                    }
                    AppUpdater.onInstallerStatus(waiting = false, message = message)
                }
            }
        }
    }
}
