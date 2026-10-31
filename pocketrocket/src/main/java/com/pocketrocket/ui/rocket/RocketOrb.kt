package com.pocketrocket.ui.rocket

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

/** How the orb moves: still, or pulsing while the approval screen listens. */
enum class OrbMotion(val durationMs: Int, val scaleTo: Float, val alphaTo: Float) {
    Still(0, 1f, 1f),
    Pulse(750, 1.09f, 1f),
}

private val FLAME = arrayOf(0f to Color(0xFFFFD9A8), 0.30f to Color(0xFFFF8A3D), 0.55f to Color(0xFFF0561E), 0.72f to Color(0x00F0561E))

/** The small orb on the approval screen (A-Approve), the one place it stays (decision 60). Holds still when the phone has animations off. */
@Composable
fun RocketOrb(size: Dp, motion: OrbMotion, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = motion != OrbMotion.Still && remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val progress = if (animate) {
        val moving by rememberInfiniteTransition(label = "orb").animateFloat(
            0f, 1f, infiniteRepeatable(tween(motion.durationMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbMotion",
        )
        moving
    } else {
        0f
    }
    Canvas(
        modifier.size(size).graphicsLayer {
            val scale = 1f + (motion.scaleTo - 1f) * progress
            scaleX = scale
            scaleY = scale
            alpha = 1f + (motion.alphaTo - 1f) * progress
        },
    ) {
        drawCircle(
            Brush.radialGradient(*FLAME, center = Offset(this.size.width * 0.5f, this.size.height * 0.56f), radius = this.size.width * 0.62f),
        )
    }
}
