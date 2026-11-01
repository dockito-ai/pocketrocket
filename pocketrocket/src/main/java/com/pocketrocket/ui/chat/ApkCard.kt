package com.pocketrocket.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.apk.ApkActions
import com.pocketrocket.apk.ApkWatch
import com.pocketrocket.apk.BuiltApk
import com.pocketrocket.files.FileFormat
import com.pocketrocket.sessions.ChatItem
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The APK cards of a session's chat by tool id (decision 99), worked out off the main thread each time a Bash call
 * finishes. Demo sessions have none.
 */
@Composable
fun rememberApkCards(folder: String, demo: Boolean, items: List<ChatItem>): Map<String, List<BuiltApk>> {
    val watch = remember(folder) { ApkWatch(File(folder), File(TermuxConstants.TERMUX_HOME_DIR_PATH)) }
    val finished = if (demo) 0 else items.count { it is ChatItem.Tool && ApkWatch.ready(it) }
    val cards by produceState(emptyMap<String, List<BuiltApk>>(), watch, finished) {
        if (finished > 0) value = withContext(Dispatchers.IO) { watch.cards(items) }
    }
    return cards
}

/**
 * An APK a build in this session wrote: its name, size and folder (relative to the session's [folder]), then
 * Install, Share and Save to Downloads. Saving says where the copy went.
 */
@Composable
fun ApkCard(apk: BuiltApk, folder: String, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by rememberSaveable(apk.key) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val file = File(apk.path)
    fun say(message: String?) = message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().background(c.card, shape).border(1.dp, c.border, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).background(c.iconTile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = c.green, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(apk.name, style = PrType.code, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(FileFormat.size(apk.size), shownFolder(file.parent.orEmpty(), folder)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = PrType.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CardButton("Install", Modifier.fillMaxWidth(), fill = c.accent, content = c.onAccent, bold = true) {
            say(ApkActions.install(context, file))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Share", Modifier.weight(1f), border = c.border, content = c.text) { say(ApkActions.share(context, file)) }
            CardButton(if (saving) "Saving…" else "Save to Downloads", Modifier.weight(1.6f), border = c.border, content = c.text) {
                if (saving) return@CardButton
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { ApkActions.saveToDownloads(context.applicationContext, file) }
                    saving = false
                    if (result.startsWith(ApkActions.SAVED)) saved = result else say(result)
                }
            }
        }
        saved?.let { Text(it, style = PrType.caption, color = c.green) }
    }
}

/** The APK's folder as the chat shows it: relative to the session's [folder] when inside it, `~/…` under home. */
internal fun shownFolder(parent: String, folder: String): String {
    val home = TermuxConstants.TERMUX_HOME_DIR_PATH
    return when {
        parent == folder -> ""
        parent.startsWith("$folder/") -> parent.removePrefix("$folder/")
        parent == home -> "~"
        parent.startsWith("$home/") -> "~/" + parent.removePrefix("$home/")
        else -> parent
    }
}
