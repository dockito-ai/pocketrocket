package com.pocketrocket.ui.accounts

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.ui.unit.sp
import com.pocketrocket.github.ConnectState
import com.pocketrocket.github.GitHubConnect
import com.pocketrocket.ui.auth.AuthTab

/**
 * Connect a GitHub account: gh's device-code sign-in. The code is shown big with Copy; "Open GitHub"
 * copies it and opens github.com/login/device in a Custom Tab. gh notices the approval by itself, then
 * the key and identity are set up and the account appears in the list.
 */
@Composable
fun GitHubConnectScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val activity = LocalContext.current as Activity
    val state by GitHubConnect.state.collectAsState()
    val surface = PrTheme.colors.background
    var copied by remember { mutableStateOf(false) }
    val leave = { GitHubConnect.cancel(); AuthTab.close(activity); onBack() }

    LaunchedEffect(Unit) { AuthTab.warmUp(activity) }
    // Done: close the browser sheet and bring this screen back.
    LaunchedEffect(state is ConnectState.Connected || state is ConnectState.Finishing) {
        if (state is ConnectState.Connected || state is ConnectState.Finishing) AuthTab.close(activity)
    }

    val c = PrTheme.colors
    AccountPage("Connect GitHub", onBack = leave, gap = 14.dp) {
        when (val s = state) {
            ConnectState.Idle -> {
                Heading("Connect a GitHub account")
                QuietCard {
                    Point("1", "Sign in on GitHub", "GitHub shows a page asking for a short code. You copy it from here.")
                    Point("2", "A key just for this account", "PocketRocket makes an SSH key on this phone and adds it to the account.")
                    Point("3", "Commits as you", "Commits use your name and GitHub's noreply address. You can change them later.")
                }
                AccentButton("Connect", onClick = { copied = false; GitHubConnect.start(activity) }, modifier = Modifier.padding(top = 8.dp))
            }
            ConnectState.Starting -> Busy("Starting gh…")
            is ConnectState.WaitingForCode -> {
                Heading("Enter this code on GitHub")
                QuietCard {
                    Text(
                        s.code,
                        style = mockText(34.sp, FontWeight.Medium, c.text, mono = true, letterSpacing = 3.sp),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    OutlineButton(if (copied) "Copied" else "Copy code", onClick = { copy(activity, s.code); copied = true }, modifier = Modifier.fillMaxWidth())
                }
                AccentButton(
                    "Copy and open GitHub",
                    onClick = {
                        copy(activity, s.code); copied = true
                        AuthTab.open(activity, s.url, surface.toArgb(), surface.luminance() < 0.5f)
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Waiting for GitHub. Paste the code at ${s.url.removePrefix("https://")}, then authorize GitHub CLI. This page carries on by itself.",
                        style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.4.sp),
                    )
                }
                OutlineButton("Cancel", onClick = leave, modifier = Modifier.fillMaxWidth())
            }
            is ConnectState.Finishing -> Busy(s.step)
            is ConnectState.Connected -> {
                Heading("Connected ${s.account.login}")
                QuietCard(gap = 6.dp) {
                    KeyValue("Commits as", s.account.userName)
                    KeyValue("Email", s.account.userEmail, mono = true)
                    KeyValue("SSH key", s.account.keyTitle, mono = true)
                }
                AccentButton("Done", onClick = { GitHubConnect.reset(); onDone() })
            }
            is ConnectState.Failed -> {
                Heading("Didn't connect")
                Text(s.message, style = mockText(14.sp, color = c.textMuted))
                AccentButton("Try again", onClick = { copied = false; GitHubConnect.start(activity) })
                OutlineButton("Cancel", onClick = leave, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = mockText(20.sp, FontWeight.Bold, PrTheme.colors.text, letterSpacing = (-0.3).sp))
}

@Composable
private fun Busy(text: String) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = c.accent)
        Spacer(Modifier.width(14.dp))
        Text(text, style = mockText(14.sp, color = c.textSoft))
    }
}

@Composable
private fun Point(number: String, title: String, body: String) {
    val c = PrTheme.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InitialBadge(number, 28.dp, 13.sp)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = mockText(14.sp, FontWeight.SemiBold, c.text))
            Text(body, style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.4.sp))
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GitHub code", text))
}
