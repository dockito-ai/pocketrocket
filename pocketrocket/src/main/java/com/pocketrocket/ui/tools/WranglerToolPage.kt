package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.wrangler.AuthAction
import com.pocketrocket.wrangler.Wrangler
import com.pocketrocket.wrangler.WranglerState
import com.pocketrocket.wrangler.WranglerTool

/** Settings → Tools → Wrangler (decisions 49 and 82): what it does, its version, the Cloudflare sign-in, sizes, and Install, Uninstall or Retry. */
@Composable
fun WranglerToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Wrangler.state.collectAsState()
    LaunchedEffect(Unit) { Wrangler.refreshAccount(context) }

    ToolPage(
        spec = Tool.Wrangler.spec,
        state = ToolStates.wrangler(s),
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
            holdUninstall = s.authBusy,
        ),
        accounts = { CloudflareAccount(s) },
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

/** Whether Wrangler is signed in to Cloudflare, the button that changes it, and the output of the last try. */
@Composable
private fun CloudflareAccount(s: WranglerState) {
    val context = LocalContext.current
    var logOpen by rememberSaveable(s.auth, s.authFailed) { mutableStateOf(s.auth == AuthAction.LoggingIn || s.authFailed) }
    ToolCard("CLOUDFLARE") {
        ToolText(
            when {
                s.auth == AuthAction.LoggingIn -> "Waiting for you to allow it in the browser. Come back here when you're done."
                s.auth == AuthAction.LoggingOut -> "Signing out…"
                s.signedIn -> s.account?.let { "Signed in as $it." } ?: "Signed in."
                s.authFailed -> "Sign-in didn't finish. Not signed in."
                else -> "Not signed in. Deploying needs it. Your login stays in Wrangler's own config file."
            },
        )
        when {
            s.auth == AuthAction.LoggingIn -> OutlineButton("Cancel sign-in", { Wrangler.cancelLogin() }, Modifier.fillMaxWidth())
            s.auth == AuthAction.LoggingOut -> BusyButton("Signing out…")
            s.signedIn -> OutlineButton("Log out", { Wrangler.logout(context) }, Modifier.fillMaxWidth())
            else -> AccentButton("Log in", onClick = { Wrangler.login(context) }, height = 48.dp, radius = 12.dp)
        }
        if (s.authBusy || s.authLog.isNotEmpty()) ToolLog(s.authLog, logOpen) { logOpen = !logOpen }
    }
}
