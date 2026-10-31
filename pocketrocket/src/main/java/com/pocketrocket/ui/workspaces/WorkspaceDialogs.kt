package com.pocketrocket.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.workspaces.Workspaces

/** `~/projects/x` back to an absolute path. */
internal fun expandTilde(path: String): String = AccountPaths.normalize(path)

/**
 * Rename or move a workspace or a folder. [name] is null for a plain folder (only its location
 * changes). Moving renames the folder on disk; nothing is deleted.
 */
@Composable
internal fun RenameMoveDialog(
    title: String,
    name: String?,
    path: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String?, path: String) -> String?,
) {
    var newName by rememberSaveable { mutableStateOf(name.orEmpty()) }
    var location by rememberSaveable { mutableStateOf(Workspaces.tilde(path)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (name != null) OutlinedTextField(newName, { newName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    location, { location = it; error = null }, label = { Text("Folder") }, singleLine = true,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(error ?: "Changing the folder moves it and everything in it.") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(if (name != null) newName else null, expandTilde(location)); if (error == null) onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for a folder path (new folder, or a folder to start a session in). */
@Composable
internal fun FolderPrompt(title: String, initial: String, confirm: String, body: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body?.let { Text(it) }
                OutlinedTextField(
                    value, { value = it; error = null }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { error = onConfirm(expandTilde(value)); if (error == null) onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
