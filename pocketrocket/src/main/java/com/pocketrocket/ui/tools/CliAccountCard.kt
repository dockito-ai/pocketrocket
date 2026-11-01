package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketrocket.clilogin.AuthAction
import com.pocketrocket.clilogin.CliLogin
import com.pocketrocket.clilogin.LoginState
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.OutlineButton

/**
 * A CLI's login on its tool page (decisions 49 and 98): whether it is signed in and as whom, Log in or Log out, the
 * field for the code the browser shows when the CLI needs it, and the output of the last try. [service] names where
 * the login goes ("Cloudflare"); [why] is the line shown while signed out. [blocked] while its install or uninstall
 * runs.
 */
@Composable
fun CliAccountCard(service: String, login: CliLogin, s: LoginState, why: String, blocked: Boolean = false) {
    val context = LocalContext.current
    var logOpen by rememberSaveable(s.auth, s.failed) { mutableStateOf(s.auth == AuthAction.LoggingIn || s.failed) }
    var code by rememberSaveable(s.auth) { mutableStateOf("") }
    ToolCard(service.uppercase()) {
        ToolText(
            when (s.auth) {
                AuthAction.LoggingIn -> "Waiting for you to allow it in the browser. Come back here when you're done."
                AuthAction.WaitingForCode -> "Allow it in the browser, then paste the code it shows here."
                AuthAction.SendingCode -> "Checking the code…"
                AuthAction.LoggingOut -> "Signing out…"
                null -> when {
                    s.signedIn -> s.account?.let { "Signed in as $it." } ?: "Signed in."
                    s.failed -> "Sign-in didn't finish. Not signed in."
                    else -> "Not signed in. $why"
                }
            },
        )
        when (s.auth) {
            AuthAction.LoggingIn -> OutlineButton("Cancel sign-in", { login.cancel(context) }, Modifier.fillMaxWidth())
            AuthAction.WaitingForCode -> {
                MockTextField(
                    code, { code = it }, placeholder = "Code from the browser",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                )
                AccentButton("Send code", onClick = { login.submitCode(context, code) }, height = 48.dp, radius = 12.dp, enabled = code.isNotBlank())
                OutlineButton("Cancel sign-in", { login.cancel(context) }, Modifier.fillMaxWidth())
            }
            AuthAction.SendingCode -> BusyButton("Checking the code…")
            AuthAction.LoggingOut -> BusyButton("Signing out…")
            null -> when {
                blocked -> BusyButton("Waiting for the install…")
                s.signedIn -> OutlineButton("Log out", { login.logout(context) }, Modifier.fillMaxWidth())
                else -> AccentButton("Log in", onClick = { login.login(context) }, height = 48.dp, radius = 12.dp)
            }
        }
        if (s.busy || s.log.isNotEmpty()) ToolLog(s.log, logOpen) { logOpen = !logOpen }
    }
}
