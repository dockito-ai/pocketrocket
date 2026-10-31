package com.pocketrocket.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pocketrocket.dictation.Dictation

/**
 * The dictation mic inside the chat box (R13). Tap to listen, tap again (or stop talking) to finish. Words stream
 * into the box through [onTextChange], after whatever was already typed ([currentText] when listening started);
 * nothing is ever sent from here. Asks for the microphone on first use, with a plain reason first.
 */
@Composable
fun MicButton(
    currentText: () -> String,
    onTextChange: (String) -> Unit,
    onListeningChange: (Boolean) -> Unit,
    onHint: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Changing this cancels listening (the message was sent, so late words must not refill the box). */
    cancelKey: Int = 0,
) {
    val context = LocalContext.current
    val mic = remember { MicState(context) }
    mic.currentText = currentText
    mic.onTextChange = onTextChange
    mic.onListeningChange = onListeningChange
    mic.onHint = onHint
    DisposableEffect(Unit) { onDispose { mic.engine.release() } }
    LaunchedEffect(cancelKey) { mic.cancel() }

    val askForMic = micPermissionAsker(
        rationaleTitle = "Dictate with the microphone",
        rationale = "PocketRocket listens only while the mic is on. Android's speech recognition turns what you say into " +
            "text in the chat box (on the phone where it can), and nothing is sent until you tap Send.",
        blocked = "To dictate, allow the microphone for PocketRocket in its app settings.",
        onGranted = { mic.start() },
        onRefused = { if (it == MicRefusal.Denied) onHint("Dictation needs the microphone.") },
    )

    fun tap() {
        when {
            mic.listening -> mic.engine.stop()
            !mic.engine.isAvailable -> onHint("Speech recognition isn't available on this phone.")
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> mic.start()
            else -> askForMic()
        }
    }

    MicVisual(mic.listening, mic.level, ::tap, modifier)
}

/** Listening state and the [Dictation] engine, kept across recompositions. Callbacks are refreshed each composition. */
private class MicState(context: Context) : Dictation.Listener {
    var listening by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var currentText: () -> String = { "" }
    var onTextChange: (String) -> Unit = {}
    var onListeningChange: (Boolean) -> Unit = {}
    var onHint: (String) -> Unit = {}
    val engine = Dictation(context, this)
    private var base = ""

    fun start() {
        base = currentText()
        markListening(true)
        engine.start()
    }

    fun cancel() {
        engine.release()
        if (listening) markListening(false)
    }

    override fun onText(text: String, final: Boolean) = onTextChange(join(base, text))

    override fun onLevel(level: Float) { this.level = level }

    override fun onEnd(error: String?) {
        markListening(false)
        error?.let(onHint)
    }

    private fun markListening(on: Boolean) {
        listening = on
        if (!on) level = 0f
        onListeningChange(on)
    }
}

/** Dictated words go after typed ones, with one space between. */
private fun join(base: String, spoken: String): String = when {
    base.isBlank() -> spoken
    base.endsWith(" ") || base.endsWith("\n") -> base + spoken
    else -> "$base $spoken"
}

/**
 * The mockup's orange mic. Listening: a soft ring around it that breathes and swells with the voice. Rocket's talk button
 * shares it with its own [icon] and [label].
 */
@Composable
internal fun MicVisual(
    listening: Boolean,
    level: Float,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector = ChatIcons.Mic,
    label: String = if (listening) "Stop dictation" else "Dictate",
) {
    val c = PrTheme.colors
    val primary = c.accent
    val swell by animateFloatAsState(if (listening) level else 0f, spring(stiffness = 300f), label = "micLevel")
    val breath by rememberInfiniteTransition(label = "micBreath").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier.fillMaxSize().scale(1f + 0.12f * breath + 0.45f * swell)
                    .background(primary.copy(alpha = 0.18f + 0.17f * swell), CircleShape),
            )
        }
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .background(primary)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = c.onAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
