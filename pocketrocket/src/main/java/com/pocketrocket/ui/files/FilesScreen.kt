package com.pocketrocket.ui.files

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pocketrocket.files.FileEntry
import com.pocketrocket.files.FileIntents
import com.pocketrocket.files.FileListing
import com.pocketrocket.files.FileOps
import com.pocketrocket.files.FileRules
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.termux.Termux
import com.pocketrocket.ui.LocalRouteOnTop
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A folder's entries as last read, or why it couldn't be. */
private class Listing(val dir: String, val entries: List<FileEntry>?, val error: String?)

/**
 * The file manager (decision 80): one folder at a time under home, starting at [start] (home, or a workspace's
 * folder). Folders first, then files, with size or item count and when they changed; dot files behind a switch
 * in the ⋮ menu. The path bar opens any folder above; back (the arrow, the button or the gesture) goes up a folder
 * until the one it opened at, then leaves. Files open in the read-only viewer ([Routes.file]). Every disk read
 * and change runs off the main thread. [onMoved] and [onDeleted] tell the app a folder moved or went, so screens
 * below this one (a workspace) follow it or close; [onDeleted] also gets the folder shown ([here]).
 */
@Composable
fun FilesScreen(
    start: String,
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    onMoved: (from: String, to: String) -> Unit = { _, _ -> },
    onDeleted: (path: String, here: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val scope = rememberCoroutineScope()
    val home = AccountPaths.home.path
    val root = if (FileRules.isInside(start, home)) home else start
    var dir by rememberSaveable { mutableStateOf(start) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val workspaces by Workspaces.state.collectAsState()

    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose {} }
    val listing by produceState(Listing(dir, null, null), dir, refresh) {
        val reading = dir
        value = withContext(Dispatchers.IO) {
            runCatching { Listing(reading, FileListing.read(File(reading)), null) }
                .getOrElse { Listing(reading, null, if (File(reading).exists()) "Can't read this folder." else "This folder isn't there any more.") }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(dir) { list.scrollToItem(0) }

    val up = FileRules.upFrom(dir, start, root)
    fun back() { if (up != null) dir = up else onBack() }
    BackHandler(enabled = up != null && LocalRouteOnTop.current) { back() }

    /** Runs a change off the main thread with [label] showing, then [done] if it worked, then reads the folder again. */
    fun change(label: String, done: () -> Unit = {}, op: () -> String?) {
        busy = label
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(op).getOrElse { it.message ?: "It didn't work." } }
            busy = null
            if (error != null) toast(context, error) else done()
            refresh++
        }
    }

    val fixedRoots = listOf(home, Workspaces.projectsRoot.path)
    val protectedRoots = fixedRoots + workspaces.map { it.path }

    fun actions(entry: FileEntry): List<MenuItem> {
        val file = File(entry.path)
        val renameKind = FileRules.renameKind(entry.path, entry.isLink, fixedRoots, workspaces.map { it.path })
        return listOfNotNull(
            MenuItem("Copy path") { FileIntents.copyPath(context, entry.path); toast(context, "Path copied") },
            if (!entry.isDir) MenuItem("Open with") { FileIntents.openWith(context, file)?.let { toast(context, it) } } else null,
            if (!entry.isDir) MenuItem("Share") { FileIntents.share(context, file)?.let { toast(context, it) } } else null,
            if (renameKind != FileRules.RenameKind.Refused) MenuItem("Rename") { renaming = entry } else null,
            MenuItem("Delete", danger = true) { deleting = entry },
        )
    }

    MockPage {
        MockTopBar("Files", MockIcons.Back, ::back, bottom = 0.dp) {
            MoreMenu(
                listOf(
                    MenuItem(if (showHidden) "Hide hidden files" else "Show hidden files") { showHidden = !showHidden },
                    MenuItem("New folder") { creating = true },
                    MenuItem("Copy path") { FileIntents.copyPath(context, dir); toast(context, "Path copied") },
                    MenuItem("Open a terminal here") { Termux.openTerminalIn(context, File(dir), File(dir).name) },
                ),
                menu, { menu = it },
            )
        }
        PathBar(FileRules.crumbs(dir, root)) { dir = it }

        // The last read is shown only once it is of this folder.
        val shown = listing.takeIf { it.dir == dir }
        val error: String? = shown?.error
        val all: List<FileEntry>? = shown?.entries
        val entries: List<FileEntry> = all?.let { FileListing.visible(it, showHidden) }.orEmpty()
        when {
            error != null -> Centered { Text(error, style = mockText(13.sp, color = c.textMuted)) }
            all == null -> Centered { CircularProgressIndicator(color = c.accent) }
            entries.isEmpty() -> Centered {
                Text(if (all?.isNotEmpty() == true) "Only hidden files here. Show them from ⋮." else "Empty folder.", style = mockText(13.sp, color = c.textMuted))
            }
            else -> LazyColumn(
                Modifier.weight(1f),
                state = list,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 24.dp),
            ) {
                itemsIndexed(entries, key = { _, e -> e.path }) { index, entry ->
                    FileRow(entry, first = index == 0, last = index == entries.lastIndex, actions = actions(entry)) {
                        if (entry.isDir) dir = entry.path else navigate(Routes.file(entry.path))
                    }
                }
            }
        }
        busy?.let { Text(it, style = mockText(12.sp, color = c.textMuted), modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }
    }

    if (creating) NameDialog("New folder", "", "Create", onDismiss = { creating = false }) { name ->
        change("Creating $name…") { FileOps.newFolder(File(dir), name) }
    }
    renaming?.let { entry ->
        val kind = FileRules.renameKind(entry.path, entry.isLink, fixedRoots, workspaces.map { it.path })
        NameDialog(
            "Rename", entry.name, "Rename",
            note = if (kind == FileRules.RenameKind.Workspace) "Workspaces in it keep their accounts and move with it." else null,
            onDismiss = { renaming = null },
        ) { name ->
            val to = File(File(entry.path).parentFile, name).path
            change("Renaming ${entry.name}…", done = { onMoved(entry.path, to) }) {
                // Through the workspace list, so the workspaces in it keep their accounts; it never replaces anything.
                if (kind == FileRules.RenameKind.Workspace) Workspaces.move(entry.path, to)
                else FileOps.rename(File(entry.path), name)
            }
        }
    }
    deleting?.let { entry ->
        val serious = FileRules.deleteGuard(entry.path, entry.isLink, protectedRoots) == FileRules.DeleteGuard.Serious
        DeleteDialog(entry, serious, onDismiss = { deleting = null }) {
            change("Deleting ${entry.name}…", done = { if (!entry.isLink) onDeleted(entry.path, dir) }) {
                val error = FileOps.delete(File(entry.path))
                // A workspace whose folder is gone comes off the list; one only partly deleted stays.
                if (error == null && !entry.isLink) {
                    Workspaces.all.filter { FileRules.isInside(it.path, entry.path) }.forEach { Workspaces.remove(it.path) }
                }
                error
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Centered(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
