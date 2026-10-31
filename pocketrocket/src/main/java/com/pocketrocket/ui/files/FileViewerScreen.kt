package com.pocketrocket.ui.files

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.files.FileFormat
import com.pocketrocket.files.FileImages
import com.pocketrocket.files.FileIntents
import com.pocketrocket.files.FileKind
import com.pocketrocket.files.FileKinds
import com.pocketrocket.files.FileText
import com.pocketrocket.files.TextFile
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.git.CodePane
import com.pocketrocket.ui.git.GitScaffold
import com.pocketrocket.ui.git.LoadingOrError
import com.pocketrocket.ui.git.MarkdownPane
import com.pocketrocket.ui.git.MonoSubtitle
import com.pocketrocket.ui.git.MonoTitle
import com.pocketrocket.ui.git.isMarkdown
import com.pocketrocket.ui.git.loadOrError
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.workspaces.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the viewer shows for a file. */
private sealed interface Shown {
    class Lines(val file: TextFile) : Shown
    class Picture(val bitmap: Bitmap) : Shown
    /** No preview: [reason] says why. */
    class Other(val size: Long, val reason: String) : Shown
}

/** Reads [file] for the viewer: text up to 1 MB, an image scaled to fit, or why neither. Off the main thread. */
private suspend fun load(file: File): Shown = withContext(Dispatchers.IO) {
    if (!file.isFile) throw java.io.FileNotFoundException("This file isn't there any more.")
    val size = file.length()
    when (FileKinds.of(file)) {
        FileKind.Image -> FileImages.decode(file)?.let { Shown.Picture(it) } ?: Shown.Other(size, "This image can't be shown here.")
        FileKind.Text -> FileText.read(file)?.let { Shown.Lines(it) } ?: Shown.Other(size, "No preview for this kind of file.")
        FileKind.Other -> Shown.Other(size, "No preview for this kind of file.")
    }
}

/**
 * The file manager's viewer (decision 80), read only: text and code in the monospace code pane (the first 1 MB of
 * a bigger file, saying so), Markdown rendered with a Source switch, images inline, anything else offered to Open
 * with or Share. The ⋮ menu has Open with, Share and Copy path for every file.
 */
@Composable
fun FileViewerScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val file = remember(path) { File(path) }
    val loaded = loadOrError(path) { load(file) }
    var menu by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(true) }
    val shown = loaded.value
    val markdown = isMarkdown(path) && shown is Shown.Lines
    fun report(error: String?) { error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    GitScaffold(
        title = file.name,
        subtitle = { MonoSubtitle(Workspaces.tilde(file.parent.orEmpty()) + "  ·  read only") },
        onBack = onBack,
        titleStyle = MonoTitle,
        actions = {
            if (markdown) FilterChip(selected = !rendered, onClick = { rendered = !rendered }, label = { Text("Source") }, modifier = Modifier.padding(end = 4.dp))
            MoreMenu(
                listOf(
                    MenuItem("Open with") { report(FileIntents.openWith(context, file)) },
                    MenuItem("Share") { report(FileIntents.share(context, file)) },
                    MenuItem("Copy path") { FileIntents.copyPath(context, path); report("Path copied") },
                ),
                menu, { menu = it },
            )
        },
    ) { padding ->
        when (shown) {
            null -> LoadingOrError(loaded.error, Modifier.padding(padding))
            is Shown.Lines -> {
                val text = shown.file
                if (markdown && rendered) MarkdownPane(text.text, Modifier.padding(padding))
                else CodePane(
                    text.text, file.name,
                    if (text.truncated) "Only the first ${FileFormat.size(FileKinds.TEXT_LIMIT.toLong())} of ${FileFormat.size(text.size)} is shown. Open with another app to see it all." else null,
                    Modifier.padding(padding),
                )
            }
            is Shown.Picture -> Box(Modifier.fillMaxSize().padding(padding).padding(18.dp), contentAlignment = Alignment.Center) {
                Image(shown.bitmap.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            is Shown.Other -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(shown.reason, style = mockText(14.sp, color = c.text), textAlign = TextAlign.Center)
                Text(FileFormat.size(shown.size), style = mockText(12.sp, color = c.textMuted, mono = true))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!FileKinds.isPackage(file.name)) OutlineButton("Open with", { report(FileIntents.openWith(context, file)) }, Modifier.width(140.dp))
                    OutlineButton("Share", { report(FileIntents.share(context, file)) }, Modifier.width(140.dp))
                }
            }
        }
    }
}
