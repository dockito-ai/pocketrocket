package com.pocketrocket.ui.chat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** How asking for the mic ended without it: refused at Android's prompt, "Not now" in ours, or refused for good earlier. */
enum class MicRefusal { Denied, NotNow, Blocked }

/**
 * Asks for the microphone with a plain reason first, and sends you to the app's settings once Android won't ask any
 * more. Shows its own dialogs; returns the function that starts asking.
 */
@Composable
fun micPermissionAsker(
    rationaleTitle: String,
    rationale: String,
    blocked: String,
    onGranted: () -> Unit,
    onRefused: (MicRefusal) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var dialog by remember { mutableStateOf<MicDialog?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> onGranted()
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false -> {
                dialog = MicDialog.Blocked
                onRefused(MicRefusal.Blocked)
            }
            else -> onRefused(MicRefusal.Denied)
        }
    }

    when (dialog) {
        MicDialog.Rationale -> AlertDialog(
            onDismissRequest = { dialog = null; onRefused(MicRefusal.NotNow) },
            title = { Text(rationaleTitle) },
            text = { Text(rationale) },
            confirmButton = { TextButton(onClick = { dialog = null; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { dialog = null; onRefused(MicRefusal.NotNow) }) { Text("Not now") } },
        )
        MicDialog.Blocked -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Microphone is off") },
            text = { Text(blocked) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Not now") } },
        )
        null -> {}
    }
    return { dialog = MicDialog.Rationale }
}

private enum class MicDialog { Rationale, Blocked }
