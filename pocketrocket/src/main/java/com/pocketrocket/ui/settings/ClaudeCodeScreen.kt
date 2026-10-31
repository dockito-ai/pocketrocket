package com.pocketrocket.ui.settings

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.accounts.ago
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockBox
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mock
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.updater.ClaudeUpdater
import com.pocketrocket.updater.UpdaterState
import com.pocketrocket.setup.SetupPhase

private const val CHANGELOG = "https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md"

/**
 * Settings › Updates › Claude Code (R9), in the mockup's account-page style: installed and released versions, the
 * running update's or rollback's steps, sessions still on the old binary, rollback, the changelog. Update and
 * Check live on the Updates page (decision 41).
 */
@Composable
fun ClaudeCodeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = mock
    val s by ClaudeUpdater.state.collectAsState()
    val stale by remember { ClaudeUpdater.staleSessions() }.collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { ClaudeUpdater.onAppOpen(context) }

    MockPage {
        MockTopBar("Claude Code", MockIcons.Back, onBack, titleSize = 17.sp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Header(s)

            Card {
                Caps("VERSIONS")
                KeyRow("Installed", s.installed ?: "not installed")
                KeyRow("Latest", s.latest ?: if (s.checking) "checking…" else "unknown")
                s.stable?.let { KeyRow("Stable", it) }
                Text(
                    when {
                        s.checking -> "Asking downloads.claude.ai…"
                        s.checkedAt > 0 -> "Checked ${ago(s.checkedAt)} on downloads.claude.ai. Check now on the Updates page looks again."
                        else -> "Not checked yet."
                    },
                    style = mockText(11.sp, color = c.muted),
                )
            }

            if (s.job != null) Job(s)

            if (stale.isNotEmpty()) {
                Card {
                    val n = stale.size
                    Text(
                        "$n ${if (n == 1) "session" else "sessions"} still on ${s.staleVersion}, they switch when restarted.",
                        style = mockText(14.sp, color = c.text),
                    )
                    Text("Restarting ends each process and resumes the conversation on the new version. Sessions in the middle of a turn are left alone.", style = mockText(12.sp, color = c.muted))
                    OutlineButton("Restart them", onClick = { ClaudeUpdater.restartStale() }, modifier = Modifier.fillMaxWidth())
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps("ROLLBACK")
                val kept = s.kept
                if (kept == null) {
                    Text("No older version kept. After an update, the one before it stays here.", style = mockText(13.sp, color = c.muted))
                } else {
                    Text("Claude Code $kept is kept on the phone. Switching back checks that it starts first.", style = mockText(13.sp, color = c.muted))
                    if (!s.running) OutlineButton("Switch back to $kept", onClick = { ClaudeUpdater.switchTo(context, kept) }, modifier = Modifier.fillMaxWidth())
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caps("ABOUT", modifier = Modifier.padding(bottom = 6.dp))
                AppRow("Changelog", "github.com ›", divider = true) { openInTab(context, CHANGELOG, c.bg) }
                AppRow("Updates", "in Settings › Updates", divider = false)
            }
        }
    }
}

/** Version and state, like the account page's who-row: a letter tile, two lines and a chip. */
@Composable
private fun Header(s: UpdaterState) {
    val c = mock
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LetterTile("C")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.installed?.let { "Claude Code $it" } ?: "Claude Code", style = mockText(15.sp, FontWeight.SemiBold, c.text))
            Text(
                when {
                    s.installed == null -> "Not installed. Run setup again."
                    s.updateAvailable -> "${s.latest} is out"
                    s.latest != null -> "The newest release"
                    else -> "Installed from downloads.claude.ai"
                },
                style = mockText(12.sp, color = c.muted),
            )
        }
        when {
            s.updateAvailable -> Chip("Update available", c.accent)
            s.installed != null && s.latest != null -> Chip("Up to date", c.green)
        }
    }
}

@Composable
internal fun Chip(text: String, color: Color) {
    Text(
        text,
        style = mockText(11.sp, FontWeight.SemiBold, color),
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The running (or last) update or rollback: the script's steps with live progress, then how it ended. */
@Composable
private fun Job(s: UpdaterState) {
    val c = mock
    val job = s.job ?: return
    Card {
        Caps(s.jobTitle?.uppercase() ?: "UPDATE")
        StepList(job)
        s.outcome?.let {
            Text(it.message.trim(), style = mockText(13.sp, color = if (it.failed) c.redText else c.green))
        }
        if (job.phase == SetupPhase.Failed && job.log.isNotEmpty()) {
            Text(job.log.takeLast(4).joinToString("\n"), style = mockText(11.sp, color = c.muted, mono = true))
        }
    }
}

/** The mockup's quiet card: 16 dp padding, radius 16, field fill, row border. */
@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = mock
    MockBox(Modifier.fillMaxWidth(), radius = 16.dp, background = c.field, border = BorderStroke(1.dp, c.rowBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun KeyRow(label: String, value: String) {
    val c = mock
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = mockText(13.sp, color = c.text), modifier = Modifier.weight(1f))
        Text(value, style = mockText(13.sp, color = c.muted, mono = true))
    }
}

/** Opens [url] in a Chrome Custom Tab in the page's colours. */
internal fun openInTab(context: Context, url: String, toolbar: Color) {
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar.toArgb()).build()
    CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build()
        .launchUrl(context, Uri.parse(url))
}
