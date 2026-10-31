package com.pocketrocket.ui.files

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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketrocket.files.FileEntry
import com.pocketrocket.files.FileOps
import com.pocketrocket.files.FileRules
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Asks for a name (new folder, rename); refuses names [FileRules.nameProblem] refuses before [onConfirm]. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, note: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                note?.let { Text(it) }
                OutlinedTextField(
                    name, { name = it; error = null }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = FileRules.nameProblem(name)
                if (error == null) { onDismiss(); onConfirm(name) }
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirms deleting [entry]. Says what goes (a link only, a file, or a folder and how much is in it). When
 * [serious] (home, ~/projects, a workspace or a folder holding one), the name has to be typed before Delete works.
 */
@Composable
internal fun DeleteDialog(entry: FileEntry, serious: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = PrTheme.colors
    var typed by rememberSaveable { mutableStateOf("") }
    val contents by produceState<FileOps.Contents?>(null, entry.path) {
        value = if (entry.isDir && !entry.isLink) withContext(Dispatchers.IO) { FileOps.contents(File(entry.path)) } else null
    }
    val what = when {
        entry.isLink -> "Only the link is removed. What it points to stays."
        !entry.isDir -> "It's deleted from the phone. This can't be undone."
        contents?.let { it.files == 0 && it.folders == 0 } == true -> "This empty folder is deleted from the phone."
        else -> {
            val inside = contents?.let { inside(it) } ?: "everything in it"
            "This folder and $inside are deleted from the phone. Links inside it are removed, not followed. This can't be undone."
        }
    }
    val ready = !serious || typed == entry.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${entry.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(what)
                if (serious) {
                    Text(seriousNote(entry.path), color = c.redSoft)
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true,
                        label = { Text("Type ${entry.name} to delete it") },
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }, enabled = ready) { Text("Delete", color = if (ready) c.redSoft else c.textMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun inside(contents: FileOps.Contents): String {
    fun count(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"
    val parts = listOfNotNull(
        count(contents.files, "file").takeIf { contents.files > 0 },
        count(contents.folders, "folder").takeIf { contents.folders > 0 },
    ).joinToString(" and ")
    return if (contents.capped) "more than $parts in it" else "the $parts in it"
}

/** Why deleting [path] needs the extra step. */
private fun seriousNote(path: String): String {
    val workspace = Workspaces.get(path)
    return when {
        path == com.pocketrocket.profiles.AccountPaths.home.path -> "This is your home folder: every repo, account and setting PocketRocket has."
        path == Workspaces.projectsRoot.path -> "This is ~/projects, with every workspace in it."
        workspace != null -> "This is the workspace ${workspace.name}. It also comes off the Workspaces list."
        else -> "Workspaces are inside this folder. They come off the Workspaces list too."
    }
}
