package com.pocketrocket.ui.auth

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.components.mockText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.pocketrocket.ui.theme.JetBrainsMono
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pocketrocket.auth.ClaudeAccount
import com.pocketrocket.auth.ClaudeLogin
import com.pocketrocket.auth.LoginState
import com.pocketrocket.ui.components.IconBadge
import com.pocketrocket.ui.components.RocketMark
import java.io.File

/**
 * Sign in to Claude with a Claude subscription. One button opens claude.ai in a sheet; when the user
 * finishes there, Claude Code's localhost callback completes the sign-in and the sheet closes by itself.
 * Fallback: a "paste the code" step. [configDir] is the Claude Code config dir to sign in (null: default).
 */
@Composable
fun SignInScreen(
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String,
    configDir: File? = null,
) {
    val activity = LocalContext.current as Activity
    val state by ClaudeLogin.state.collectAsState()
    val tabOpen by AuthTab.isOpen.collectAsState()
    var pasteMode by rememberSaveable { mutableStateOf(false) }

    val surface = PrTheme.colors.background
    val openTab = { url: String -> AuthTab.open(activity, url, surface.toArgb(), surface.luminance() < 0.5f) }

    LaunchedEffect(Unit) { AuthTab.warmUp(activity) }

    // A new attempt opens the sheet straight away; without the localhost URL it goes to the paste step.
    val waiting = state as? LoginState.Waiting
    LaunchedEffect(waiting?.attempt) {
        val w = waiting ?: return@LaunchedEffect
        if (w.browserUrl == null) pasteMode = true
        val url = if (pasteMode) w.manualUrl else w.browserUrl
        if (url == null || !openTab(url)) pasteMode = true
    }

    val leave = { ClaudeLogin.cancel(); pasteMode = false; onDismiss() }
    BackHandler {
        when {
            pasteMode && state is LoginState.Waiting -> pasteMode = false
            state is LoginState.SignedIn -> { ClaudeLogin.reset(); onDone() }
            else -> leave()
        }
    }

    Surface(Modifier.fillMaxSize(), color = PrTheme.colors.background, contentColor = PrTheme.colors.text) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val stage = when (state) {
                LoginState.Idle, LoginState.Starting -> Stage.Intro
                is LoginState.Waiting -> if (pasteMode) Stage.Paste else Stage.Waiting
                LoginState.Verifying -> Stage.Verifying
                is LoginState.SignedIn -> Stage.Done
                is LoginState.Failed -> Stage.Failed
            }
            AnimatedContent(
                stage,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "signin",
            ) { st ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (st) {
                        Stage.Intro -> IntroBody()
                        Stage.Waiting -> WaitingBody(tabOpen)
                        Stage.Paste -> (state as? LoginState.Waiting)?.let { PasteBody(it, openTab) }
                        Stage.Verifying -> StatusBody(null, "Checking your account", "Claude Code is finishing the sign-in.")
                        Stage.Done -> (state as? LoginState.SignedIn)?.let { DoneBody(it.account) }
                        Stage.Failed -> StatusBody(Icons.Filled.Warning, "Sign-in didn't finish", (state as? LoginState.Failed)?.message.orEmpty(), error = true)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(bottom = 16.dp)) {
                when (val s = state) {
                    LoginState.Idle, LoginState.Starting -> {
                        PrimaryButton(if (s == LoginState.Starting) "Opening claude.ai" else "Sign in with Claude", busy = s == LoginState.Starting) {
                            pasteMode = false
                            ClaudeLogin.start(activity, configDir)
                        }
                        SecondaryButton(dismissLabel, onClick = leave)
                    }
                    is LoginState.Waiting -> if (!pasteMode) {
                        PrimaryButton("Open claude.ai again") { (s.browserUrl ?: s.manualUrl)?.let { openTab(it) } }
                        SecondaryButton("Paste a code instead") {
                            pasteMode = true
                            s.manualUrl?.let { openTab(it) }
                        }
                        SecondaryButton("Cancel", onClick = leave)
                    } else {
                        SecondaryButton("Cancel", onClick = leave)
                    }
                    LoginState.Verifying -> Spacer(Modifier.height(56.dp))
                    is LoginState.SignedIn -> PrimaryButton("Continue") { ClaudeLogin.reset(); onDone() }
                    is LoginState.Failed -> {
                        PrimaryButton("Try again") { pasteMode = false; ClaudeLogin.start(activity, configDir) }
                        SecondaryButton("Cancel", onClick = leave)
                    }
                }
            }
        }
    }
}

private enum class Stage { Intro, Waiting, Paste, Verifying, Done, Failed }

@Composable
private fun IntroBody() {
    Spacer(Modifier.height(56.dp))
    RocketMark(size = 88.dp)
    Spacer(Modifier.height(28.dp))
    Text("Sign in to Claude", style = mockText(24.sp, FontWeight.Bold, letterSpacing = (-0.3).sp), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "Use your Claude Pro or Max plan. Claude Code runs on your subscription, with no API key to manage.",
        style = mockText(15.sp).copy(lineHeight = 21.sp),
        color = PrTheme.colors.textMuted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(36.dp))
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PrTheme.colors.surface)
            .border(1.dp, PrTheme.colors.iconTile, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Point(Icons.Filled.AccountCircle, "Sign in on claude.ai", "Google, email or SSO, the same as on the web. It opens right here.")
        Point(Icons.Filled.Lock, "Your password stays with Anthropic", "PocketRocket never sees it. Claude Code keeps its own sign-in on this phone.")
        Point(Icons.Filled.Star, "Your plan, your limits", "Sessions use your Claude subscription's usage.")
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun WaitingBody(tabOpen: Boolean) {
    if (tabOpen) {
        // Only the strip above the sheet is visible: keep a compact header there.
        Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            RocketMark(size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Signing in to Claude", style = mockText(15.sp, FontWeight.SemiBold))
                Text(
                    "Finish below. It closes by itself.",
                    style = mockText(14.sp),
                    color = PrTheme.colors.textMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = PrTheme.colors.accent)
        }
        return
    }
    StatusBody(
        null,
        "Waiting for claude.ai",
        "The sign-in page was closed before you finished. Open it again, or paste a code if the page gave you one.",
    )
}

@Composable
private fun PasteBody(waiting: LoginState.Waiting, openTab: (String) -> Boolean) {
    val context = LocalContext.current
    var code by rememberSaveable(waiting.attempt) { mutableStateOf("") }
    var clip by remember { mutableStateOf<String?>(null) }

    // Check the clipboard whenever we come back to the app: the code page has a Copy button.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(waiting.attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            clip = readClipboard(context)?.takeIf { ClaudeLogin.looksLikeCode(it, waiting) && it.trim() != code.trim() }
        }
    }
    val valid = ClaudeLogin.looksLikeCode(code, waiting)

    Spacer(Modifier.height(48.dp))
    IconBadge(Icons.Filled.Lock, size = 64.dp)
    Spacer(Modifier.height(24.dp))
    Text("Paste your sign-in code", style = mockText(20.sp, FontWeight.Bold, letterSpacing = (-0.3).sp), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "Sign in on claude.ai. When it shows a code, tap Copy, come back here and paste it.",
        style = mockText(15.sp).copy(lineHeight = 21.sp),
        color = PrTheme.colors.textMuted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
        OutlineButton("Open claude.ai", onClick = { waiting.manualUrl?.let { openTab(it) } }, enabled = waiting.manualUrl != null, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        AnimatedVisibility(clip != null, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
            AssistChip(
                onClick = { clip?.let { code = it.trim(); clip = null } },
                label = { Text("Paste code from clipboard") },
                leadingIcon = { Icon(Icons.Filled.Check, null, Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.trim() },
            label = { Text("Code") },
            singleLine = true,
            textStyle = mockText(14.sp).copy(fontFamily = JetBrainsMono),
            isError = code.isNotEmpty() && !valid,
            supportingText = {
                if (code.isNotEmpty() && !valid) Text("That doesn't look like the code from this sign-in.")
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) ClaudeLogin.submitCode(code) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        AccentButton("Sign in", onClick = { ClaudeLogin.submitCode(code) }, enabled = valid)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DoneBody(account: ClaudeAccount) {
    Spacer(Modifier.height(96.dp))
    Box(
        Modifier.size(88.dp).background(PrTheme.colors.accentTint, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Check, null, tint = PrTheme.colors.accent, modifier = Modifier.size(48.dp)) }
    Spacer(Modifier.height(28.dp))
    Text("You're signed in", style = mockText(24.sp, FontWeight.Bold, letterSpacing = (-0.3).sp), textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    AccountCard(account)
}

/** The signed-in account as a small card: avatar initial, email, plan. Also used in Settings. */
@Composable
fun AccountCard(account: ClaudeAccount, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = PrTheme.colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, PrTheme.colors.iconTile),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(PrTheme.colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (account.email?.firstOrNull() ?: 'C').uppercase(),
                    style = mockText(15.sp, FontWeight.SemiBold),
                    color = PrTheme.colors.textSoft,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(account.email ?: "Claude account", style = mockText(15.sp, FontWeight.SemiBold))
                val detail = listOfNotNull(account.planLabel, account.teamName).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = mockText(14.sp), color = PrTheme.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun StatusBody(icon: ImageVector?, title: String, body: String, error: Boolean = false) {
    Spacer(Modifier.height(120.dp))
    if (icon == null) {
        CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 3.dp, color = PrTheme.colors.accent)
    } else {
        val bg = if (error) PrTheme.colors.red.copy(alpha = 0.10f) else PrTheme.colors.iconTile
        val fg = if (error) PrTheme.colors.redSoft else PrTheme.colors.textSoft
        Box(Modifier.size(88.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(44.dp))
        }
    }
    Spacer(Modifier.height(28.dp))
    Text(title, style = mockText(20.sp, FontWeight.Bold, letterSpacing = (-0.3).sp), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        body,
        style = mockText(15.sp).copy(lineHeight = 21.sp),
        color = PrTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 420.dp),
    )
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        IconBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = mockText(14.sp, FontWeight.SemiBold, PrTheme.colors.text))
            Text(body, style = mockText(12.sp, color = PrTheme.colors.textMuted).copy(lineHeight = 17.4.sp))
        }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean = false, onClick: () -> Unit) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (busy) c.accent.copy(alpha = 0.6f) else c.accent)
            .clickable(enabled = !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onAccent)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = mockText(15.sp, FontWeight.SemiBold, c.onAccent))
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(text, style = mockText(14.sp, color = PrTheme.colors.textMuted)) }
}

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()
