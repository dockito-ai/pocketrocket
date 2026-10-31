package com.pocketrocket.ui.rocket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.assistant.WakeFiles
import com.pocketrocket.assistant.WakeInstall
import com.pocketrocket.assistant.WakeModel
import com.pocketrocket.assistant.WakeView
import com.pocketrocket.assistant.WakeWord
import com.pocketrocket.ui.chat.micPermissionAsker
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/** The Hey Rocket switch as the quick controls and Settings both use it: what to show, and what a tap does. */
class WakeWordSwitch(val view: WakeView, val toggle: () -> Unit)

/** Turning Hey Rocket on the first time: says what it fetches and how big, asks for the mic, and shows the download. */
@Composable
fun rememberWakeWordSwitch(): WakeWordSwitch {
    val context = LocalContext.current
    val c = PrTheme.colors
    val view by WakeWord.view.collectAsState()
    val install by WakeModel.state.collectAsState()
    var dialog by remember { mutableStateOf(false) }

    val askForMic = micPermissionAsker(
        rationaleTitle = "Listen for \"Hey Rocket\"",
        rationale = "While a session runs or the phone is charging, PocketRocket keeps the microphone open and a small model on the phone listens for its name. " +
            "No audio leaves the phone, and a notification with Stop shows while it listens.",
        blocked = "To use Hey Rocket, allow the microphone for PocketRocket in its app settings.",
        onGranted = {
            WakeWord.turnOn(context)
            if (WakeModel.state.value != WakeInstall.Installed) WakeModel.install(context)
        },
        onRefused = { dialog = false; WakeWord.refresh(context) },
    )

    if (dialog) {
        val state = install
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Hey Rocket") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (state) {
                        is WakeInstall.Downloading -> {
                            LinearProgressIndicator(progress = { state.done.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                            Text("${WakeFiles.megabytes(state.done)} of ${WakeFiles.megabytes(state.total)}", style = mockText(13.sp, color = c.textSoft))
                        }
                        WakeInstall.Unpacking -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Unpacking…", style = mockText(13.sp, color = c.textSoft))
                        }
                        WakeInstall.Installed -> Text("Ready.", style = mockText(14.sp, color = c.text))
                        is WakeInstall.Failed -> Text(state.message, style = mockText(14.sp, color = c.text))
                        else -> Text(
                            "It runs on the phone, so it needs a ${WakeFiles.megabytes(WakeFiles.downloadBytes)} download from sherpa-onnx's official GitHub releases, " +
                                "checked against pinned SHA-256s. It takes ${WakeFiles.megabytes(WakeFiles.installedBytes)} on the phone. Wi-Fi is a good idea.",
                            style = mockText(14.sp, color = c.textSoft),
                        )
                    }
                }
            },
            confirmButton = {
                when (state) {
                    WakeInstall.NotInstalled, is WakeInstall.Failed -> TextButton(onClick = askForMic) { Text(if (state is WakeInstall.Failed) "Try again" else "Download") }
                    is WakeInstall.Downloading -> TextButton(onClick = { WakeModel.cancel() }) { Text("Cancel") }
                    else -> TextButton(onClick = { dialog = false }) { Text("Done") }
                }
            },
            dismissButton = {
                if (state !is WakeInstall.Installed) TextButton(onClick = { dialog = false }) { Text(if (state is WakeInstall.Downloading || state == WakeInstall.Unpacking) "Hide" else "Not now") }
            },
            containerColor = c.card,
            titleContentColor = c.text,
        )
    }

    return WakeWordSwitch(view) {
        when {
            install == WakeInstall.Unsupported -> Unit
            install != WakeInstall.Installed -> dialog = true
            view.on -> WakeWord.turnOff(context)
            else -> askForMic()
        }
    }
}
