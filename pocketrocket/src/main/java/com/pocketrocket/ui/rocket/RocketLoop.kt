package com.pocketrocket.ui.rocket

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrocket.assistant.AssistantPrefs
import com.pocketrocket.assistant.AudioRoute
import com.pocketrocket.assistant.VoiceLoop
import com.pocketrocket.assistant.rocketVoiceLoop
import com.pocketrocket.ui.chat.micPermissionAsker

/** The voice loop for a screen: follows the audio output and Quiet, and stops when the screen stops being in front or goes away. */
@Composable
internal fun rememberRocketLoop(rationale: String, canAskForMic: () -> Boolean = { true }): VoiceLoop {
    val context = LocalContext.current
    val settings by remember { AssistantPrefs.settings(context) }.collectAsState()
    var askForMic by remember { mutableStateOf({}) }
    val loop = remember {
        rocketVoiceLoop(context, askForMic = { askForMic.invoke() })
    }
    val asker = micPermissionAsker(
        rationaleTitle = "Talk to Rocket",
        rationale = rationale,
        blocked = "To talk to Rocket, allow the microphone for PocketRocket in its app settings.",
        onGranted = { loop.micAnswered(true) },
        onRefused = { loop.micAnswered(false) },
    )
    askForMic = { if (canAskForMic()) asker() else loop.micAnswered(false) }
    DisposableEffect(loop) {
        val stopWatching = AudioRoute(context).watch { loop.conditionsChanged() }
        onDispose {
            stopWatching()
            loop.release()
        }
    }
    LaunchedEffect(settings.speaker, settings.quietUntil) { loop.conditionsChanged() }
    // The mic and the voice belong to the screen in front: the popup coming up over the tab takes them over.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) loop.cancel() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    return loop
}
