package com.pocketrocket.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.node.NodeJs
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.ui.accounts.DockitoAccountsSection
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Tools → Node.js and npm: built in (first-run setup installs nodejs-lts); its versions and size, and Update
 * through Updates (decision 103).
 */
@Composable
fun NodeToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by NodeJs.state.collectAsState()
    val updates by Updates.state.collectAsState()
    LaunchedEffect(Unit) {
        NodeJs.refresh(context, force = true)
        Updates.onAppOpen(context, checkAfterMs = 10 * 60_000L)
    }
    val row = updates.rows.firstOrNull { it.component == UpdateComponent.Node }
    // An update changes the programs' versions; read them again once it ends.
    LaunchedEffect(row?.running) { if (row?.running == false) NodeJs.refresh(context, force = true) }
    ToolPage(
        spec = Tool.Node.spec,
        state = ToolStates.node(s, row),
        icon = Tool.Node.icon,
        purpose = "Runs JavaScript, and npm installs packages.",
        onBack = onBack,
        update = toolUpdate(context, updates, UpdateComponent.Node),
    ) {
        ToolCard("WHAT IT DOES") {
            ToolBullets(
                listOf(
                    "Runs Node.js scripts and dev servers in terminals and Claude sessions.",
                    "npm installs packages for your projects, and the MCP servers Claude uses.",
                    "Wrangler and Headless Chrome's MCP server run on it.",
                ),
            )
        }
        ToolCard("WHAT IT IS") {
            ToolText("Termux's nodejs-lts package, installed by first-run setup with git and the rest of the base set, with npm inside it. Settings › Updates updates it on its own.")
        }
    }
}

/**
 * Settings → Tools → Dockito: built in (first-run setup installs it, decision 40). Its version with Update through
 * the Updates flow (decision 41), and its accounts, which moved here from Settings › Accounts (decision 64).
 */
@Composable
fun DockitoToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Dockito.state.collectAsState()
    val accounts by DockitoAccounts.state.collectAsState()
    val updates by Updates.state.collectAsState()
    LaunchedEffect(Unit) {
        Dockito.refresh(context)
        Updates.onAppOpen(context, checkAfterMs = 10 * 60_000L)
    }
    val size by produceState<Int?>(null, s.installed) { value = withContext(Dispatchers.IO) { ToolStates.dockitoSizeMb(s.installed) } }
    val row = updates.rows.firstOrNull { it.component == UpdateComponent.Dockito }

    ToolPage(
        spec = Tool.Dockito.spec,
        state = ToolStates.dockito(s, row, accounts.default?.email, size),
        icon = Tool.Dockito.icon,
        purpose = "Publish and share docs from this phone.",
        onBack = onBack,
        update = toolUpdate(context, updates, UpdateComponent.Dockito),
        accounts = { DockitoAccountsSection() },
    ) {
        if (s.loaded && s.installed == null) {
            ToolCard("NOT INSTALLED") { ToolText("Dockito's CLI isn't on the phone. Run setup again to install it.") }
        } else {
            ToolCard("WHAT IT DOES") {
                ToolText("Dockito's command line tool. Claude publishes and shares docs with it, and every Claude account gets its skill. Sessions and terminals use the workspace's Dockito account, the default elsewhere.")
            }
        }
    }
}

/** Settings → Tools → Git: built in (first-run setup installs Termux's git); its version, and Update through Updates. */
@Composable
fun GitToolPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val updates by Updates.state.collectAsState()
    LaunchedEffect(Unit) { Updates.onAppOpen(context, checkAfterMs = 10 * 60_000L) }
    ToolPage(
        spec = Tool.Git.spec,
        state = ToolStates.fromRow(updates.rows.firstOrNull { it.component == UpdateComponent.Git }),
        icon = Tool.Git.icon,
        purpose = "Version control for every workspace.",
        onBack = onBack,
        update = toolUpdate(context, updates, UpdateComponent.Git),
    ) {
        ToolCard("WHAT IT DOES") {
            ToolText("Clones, commits, branches and pushes, for you in terminals and for Claude in sessions. The Git screens and worktrees use it too. GitHub logins are under Settings › Accounts.")
        }
        ToolCard("WHAT IT IS") {
            ToolText("Termux's git package, installed by first-run setup. Settings › Updates updates it on its own.")
        }
    }
}
