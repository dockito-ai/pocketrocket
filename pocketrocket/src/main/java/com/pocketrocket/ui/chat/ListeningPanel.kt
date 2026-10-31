package com.pocketrocket.ui.chat

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrocket.dictation.ContinuousDictation
import com.pocketrocket.dictation.DictationSource
import com.pocketrocket.dictation.FakeDictation
import com.pocketrocket.dictation.SilenceTimer
import com.pocketrocket.dictation.Transcript
import com.pocketrocket.ui.theme.Inter
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlin.math.abs
import kotlin.random.Random

/**
 * One chat's dictation (decision 81, Dictation.html): what the listening panel shows, and where kept words go. The chat
 * box sets [onKeep] (insert at the cursor) and [onHint]. Debug builds with no recognizer (an emulator) use [FakeDictation].
 */
class Dictator(context: Context) : ContinuousDictation.Listener {
    var open by mutableStateOf(false)
        private set
    var settled by mutableStateOf("")
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var clock by mutableStateOf("0:00")
        private set
    var stillThere by mutableStateOf(false)
        private set
    var adding by mutableStateOf(false)
        private set

    var onKeep: (String) -> Unit = {}
    var onHint: (String) -> Unit = {}

    private val real = ContinuousDictation(context, this)
    private val fake = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) FakeDictation(this) else null
    private var source: DictationSource = real

    /** The phone can dictate (or, in a debug build, fake it). */
    val available: Boolean get() = real.isAvailable || fake != null

    /** The real recognizer will be used, so the mic permission is needed. */
    val needsMic: Boolean get() = real.isAvailable

    val text: String get() = Transcript.join(settled, partial)

    fun start() {
        if (open) return
        source = if (real.isAvailable) real else fake ?: return
        settled = ""; partial = ""; level = 0f; clock = "0:00"; stillThere = false; adding = false
        open = true
        source.start()
    }

    /** ✓ */
    fun keep() {
        if (!open) return
        adding = true
        source.finish()
    }

    /** ✕ or back, once confirmed. */
    fun discard() = source.cancel()

    override fun onTranscript(settled: String, partial: String) {
        this.settled = settled
        this.partial = partial
    }

    override fun onLevel(level: Float) { this.level = level }

    override fun onTick(clock: String, state: SilenceTimer.State) {
        this.clock = clock
        stillThere = state != SilenceTimer.State.Listening
    }

    override fun onEnd(text: String?, error: String?) {
        open = false
        adding = false
        level = 0f
        text?.let(onKeep)
        error?.let(onHint)
    }
}

/**
 * A [Dictator] for this screen. Leaving the screen keeps what was said (it goes into the box); the screen going away
 * for good throws it away. Either way the recognizer stops and its sounds come back.
 */
@Composable
fun rememberDictator(): Dictator {
    val context = LocalContext.current
    val dictator = remember { Dictator(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dictator.keep() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            dictator.discard()
        }
    }
    return dictator
}

private val PanelEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * [content] (the chat and its box) with the listening panel over it: the chat blurs and dims while the panel is up,
 * and taps on it do nothing. System back is ✕.
 */
@Composable
fun DictationOverlay(dictator: Dictator, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val open = dictator.open
    val dim by animateFloatAsState(if (open) 1f else 0f, tween(300), label = "dictationDim")
    var confirm by remember { mutableStateOf(false) }
    fun askDiscard() {
        if (Transcript.worthAsking(dictator.text)) confirm = true else dictator.discard()
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize()
                .then(if (dim > 0f) Modifier.blur((2.5f * dim).dp) else Modifier)
                .graphicsLayer { alpha = 1f - 0.65f * dim },
            content = content,
        )
        if (open) {
            // Swallows taps on the dimmed chat.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(350, easing = PanelEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(300, easing = PanelEasing)) { it } + fadeOut(tween(250)),
        ) {
            ListeningPanel(dictator, onDiscard = ::askDiscard)
        }
    }
    BackHandler(enabled = open) { askDiscard() }
    if (confirm && open) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Discard what you said?") },
            text = { Text("The words from this dictation are thrown away.") },
            confirmButton = { TextButton(onClick = { confirm = false; dictator.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep listening") } },
        )
    }
    LaunchedEffect(open) { if (!open) confirm = false }
}

@Composable
private fun ListeningPanel(dictator: Dictator, onDiscard: () -> Unit) {
    val c = PrTheme.colors
    val fade = Brush.verticalGradient(
        0f to c.background.copy(alpha = 0f),
        0.18f to c.background.copy(alpha = 0.92f),
        0.4f to c.background,
    )
    Column(
        Modifier.fillMaxWidth().background(fade).navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 18.dp, end = 18.dp, top = 70.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TranscriptText(dictator.settled, dictator.partial)
        Waveform(dictator.level, onTap = dictator::keep)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("Discard", ChatIcons.Close, filled = false, onClick = onDiscard)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dictator.clock, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, fontWeight = FontWeight.Medium), color = c.textSoft)
                Text(
                    when {
                        dictator.adding -> "adding…"
                        dictator.stillThere -> "still there?"
                        else -> "listening"
                    },
                    style = PrType.caption,
                    color = if (dictator.stillThere && !dictator.adding) c.accent else c.textMuted,
                )
            }
            RoundAction("Done", ChatIcons.Check, filled = true, onClick = dictator::keep)
        }
    }
}

/** Large, settled words in text colour and guesses muted; follows the newest words. */
@Composable
private fun TranscriptText(settled: String, partial: String) {
    val c = PrTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(settled, partial) { scroll.animateScrollTo(scroll.maxValue) }
    val big = TextStyle(fontFamily = Inter, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 200.dp).verticalScroll(scroll)) {
        if (settled.isBlank() && partial.isBlank()) {
            Text("Listening…", style = big.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal), color = c.textFaint)
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.text)) { append(settled) }
                    if (partial.isNotBlank()) {
                        if (settled.isNotBlank()) append(" ")
                        withStyle(SpanStyle(color = c.textMuted)) { append(partial) }
                    }
                },
                style = big,
            )
        }
    }
}

private const val BARS = 23

/** Bars that move with the voice over a soft orange glow that swells with it. Tapping it is ✓, as in the mockup. */
@Composable
private fun Waveform(level: Float, onTap: () -> Unit) {
    val c = PrTheme.colors
    val glow by animateFloatAsState(0.8f + level * 0.9f, tween(120), label = "glow")
    // A fresh wobble for each loudness reading, so the bars flicker like a voice rather than move as one.
    val wobble = remember(level) { FloatArray(BARS) { 0.6f + Random.nextFloat() * 0.4f } }
    Box(
        Modifier.fillMaxWidth().height(90.dp).clickable(
            interactionSource = null, indication = null, role = Role.Button, onClickLabel = "Done", onClick = onTap,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(120.dp).graphicsLayer { scaleX = glow; scaleY = glow }) {
            drawCircle(
                Brush.radialGradient(
                    0f to c.accent.copy(alpha = 0.45f), 0.7f to c.accent.copy(alpha = 0f),
                    center = Offset(size.width / 2, size.height / 2), radius = size.minDimension / 2,
                ),
            )
        }
        Row(Modifier.height(70.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until BARS) {
                val centre = 1f - abs(i - (BARS - 1) / 2f) / ((BARS - 1) / 2f)
                val target = maxOf(6f, 66f * level * (0.35f + 0.65f * centre) * wobble[i])
                val height by animateDpAsState(target.dp, tween(100), label = "bar")
                Box(Modifier.width(5.dp).height(height).clip(RoundedCornerShape(3.dp)).background(c.accent))
            }
        }
    }
}

@Composable
private fun RoundAction(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .background(if (filled) c.accent else c.raised)
            .then(if (filled) Modifier else Modifier.border(1.dp, c.borderStrong, CircleShape))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (filled) c.onAccent else c.text, modifier = Modifier.size(22.dp))
    }
}
