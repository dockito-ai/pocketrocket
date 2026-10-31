package com.pocketrocket.ui.accounts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.dockito.Dockito
import com.pocketrocket.dockito.DockitoLoginState
import com.pocketrocket.dockito.DockitoCli
import com.pocketrocket.dockito.DockitoState
import com.pocketrocket.dockito.DockitoStatus
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.DockitoAccountEntry
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.ui.components.DashedAddButton
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * The Accounts section of Dockito's tool page (decision 64): its logins, from the CLI's own list (`dockito
 * accounts`), one the default, workspaces pick their own; sign in, add, make default, sign out.
 */
@Composable
fun DockitoAccountsSection() {
    val context = LocalContext.current
    val s by Dockito.state.collectAsState()
    val list by DockitoAccounts.state.collectAsState()
    val bindings by AccountWiring.bindings.collectAsState()
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val c = PrTheme.colors

    // The sign-in link opens in the browser as soon as the CLI prints it.
    val waiting = s.login as? DockitoLoginState.Waiting
    LaunchedEffect(waiting?.link) { waiting?.link?.let { openLink(context, it) } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!s.accountsSupported) {
            Text(
                "Update Dockito to ${DockitoCli.MIN_VERSION} or newer to sign in: accounts come from the CLI's own list since then.",
                style = mockText(13.sp, color = c.textMuted).copy(lineHeight = 18.sp),
            )
            return@Column
        }
        if (list.accounts.isEmpty() && !s.signingIn) {
            Text("No Dockito account yet. Sign in to publish and share docs from this phone.", style = mockText(14.sp, color = c.textMuted))
        }
        list.accounts.forEach { entry ->
            AccountCard(
                entry, isDefault = list.isDefault(entry), status = s.statuses[entry.id],
                folders = bindings.count { it.dockito == entry.id }, busy = entry.id in s.signingOut || s.signingIn,
                onSignIn = { Dockito.signIn(context, entry) },
                onMakeDefault = { Dockito.setDefault(context, entry) },
                onRemove = { removing = entry.id },
            )
        }
        SignInCard(s, context)
        if (!s.signingIn) {
            DashedAddButton("+ Add a Dockito account", onClick = { Dockito.signIn(context) }, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "Sessions and terminals use the workspace's account, the default elsewhere. In a terminal, dockito accounts " +
                "lists them and --account <email> picks one for a command.",
            style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.4.sp),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }

    removing?.let(DockitoAccounts::get)?.let { entry ->
        Confirm(
            "Sign out of ${entry.name}?",
            "Signs this phone out of Dockito as ${entry.email} and removes its login here. " +
                "Workspaces using it switch to the default.",
            "Sign out", onDismiss = { removing = null },
        ) { Dockito.signOut(context, entry) }
    }
}

@Composable
private fun AccountCard(
    entry: DockitoAccountEntry,
    isDefault: Boolean,
    status: DockitoStatus?,
    folders: Int,
    busy: Boolean,
    onSignIn: () -> Unit,
    onMakeDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = PrTheme.colors
    QuietCard(highlight = isDefault) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialBadge(entry.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.name, style = mockText(15.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.email, style = mockText(12.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isDefault) DefaultChip(large = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyValue(
                "Login",
                when (status) {
                    null -> "checking…"
                    is DockitoStatus.SignedIn -> "signed in"
                    DockitoStatus.SignedOut -> "signed out"
                    is DockitoStatus.Unknown -> "couldn't check: ${status.message}"
                },
                valueColor = if (status is DockitoStatus.SignedIn || status == null) c.text else c.redSoft,
            )
            (status as? DockitoStatus.SignedIn)?.account?.workspaces?.takeIf { it.isNotEmpty() }?.let { ws ->
                KeyValue("Workspaces", ws.joinToString(", ") { "${it.name} (${it.level.replaceFirstChar(Char::uppercase)})" })
            }
            KeyValue(
                "Used by",
                listOfNotNull(
                    if (folders == 0) null else if (folders == 1) "1 workspace" else "$folders workspaces",
                    if (isDefault) "everything else" else null,
                ).joinToString(" · ").ifEmpty { "nothing yet" },
            )
            KeyValue("Username", entry.username, mono = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val small = Modifier.weight(1f).height(40.dp)
            if (!isDefault) OutlineButton("Make default", onClick = onMakeDefault, modifier = small, fontSize = 13.sp, radius = 10.dp)
            if ((status == DockitoStatus.SignedOut || status is DockitoStatus.Unknown) && !busy) OutlineButton("Sign in again", onClick = onSignIn, modifier = small, fontSize = 13.sp, radius = 10.dp)
            OutlineButton(if (busy) "…" else "Sign out", onClick = { if (!busy) onRemove() }, modifier = small, color = c.redSoft, border = c.redBorder, fontSize = 13.sp, radius = 10.dp)
        }
    }
}

/** The running sign-in: the code to check on Dockito's page, the link, Cancel; or why it failed. */
@Composable
private fun SignInCard(s: DockitoState, context: Context) {
    val c = PrTheme.colors
    when (val login = s.login) {
        DockitoLoginState.Idle -> Unit
        is DockitoLoginState.Starting -> QuietCard(highlight = true) {
            Text("Starting Dockito's sign-in…", style = mockText(14.sp, color = c.text))
            OutlineButton("Cancel", onClick = Dockito::cancelSignIn, modifier = Modifier.fillMaxWidth())
        }
        is DockitoLoginState.Waiting -> QuietCard(highlight = true) {
            SectionLabel("Signing in")
            if (login.code != null) {
                Text("Check that Dockito's page shows this code:", style = mockText(13.sp, color = c.textMuted))
                Text(login.code, style = mockText(26.sp, FontWeight.Bold, c.text, mono = true, letterSpacing = 2.sp))
            }
            Text(
                "Dockito opened in your browser. Pick the access for each workspace and confirm; this page updates when you're done.",
                style = mockText(13.sp, color = c.textMuted).copy(lineHeight = 18.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val small = Modifier.weight(1f).height(40.dp)
                OutlineButton("Open again", onClick = { openLink(context, login.link) }, modifier = small, fontSize = 13.sp, radius = 10.dp)
                OutlineButton("Cancel", onClick = Dockito::cancelSignIn, modifier = small, fontSize = 13.sp, radius = 10.dp)
            }
        }
        is DockitoLoginState.Failed -> QuietCard {
            Text(login.message, style = mockText(13.sp, color = c.redSoft))
            OutlineButton("OK", onClick = Dockito::clearFailure, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
