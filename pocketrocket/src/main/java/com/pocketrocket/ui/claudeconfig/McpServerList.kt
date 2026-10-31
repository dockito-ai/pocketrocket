package com.pocketrocket.ui.claudeconfig

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.McpBusy
import com.pocketrocket.claudeconfig.McpGroup
import com.pocketrocket.claudeconfig.McpPanel
import com.pocketrocket.claudeconfig.McpPanelState
import com.pocketrocket.claudeconfig.McpServer
import com.pocketrocket.claudeconfig.McpState
import com.pocketrocket.claudeconfig.ServerNotice
import com.pocketrocket.claudeconfig.TerminalHint
import com.pocketrocket.termux.Termux
import com.pocketrocket.ui.accounts.Confirm
import com.pocketrocket.ui.accounts.KeyValue
import com.pocketrocket.ui.accounts.StatusDot
import com.pocketrocket.ui.auth.AuthTab
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/*
 * The MCP server list, shared by the session's Status sheet and the Claude account screen: grouped by state, connected
 * first. Lists can be about 25 long, so a row is one line until tapped, and only what needs doing shows a button.
 */

/**
 * [terminal] says where the fallback terminal opens. [onNewSession] is null where a new session makes no sense (the
 * account screen has no folder, a demo session no account).
 */
@Composable
internal fun McpServerList(
    panel: McpPanel,
    state: McpPanelState,
    terminal: TerminalHint,
    terminalName: String?,
    onNewSession: (() -> Unit)? = null,
) {
    val listing = state.listing
    val openUrl = rememberAuthUrlOpener()
    var terminalFor by remember { mutableStateOf<McpServer?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listing.groups.forEach { group ->
            GroupSection(group, state, panel, openUrl, onTerminal = { terminalFor = it }, onNewSession)
        }
        if (listing.loadingPlugins > 0) {
            val n = listing.loadingPlugins
            Text(
                if (n == 1) "1 plugin server is still loading." else "$n plugin servers are still loading.",
                style = mockText(12.sp, color = PrTheme.colors.textMuted),
            )
        }
    }

    terminalFor?.let { server -> TerminalDialog(server, terminal, terminalName, onDismiss = { terminalFor = null }) }
}

@Composable
private fun GroupSection(
    group: McpGroup,
    state: McpPanelState,
    panel: McpPanel,
    openUrl: (String) -> Boolean,
    onTerminal: (McpServer) -> Unit,
    onNewSession: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caps("${groupTitle(group.state).uppercase()} · ${group.servers.size}")
        if (group.state == McpState.NeedsAuth) {
            Text(
                "Sign in opens claude.ai in your browser. It must be signed in there as ${state.signInEmail}. " +
                    "Nothing comes back to this app, so return here and tap Re-check. A change may only reach new sessions.",
                style = mockText(12.sp, color = PrTheme.colors.textMuted),
            )
        }
        group.servers.forEach { server ->
            key(server.name) { ServerRow(server, state, panel, openUrl, onTerminal, onNewSession) }
        }
    }
}

private fun groupTitle(state: McpState) = when (state) {
    McpState.Connected -> "Connected"
    McpState.NeedsAuth -> "Needs sign-in"
    McpState.Failed -> "Failed"
    McpState.Disabled -> "Off"
    McpState.Pending -> "Connecting"
    McpState.Unknown -> "Other"
}

@Composable
private fun ServerRow(
    server: McpServer,
    state: McpPanelState,
    panel: McpPanel,
    openUrl: (String) -> Boolean,
    onTerminal: (McpServer) -> Unit,
    onNewSession: (() -> Unit)?,
) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val busy = state.busy[server.name]
    val notice = state.notices[server.name]
    val free = busy == null && !state.actionsBlocked
    var expanded by remember { mutableStateOf(false) }
    val signIn = { panel.signIn(server.name, openUrl) }

    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.iconTile, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(stateColor(server.state))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(server.displayName, style = mockText(14.sp, color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rowLine(server, busy), style = mockText(11.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                busy != null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                server.name in state.awaitingBrowser -> ActionPill("Re-check", accent = true, enabled = free) { panel.recheck(server.name) }
                state.canSignIn(server) -> ActionPill("Sign in", accent = true, enabled = free, onClick = signIn)
                server.state == McpState.NeedsAuth -> ActionPill("Terminal", accent = false, enabled = true) { onTerminal(server) }
                server.state == McpState.Failed && state.canReconnect(server) -> ActionPill("Reconnect", accent = false, enabled = free) { panel.reconnect(server.name) }
                server.state == McpState.Disabled && state.canToggle(server) -> ActionPill("Turn on", accent = false, enabled = free) { panel.setEnabled(server.name, true) }
            }
        }
        notice?.let { NoticeBlock(it, server, onTerminal, onNewSession) }
        if (expanded) ServerDetails(server, state, panel, free, signIn, onTerminal)
    }
}

private fun rowLine(server: McpServer, busy: McpBusy?): String = when (busy) {
    McpBusy.SignIn -> "Getting the sign-in page. A new process can take up to 30 s to list it."
    McpBusy.WaitingForBrowser -> "Finish in the browser. This checks by itself."
    McpBusy.Recheck -> "Checking. This can take up to 30 s."
    McpBusy.Reconnect -> "Reconnecting"
    McpBusy.Toggle -> "Changing"
    null -> when (server.state) {
        McpState.Connected -> (if (server.toolCount == 1) "1 tool" else "${server.toolCount} tools") + " · " + server.sourceLabel
        McpState.Failed -> server.error ?: "Couldn't connect"
        McpState.Disabled -> "Off in this folder · " + server.sourceLabel
        else -> server.sourceLabel
    }
}

/** How the last action went, with what to do next. */
@Composable
private fun NoticeBlock(notice: ServerNotice, server: McpServer, onTerminal: (McpServer) -> Unit, onNewSession: (() -> Unit)?) {
    val c = PrTheme.colors
    val color = when (notice.kind) {
        ServerNotice.Kind.Success -> c.green
        ServerNotice.Kind.Problem -> c.redSoft
        ServerNotice.Kind.Info -> c.textSoft
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(notice.text, style = mockText(12.sp, color = color))
        val newSession = onNewSession?.takeIf { notice.offerNewSession }
        if (notice.offerTerminal || newSession != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                newSession?.let { ActionPill("Start a new session", accent = true, enabled = true, onClick = it) }
                if (notice.offerTerminal) ActionPill("Open a terminal", accent = false, enabled = true) { onTerminal(server) }
            }
        }
    }
}

/** What a tap on a row shows: where the server comes from, its error in full, and the quieter actions. */
@Composable
private fun ServerDetails(
    server: McpServer,
    state: McpPanelState,
    panel: McpPanel,
    free: Boolean,
    signIn: () -> Unit,
    onTerminal: (McpServer) -> Unit,
) {
    val c = PrTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyValue("From", server.sourceLabel)
        server.transport?.let { KeyValue("Transport", it, mono = true) }
        server.urlHost?.let { KeyValue("Host", it, mono = true) }
        server.serverVersion?.let { KeyValue("Version", it, mono = true) }
        if (server.state == McpState.Connected) KeyValue("Tools", server.toolCount.toString())
        server.errorCode?.let { KeyValue("Code", it, mono = true) }
        server.error?.let { Text(it, style = mockText(12.sp, color = c.redSoft)) }
        val toggle = state.canToggle(server)
        if (toggle) {
            Text(
                "On and off is saved per folder, on this account. It is not account-wide: other folders aren't changed, and later sessions in this folder keep the setting.",
                style = mockText(11.sp, color = c.textMuted),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (server.state == McpState.Connected && state.canReconnect(server)) {
                ActionPill("Reconnect", accent = false, enabled = free) { panel.reconnect(server.name) }
            }
            if (toggle && server.state != McpState.Disabled) {
                ActionPill("Turn off here", accent = false, enabled = free) { panel.setEnabled(server.name, false) }
            }
            if (server.name in state.awaitingBrowser && state.canSignIn(server)) {
                ActionPill("Open sign-in again", accent = false, enabled = free, onClick = signIn)
            }
            if (server.state == McpState.NeedsAuth || server.state == McpState.Failed) {
                ActionPill("Open a terminal", accent = false, enabled = true) { onTerminal(server) }
            }
        }
    }
}

/** A small button for a row: orange for what to do next, outlined otherwise. */
@Composable
internal fun ActionPill(text: String, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.heightIn(min = 36.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background(if (accent) c.accent else Color.Transparent)
            .border(1.dp, if (accent) c.accent else c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mockText(13.sp, FontWeight.SemiBold, if (accent) c.onAccent else c.text), maxLines = 1)
    }
}

@Composable
internal fun stateColor(state: McpState): Color {
    val c = PrTheme.colors
    return when (state) {
        McpState.Connected -> c.green
        McpState.NeedsAuth -> c.amber
        McpState.Failed -> c.red
        McpState.Pending -> c.blue
        McpState.Disabled, McpState.Unknown -> c.textFaint
    }
}

/** Says what to type before leaving the app, because the terminal can't be told; the command is copied too. */
@Composable
private fun TerminalDialog(server: McpServer, hint: TerminalHint, name: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Confirm(
        "Sign in from a terminal",
        "In the terminal, run:\n\n${hint.command}\n\nThen type /mcp and pick ${server.displayName}. The command is copied, ready to paste.",
        "Open terminal",
        onDismiss = onDismiss,
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Claude Code", hint.command))
        Termux.openTerminalIn(context, hint.folder, name)
    }
}

/**
 * Opens a sign-in URL in the app's browser sheet, or failing that any browser; false when nothing could. The sheet's
 * context is a dialog's, so the activity has to be dug out of it.
 */
@Composable
private fun rememberAuthUrlOpener(): (String) -> Boolean {
    val context = LocalContext.current
    val surface = PrTheme.colors.background
    LaunchedEffect(Unit) { AuthTab.warmUp(context) }
    return remember(context, surface) {
        { url: String ->
            val activity = context.findActivity()
            (activity != null && AuthTab.open(activity, url, surface.toArgb(), surface.luminance() < 0.5f)) ||
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
