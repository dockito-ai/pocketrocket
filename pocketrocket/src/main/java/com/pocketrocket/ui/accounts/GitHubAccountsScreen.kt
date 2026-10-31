package com.pocketrocket.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import com.pocketrocket.ui.components.DashedAddButton
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.GitHubAccountEntry
import com.pocketrocket.profiles.GitHubAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → GitHub: each connected account with its identity, key and use; make default, edit, remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GitHubAccountsScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list by GitHubAccounts.state.collectAsState()
    val bindings by AccountWiring.bindings.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    val c = PrTheme.colors
    AccountPage("GitHub accounts", onBack, gap = 10.dp) {
        if (list.accounts.isEmpty()) {
            Text("No GitHub account yet. Connect one to clone and push from this phone.", style = mockText(14.sp, color = c.textMuted))
        }
        list.accounts.forEach { entry ->
            val isDefault = list.isDefault(entry)
            val folders = bindings.count { it.github == entry.id }
            QuietCard(highlight = isDefault) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(c.border, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(MockIcons.Person, null, tint = c.text, modifier = Modifier.size(18.dp))
                    }
                    Text(entry.login, style = mockText(15.sp, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (isDefault) DefaultChip(large = true)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValue("Commits as", identityLabel(entry), mono = true)
                    KeyValue("SSH key", "ed25519 · pocketrocket-${entry.id}", mono = true)
                    KeyValue(
                        "Used by",
                        listOfNotNull(
                            if (folders == 0) null else if (folders == 1) "1 folder" else "$folders folders",
                            if (isDefault) "everything else" else null,
                        ).joinToString(" · ").ifEmpty { "nothing yet" },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val small = Modifier.weight(1f).height(40.dp)
                    if (!isDefault) OutlineButton("Make default", onClick = { GitHubAccounts.setDefault(entry.id) }, modifier = small, fontSize = 13.sp, radius = 10.dp)
                    OutlineButton("Edit identity", onClick = { editing = entry.id }, modifier = small, fontSize = 13.sp, radius = 10.dp)
                    OutlineButton("Remove", onClick = { removing = entry.id }, modifier = small, color = c.redSoft, border = c.redBorder, fontSize = 13.sp, radius = 10.dp)
                }
            }
        }
        DashedAddButton("+ Connect a GitHub account", onClick = onConnect, modifier = Modifier.padding(top = 6.dp))
        Text(
            "Connecting creates a key on this phone, signs in to gh through GitHub's page, and adds the key to the account. " +
                "Each workspace picks which account its repos use.",
            style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.4.sp),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }

    editing?.let(GitHubAccounts::get)?.let { entry -> IdentityDialog(entry, onDismiss = { editing = null }) }
    removing?.let(GitHubAccounts::get)?.let { entry ->
        Confirm(
            "Remove ${entry.login}?",
            "Deletes its gh login, key and git settings on this phone. The key stays listed on GitHub " +
                "(Settings > SSH keys) until you delete it there. Folders using it switch to the default.",
            "Remove", onDismiss = { removing = null },
        ) { scope.launch { withContext(Dispatchers.IO) { GitHubAccounts.remove(entry.id) } } }
    }
}

/** "Shankar Kakumani · noreply" when the email is GitHub's noreply address. */
private fun identityLabel(entry: GitHubAccountEntry): String {
    val email = if (entry.userEmail.endsWith("@users.noreply.github.com")) "noreply" else entry.userEmail
    return "${entry.userName} · $email"
}

@Composable
private fun IdentityDialog(entry: GitHubAccountEntry, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(entry.userName) }
    var email by rememberSaveable { mutableStateOf(entry.userEmail) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Commits in folders using ${entry.login} are made with this name and email.",
                    style = mockText(14.sp, color = PrTheme.colors.textMuted),
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.width(0.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { GitHubAccounts.editIdentity(entry.id, name, email); onDismiss() },
                enabled = name.isNotBlank() && email.contains('@'),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
