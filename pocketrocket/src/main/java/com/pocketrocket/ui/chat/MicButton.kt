package com.pocketrocket.ui.chat

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The dictation mic in the chat box's row (decision 81): a plain mic that raises [dictator]'s listening panel. Always
 * there, with text in the box and while Claude works. Asks for the microphone on first use, with a plain reason first.
 */
@Composable
fun MicButton(dictator: Dictator, onHint: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val askForMic = micPermissionAsker(
        rationaleTitle = "Dictate with the microphone",
        rationale = "PocketRocket listens only while the mic is on. Android's speech recognition turns what you say into " +
            "text in the chat box (on the phone where it can), and nothing is sent until you tap Send.",
        blocked = "To dictate, allow the microphone for PocketRocket in its app settings.",
        onGranted = { dictator.start() },
        onRefused = { if (it == MicRefusal.Denied) onHint("Dictation needs the microphone.") },
    )

    fun tap() {
        when {
            !dictator.available -> onHint("Speech recognition isn't available on this phone.")
            !dictator.needsMic -> dictator.start()
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> dictator.start()
            else -> askForMic()
        }
    }

    Box(
        modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Dictate", onClick = ::tap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ChatIcons.Mic, contentDescription = "Dictate", tint = c.textSoft, modifier = Modifier.size(20.dp))
    }
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
