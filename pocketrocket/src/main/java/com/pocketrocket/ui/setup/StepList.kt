package com.pocketrocket.ui.setup

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.components.mockText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.SetupStep
import com.pocketrocket.setup.StepStatus
import kotlinx.coroutines.delay

/**
 * How [StepList] draws: the setup screen's numbered 28 dp dots, or the tool page's small ones
 * (Tools.dc.html: 22 dp, green done, blue running, the failed step's error under it).
 */
enum class StepListStyle { Setup, Compact }

/**
 * The setup steps: done ones checked, the running one with its progress, then how many are still to come.
 * With [upcoming] (every step's title, in order), the steps not reached yet are listed by name instead.
 */
@Composable
fun StepList(
    state: SetupState,
    modifier: Modifier = Modifier,
    style: StepListStyle = StepListStyle.Setup,
    upcoming: List<String> = emptyList(),
) {
    val compact = style == StepListStyle.Compact
    val later = upcoming.drop(state.steps.size)
    Column(modifier, verticalArrangement = if (compact) Arrangement.spacedBy(12.dp) else Arrangement.Top) {
        state.steps.forEachIndexed { index, step ->
            key(index) {
                val appear = remember { MutableTransitionState(index == 0 || compact).apply { targetState = true } }
                AnimatedVisibility(appear, enter = fadeIn() + expandVertically()) {
                    val running = step.status == StepStatus.Running
                    val live = if (running) liveDetail(state) else null
                    val elapsed = if (running) live?.elapsed else null
                    if (compact) {
                        CompactStepRow(step, if (running) state.progress else null, live?.text, if (step.status == StepStatus.Failed) state.error else null, elapsed)
                    } else {
                        StepRow(number = index + 1, step = step, progress = if (running) state.progress else null, detail = live?.text, elapsed = elapsed)
                    }
                }
            }
        }
        if (later.isNotEmpty()) {
            later.forEachIndexed { i, title ->
                val step = SetupStep(title, StepStatus.Pending)
                if (compact) CompactStepRow(step, null, null, null, null) else StepRow(state.steps.size + i + 1, step, null, null, null)
            }
            return@Column
        }
        AnimatedVisibility(state.remainingSteps > 0, enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
            val n = state.remainingSteps
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(number = state.steps.size + 1, status = StepStatus.Pending)
                Spacer(Modifier.width(16.dp))
                Text(
                    if (n == 1) "1 more step" else "$n more steps",
                    style = mockText(14.sp),
                    color = PrTheme.colors.textMuted,
                )
            }
        }
    }
}

/** The running step's detail line and its elapsed time ("2:14"), as of the last tick. */
private class LiveDetail(val text: String?, val elapsed: String?)

/** Seconds without a script line before the row says it is still working. */
private const val QUIET_MS = 20_000L

/**
 * Ticks once a second while a step runs, so the timer moves even when the script is silent. After
 * [QUIET_MS] with no new line, a step with no detail of its own says "Still working…" (the script names the
 * item itself during package installs: "Still working on git…").
 */
@Composable
private fun liveDetail(state: SetupState): LiveDetail {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    val quiet = state.lastLineAt?.let { now - it >= QUIET_MS } == true
    val text = when {
        !quiet -> state.detail
        state.detail == null -> "Still working…"
        state.detail.startsWith("Still working") -> state.detail
        else -> "${state.detail} · still working…"
    }
    return LiveDetail(text, state.stepStartedAt?.let { elapsedText(now - it) })
}

internal fun elapsedText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Elapsed(text: String?) {
    text?.let { Text(it, style = mockText(12.sp, FontWeight.Medium), color = PrTheme.colors.textMuted, modifier = Modifier.padding(start = 8.dp)) }
}

@Composable
private fun StepRow(number: Int, step: SetupStep, progress: Int?, detail: String?, elapsed: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        StatusDot(number, step.status)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step.title,
                    style = mockText(14.sp, if (step.status == StepStatus.Running) FontWeight.SemiBold else FontWeight.Normal),
                    color = when (step.status) {
                        StepStatus.Failed -> PrTheme.colors.red
                        StepStatus.Pending -> PrTheme.colors.textMuted
                        else -> PrTheme.colors.text
                    },
                    modifier = Modifier.weight(1f),
                )
                Elapsed(elapsed)
            }
            AnimatedVisibility(step.status == StepStatus.Running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                RunningDetail(progress, detail)
            }
        }
    }
}

@Composable
private fun RunningDetail(progress: Int?, detail: String?) {
    Column(Modifier.padding(top = 10.dp)) {
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = PrTheme.colors.accent, trackColor = PrTheme.colors.border)
        } else {
            val animated by animateFloatAsState(progress / 100f, label = "stepProgress")
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().clip(CircleShape), color = PrTheme.colors.accent, trackColor = PrTheme.colors.border, gapSize = 0.dp, drawStopIndicator = {})
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                detail ?: "Working…",
                style = mockText(12.sp),
                color = PrTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (progress != null) {
                Text("$progress%", style = mockText(12.sp, FontWeight.Medium), color = PrTheme.colors.textMuted)
            }
        }
    }
}

/** One step on the tool page: a 22 dp dot, the title, and the running step's detail and bar or the failed step's error. */
@Composable
private fun CompactStepRow(step: SetupStep, progress: Int?, detail: String?, error: String?, elapsed: String?) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompactDot(step.status)
        Column(Modifier.weight(1f).padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pending = step.status == StepStatus.Pending
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step.title, style = mockText(14.sp, if (pending) FontWeight.Normal else FontWeight.Medium), color = if (pending) c.textMuted else c.text, modifier = Modifier.weight(1f))
                Elapsed(elapsed)
            }
            if (step.status == StepStatus.Running) {
                Row {
                    Text(detail ?: "Working…", style = mockText(12.sp), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (progress != null) Text("$progress%", style = mockText(12.sp, FontWeight.Medium), color = c.textMuted, modifier = Modifier.padding(start = 8.dp))
                }
                val track = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (progress == null) {
                    LinearProgressIndicator(track, color = c.blue, trackColor = c.border, gapSize = 0.dp)
                } else {
                    val animated by animateFloatAsState(progress / 100f, label = "stepProgress")
                    LinearProgressIndicator(progress = { animated }, modifier = track, color = c.blue, trackColor = c.border, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
            error?.let { Text(it, style = mockText(12.sp).copy(lineHeight = (12 * 1.45).sp), color = c.redSoft) }
        }
    }
}

@Composable
private fun CompactDot(status: StepStatus) {
    val c = PrTheme.colors
    val dot = Modifier.size(22.dp).clip(CircleShape)
    when (status) {
        StepStatus.Done -> Box(dot.background(c.green.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = c.green, modifier = Modifier.size(14.dp))
        }
        StepStatus.Running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.blue)
        StepStatus.Failed -> Box(dot.background(c.redSoft.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text("!", style = mockText(12.sp, FontWeight.Bold), color = c.redSoft)
        }
        StepStatus.Pending -> Box(dot.border(1.5.dp, c.textFaint, CircleShape))
    }
}

/** A 28dp circle showing the step's state; animates between states. */
@Composable
private fun StatusDot(number: Int, status: StepStatus) {
    AnimatedContent(status, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() }, label = "status") { s ->
        val colors = PrTheme.colors
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (s) {
                StepStatus.Done -> Box(Modifier.size(28.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = colors.onAccent, modifier = Modifier.size(18.dp))
                }
                StepStatus.Running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = colors.accent)
                StepStatus.Failed -> Box(Modifier.size(28.dp).clip(CircleShape).background(colors.red), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Failed", tint = colors.onAmber, modifier = Modifier.size(18.dp))
                }
                StepStatus.Pending -> Box(Modifier.size(28.dp).border(1.5.dp, colors.border, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$number", style = mockText(12.sp, FontWeight.Medium, mono = true), color = colors.textMuted)
                }
            }
        }
    }
}
