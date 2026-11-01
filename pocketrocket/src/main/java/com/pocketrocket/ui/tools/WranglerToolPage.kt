package com.pocketrocket.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.wrangler.Wrangler
import com.pocketrocket.wrangler.WranglerTool

/**
 * Settings → Tools → Wrangler (decisions 49 and 82): what it does, its version, the Cloudflare sign-in (the shared CLI
 * login, decision 98), sizes, and Install, Uninstall or Retry.
 */
@Composable
fun WranglerToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Wrangler.state.collectAsState()
    val ls by Wrangler.login.state.collectAsState()
    LaunchedEffect(Unit) { Wrangler.refreshAccount(context) }

    ToolPage(
        spec = Tool.Wrangler.spec,
        state = ToolStates.wrangler(s, ls),
        icon = Tool.Wrangler.icon,
        purpose = "Deploy and manage Cloudflare Workers.",
        onBack = onBack,
        install = ToolInstall(
            job = s.job, outcome = s.outcome, removing = s.removing,
            installSteps = WranglerTool.INSTALL_STEPS, removeSteps = WranglerTool.REMOVE_STEPS,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry starts it again.",
            onInstall = { Wrangler.install(context) },
            onRetry = { if (s.removing) Wrangler.remove(context) else Wrangler.install(context) },
            onUninstall = { Wrangler.remove(context) },
            confirmTitle = "Uninstall Wrangler?",
            confirmBody = "Wrangler and its folder are removed, and you're signed out of Cloudflare on this phone. Your projects stay, and so do your Workers on Cloudflare.",
            holdUninstall = ls.busy,
        ),
        accounts = {
            CliAccountCard(
                "Cloudflare", Wrangler.login, ls, blocked = s.running,
                why = "Deploying needs it. Your login stays in Wrangler's own config file.",
            )
        },
    ) {
        if (s.installed) {
            ToolCard("HOW TO USE") {
                ToolText("In a terminal or a Claude session, run wrangler dev in your project to try a Worker on the phone at localhost:8787, and wrangler deploy to put it on Cloudflare. New terminals and sessions already have it.")
            }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolBullets(
                    listOf(
                        "Cloudflare's command line tool.",
                        "Runs Workers on the phone with wrangler dev, KV, D1 and the rest kept locally.",
                        "Deploys and manages Workers on your Cloudflare account.",
                        "Works in terminals and Claude sessions.",
                    ),
                )
            }
        }
    }
}
