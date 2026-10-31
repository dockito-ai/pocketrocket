package com.pocketrocket.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException

/**
 * How the app's screens move (the same everywhere):
 * - push: the new screen slides in from the right over the current one, which stays put;
 * - back (button, gesture or a screen's own back arrow): the top screen slides out to the right and the
 *   screen underneath, which stayed composed the whole time, is revealed in place. Nothing re-creates.
 * - the predictive back gesture drags the top screen with the finger; letting go finishes or cancels it.
 */
@Stable
class StackMotion {
    /** How far the top screen is pushed to the right by a back in progress, px. */
    val back = Animatable(0f)
    var width by mutableStateOf(0f)
    /** The key of a screen just pushed, which starts off-screen and slides in. */
    var entering: String? = null
    var popping by mutableStateOf(false)
        private set

    /** Slides the top screen out, then runs [commit] (which drops it from the stack). */
    suspend fun popWith(commit: () -> Unit) {
        if (popping) return
        popping = true
        try {
            back.animateTo(width, tween(DURATION, easing = FastOutSlowInEasing))
            commit()
        } finally {
            back.snapTo(0f)
            popping = false
        }
    }

    /** The gesture version of [popWith]: follows [progress] (0 to 1), then finishes or springs back. */
    suspend fun gesture(progress: kotlinx.coroutines.flow.Flow<androidx.activity.BackEventCompat>, commit: () -> Unit) {
        popping = true
        try {
            progress.collect { back.snapTo(it.progress * width) }
            back.animateTo(width, tween(DURATION / 2))
            commit()
            back.snapTo(0f)
        } catch (e: CancellationException) {
            back.animateTo(0f, tween(DURATION / 2))
            throw e
        } finally {
            popping = false
        }
    }

    companion object {
        const val DURATION = 280
    }
}

/**
 * Draws the top two entries of [stack] (keys unique per stack position), the top one over the other,
 * each in its own saved-state scope from [states]. [onGestureBack] commits a back made with the gesture.
 */
@Composable
fun RouteStack(
    stack: List<String>,
    motion: StackMotion,
    states: SaveableStateHolder,
    onGestureBack: () -> Unit,
    content: @Composable (route: String) -> Unit,
) {
    PredictiveBackHandler(enabled = stack.size > 1) { progress -> motion.gesture(progress, onGestureBack) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        motion.width = width
        val first = (stack.size - 2).coerceAtLeast(0)
        for (index in first until stack.size) {
            val route = stack[index]
            val stateKey = entryKey(index, route)
            val isTop = index == stack.lastIndex
            key(stateKey) {
                val enter = remember { Animatable(if (motion.entering == stateKey) width else 0f) }
                LaunchedEffect(Unit) {
                    if (motion.entering == stateKey) motion.entering = null
                    if (enter.value != 0f) enter.animateTo(0f, tween(StackMotion.DURATION, easing = FastOutSlowInEasing))
                }
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = enter.value + if (isTop) motion.back.value else 0f }) {
                    CompositionLocalProvider(LocalRouteOnTop provides isTop) {
                        states.SaveableStateProvider(stateKey) { content(route) }
                    }
                }
            }
        }
    }
}

/**
 * Whether the screen reading it is the top of the stack. The screen under it stays composed, so a screen's own
 * BackHandler should be enabled only while this is true, or it would take a back meant for the screen above.
 */
val LocalRouteOnTop = compositionLocalOf { true }

/** The key of the entry at [index] of the stack: its position plus its route, so a route can repeat. */
fun entryKey(index: Int, route: String) = "$index:$route"
