package com.pocketrocket.ui.components

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.notifications.NotificationPermission

/**
 * The first time something that needs notifications is turned on while Android has them off, explain and lead to the app's
 * notification settings (the app targets below Android 13, so there is no system prompt). Returns what to call instead of acting
 * straight away: it runs [then] either way, and says so if he declines.
 */
@Composable
fun rememberNotificationAsk(why: String): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    pending?.let { action ->
        val finish = {
            NotificationPermission.markAsked(context)
            pending = null
            action()
        }
        AlertDialog(
            onDismissRequest = {
                finish()
                Toast.makeText(context, "Notifications stay off, so Rocket can't alert you in the background.", Toast.LENGTH_LONG).show()
            },
            title = { Text("Turn on notifications?") },
            text = { Text("$why Android has notifications off for PocketRocket right now.") },
            confirmButton = { TextButton(onClick = { finish(); NotificationPermission.openSettings(context) }) { Text("Turn on") } },
            dismissButton = {
                TextButton(onClick = {
                    finish()
                    Toast.makeText(context, "Notifications stay off, so Rocket can't alert you in the background.", Toast.LENGTH_LONG).show()
                }) { Text("Not now") }
            },
        )
    }
    return { then -> if (NotificationPermission.shouldAsk(context)) pending = then else then() }
}
