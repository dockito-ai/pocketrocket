package com.pocketrocket.ui.setup

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.components.mockText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.MockIcons
import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupRunner
import com.pocketrocket.setup.SetupState
import com.pocketrocket.setup.StepStatus

@Composable
fun SetupScreen(onFinished: () -> Unit) {
    val state by SetupRunner.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    LaunchedEffect(Unit) { if (state.phase == SetupPhase.Idle) SetupRunner.start(activity) }

    var showLog by rememberSaveable { mutableStateOf(false) }
    val page = rememberScrollState()
    // Opening the details brings the log into view as it expands.
    LaunchedEffect(showLog) { if (showLog) snapshotFlow { page.maxValue }.collect { page.scrollTo(it) } }

    Surface(Modifier.fillMaxSize(), color = PrTheme.colors.background, contentColor = PrTheme.colors.text) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(page)) {
                Spacer(Modifier.height(40.dp))
                Header(state)
                Spacer(Modifier.height(28.dp))
                AnimatedVisibility(state.phase == SetupPhase.Failed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ErrorCard(state.error.orEmpty(), onRetry = { SetupRunner.start(activity) })
                }
                Surface(shape = RoundedCornerShape(16.dp), color = PrTheme.colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, PrTheme.colors.iconTile)) {
                    StepList(state, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                DetailsToggle(showLog) { showLog = !showLog }
                AnimatedVisibility(showLog, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    SetupLog(state.log, Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
            Footer(state.phase, onFinished)
        }
    }
}

@Composable
private fun Header(state: SetupState) {
    val title = when (state.phase) {
        SetupPhase.Done -> "You're all set"
        SetupPhase.Failed -> "Setup stopped"
        else -> "Setting up PocketRocket"
    }
    val total = state.totalSteps
    val subtitle = when (state.phase) {
        SetupPhase.Done -> "Claude Code and your tools are installed."
        SetupPhase.Failed -> "Nothing is lost. Retry picks up where it left off."
        else -> if (total != null) "Step ${state.currentIndex + 1} of $total" else "Getting started"
    }

    AnimatedContent(title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") {
        Text(it, style = mockText(24.sp, FontWeight.Bold, letterSpacing = (-0.3).sp))
    }
    Spacer(Modifier.height(6.dp))
    AnimatedContent(subtitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") {
        Text(it, style = mockText(14.sp), color = PrTheme.colors.textMuted)
    }
    Spacer(Modifier.height(20.dp))
    OverallProgress(state)
}

@Composable
private fun OverallProgress(state: SetupState) {
    val total = state.totalSteps
    val modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    if (total == null && state.phase == SetupPhase.Running) {
        LinearProgressIndicator(modifier, color = PrTheme.colors.accent, trackColor = PrTheme.colors.border)
        return
    }
    val done = state.steps.count { it.status == StepStatus.Done }
    val running = if (state.steps.any { it.status == StepStatus.Running }) (state.progress ?: 0) / 100f else 0f
    val target = if (state.phase == SetupPhase.Done) 1f else (done + running) / (total ?: 1)
    val animated by animateFloatAsState(target, label = "overall")
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier,
        color = if (state.phase == SetupPhase.Failed) PrTheme.colors.red else PrTheme.colors.accent,
        trackColor = PrTheme.colors.border,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PrTheme.colors.red.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(1.dp, PrTheme.colors.redBorder),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = PrTheme.colors.redSoft)
                Spacer(Modifier.width(12.dp))
                Text("Something went wrong", style = mockText(15.sp, FontWeight.SemiBold), color = PrTheme.colors.redSoft)
            }
            Spacer(Modifier.height(8.dp))
            Text(message, style = mockText(14.sp), color = PrTheme.colors.redSoft)
            Spacer(Modifier.height(16.dp))
            OutlineButton("Retry", onClick = onRetry, modifier = Modifier.fillMaxWidth(), color = PrTheme.colors.redSoft, border = PrTheme.colors.redBorder)
        }
    }
}

@Composable
private fun DetailsToggle(expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    TextButton(onClick = onToggle) {
        Text(if (expanded) "Hide details" else "Show details", style = mockText(13.sp, color = PrTheme.colors.textMuted))
        Spacer(Modifier.width(4.dp))
        Icon(MockIcons.ChevronDown, contentDescription = null, tint = PrTheme.colors.textMuted, modifier = Modifier.size(16.dp).rotate(rotation))
    }
}

@Composable
private fun Footer(phase: SetupPhase, onFinished: () -> Unit) {
    // Headless Chrome is not offered here; it installs only from Settings → Tools (decision 40).
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (phase == SetupPhase.Done) {
            AccentButton("Continue", onClick = onFinished)
        } else if (phase == SetupPhase.Running) {
            Text(
                "You can leave the app. Setup keeps going in the background.",
                style = mockText(12.sp),
                color = PrTheme.colors.textMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
