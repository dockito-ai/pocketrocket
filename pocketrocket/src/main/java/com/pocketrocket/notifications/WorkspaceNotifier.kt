package com.pocketrocket.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketrocket.MainActivity
import com.pocketrocket.R
import com.pocketrocket.workspaces.Workspace
import com.pocketrocket.workspaces.Workspaces

/**
 * "Claude added <name> as a workspace" (decision 72): posted when `pocketrocket-workspace` makes a new
 * workspace, so a workspace that appears from a session never goes unnoticed. Tapping opens the app.
 */
object WorkspaceNotifier {
    private const val CHANNEL = "pocketrocket_workspace_added"
    private const val ACCENT = 0xFFFF8A3D.toInt()

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, "Workspace added", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Claude or a terminal added a workspace" },
        )
    }

    /** [byClaude]: the request came from a Claude session (CLAUDECODE was set), else from a terminal. */
    fun added(context: Context, workspace: Workspace, byClaude: Boolean) = runCatching {
        val title = if (byClaude) "Claude added ${workspace.name} as a workspace" else "Added ${workspace.name} as a workspace"
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        @Suppress("DEPRECATION")
        val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_pr_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(Workspaces.tilde(workspace.path))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(context, workspace.path.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        manager(context).notify(workspace.path.hashCode(), builder.build())
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
