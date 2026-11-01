package com.pocketrocket.ui.tools

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.pocketrocket.browser.Chromium
import com.pocketrocket.browser.ChromiumMcp
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates

private val MEMORY = "About ${ChromiumMcp.MEMORY_MB} MB per running browser"

/** Settings → Tools → Headless Chrome (decision 37): what it does, its MCP server, sizes, and Install, Uninstall or Retry. */
@Composable
fun ChromiumToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Chromium.state.collectAsState()
    val accounts by ClaudeAccounts.state.collectAsState()
    val updates by Updates.state.collectAsState()
    LaunchedEffect(Unit) {
        Chromium.refresh()
        Updates.onAppOpen(context, checkAfterMs = 10 * 60_000L)
    }
    val n = accounts.accounts.size

    ToolPage(
        spec = Tool.Chromium.spec,
        state = ToolStates.chromium(s, updates.rows.firstOrNull { it.component == UpdateComponent.Chromium }),
        icon = Tool.Chromium.icon,
        purpose = "Claude opens and tests the pages it builds.",
        onBack = onBack,
        install = ToolInstall(
            job = s.job, outcome = s.outcome, removing = s.removing,
            installSteps = ChromiumMcp.INSTALL_STEPS, removeSteps = ChromiumMcp.REMOVE_STEPS,
            failedLine = if (s.claudeChanged) "Claude's settings may be partly changed." else "Nothing was changed for Claude.",
            onInstall = { Chromium.install(context) },
            onRetry = { if (s.removing) Chromium.remove(context) else Chromium.install(context) },
            onUninstall = { Chromium.remove(context) },
            confirmTitle = "Uninstall ${Tool.Chromium.spec.name}?",
            confirmBody = "Claude sessions lose the browser. ${ChromiumMcp.INSTALLED_MB} MB is freed.",
        ),
        update = toolUpdate(context, updates, UpdateComponent.Chromium),
        storageRows = listOf("Memory" to MEMORY),
    ) {
        if (s.installed) {
            ToolDetails(
                label = "DETAILS",
                rows = listOf("Claude accounts" to "Wired into $n Claude ${if (n == 1) "account" else "accounts"}"),
            )
            ToolCard("WHAT IT DOES") {
                ToolText("Claude opens the pages it builds, screenshots them at phone, tablet and desktop widths, reads console and network errors and clicks through flows. Each session gets its own browser.")
            }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "Claude opens the pages it builds.",
                        "It takes screenshots at phone, tablet and desktop widths.",
                        "It reads console and network errors.",
                        "It clicks through flows.",
                    ),
                )
                Text("Each session gets its own browser.", style = mockText(11.sp).copy(lineHeight = 16.sp), color = PrTheme.colors.textMuted)
            }
            ToolCard("WHAT IT IS") {
                ToolText("Chromium from Termux's packages, updated from Settings › Updates, plus the Chrome DevTools MCP server. The server is added to every Claude account.")
            }
        }
    }
}
