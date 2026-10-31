package com.pocketrocket.ui.git

import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.ChangeArea
import com.pocketrocket.git.FileBlob
import com.pocketrocket.git.TreeEntry
import com.pocketrocket.ui.chat.MarkdownText

/**
 * Trims what the chat's MarkdownText does not render and would show as noise in a README: badges and
 * images, link targets (the text stays), HTML tags, rules and reference definitions.
 */
internal fun forReading(markdown: String): String = markdown
    .replace(Regex("""\[!\[[^\]]*]\([^)]*\)]\([^)]*\)"""), "")
    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""<[^>\n]+>"""), "")
    .replace(Regex("""(?m)^\s*(\*{3,}|-{3,}|_{3,})\s*$"""), "")
    .replace(Regex("""(?m)^\[[^\]]+]:\s+\S+.*$"""), "")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()

/**
 * One folder at HEAD, GitHub-mobile style: the last commit, the entries (folders first, changed files
 * flagged), then the folder's README rendered below.
 */
@Composable
fun BrowseScreen(model: GitPanelModel, dir: String, onBack: () -> Unit, onOpen: (GitRoute) -> Unit) {
    val repo = model.repo ?: return
    val listing = loadOrError(dir) {
        val entries = repo.tree(dir)
        val readme = entries.filter { !it.isDir && it.name.startsWith("readme", ignoreCase = true) }
            .minByOrNull { if (isMarkdown(it.path)) 0 else 1 }
        entries to readme?.let { runCatching { repo.file(it.path, maxBytes = 256 * 1024) }.getOrNull() }
    }
    val flags = remember(model.status) {
        model.status?.changes.orEmpty().associate { it.path to if (it.area == ChangeArea.Untracked || it.kind == 'A') "new" else "modified" }
    }

    GitScaffold(
        title = if (dir.isEmpty()) repo.name else dir.substringAfterLast('/'),
        subtitle = { MonoSubtitle(listOf(repo.name, dir).filter { it.isNotEmpty() }.joinToString(" / ") + "  ·  " + (model.status?.branch?.head ?: "HEAD")) },
        onBack = onBack,
        titleStyle = MonoTitle,
    ) { padding ->
        val loaded = listing.value
        if (loaded == null) { LoadingOrError(listing.error, Modifier.padding(padding)); return@GitScaffold }
        val (entries, readme) = loaded
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
            if (dir.isEmpty()) model.commits.firstOrNull()?.let { last ->
                item(key = "last") { LastCommit(last.author, last.subject, relativeTime(last.time)) }
            }
            if (entries.isEmpty()) item { Text("Nothing committed here yet.", style = mockText(13.sp), color = PrTheme.colors.textMuted) }
            itemsIndexed(entries, key = { _, e -> e.path }) { index, entry ->
                val flag = if (entry.isDir) flags.keys.firstOrNull { it.startsWith(entry.path + "/") }?.let { "changed" } else flags[entry.path]
                EntryRow(entry, flag, first = index == 0, last = index == entries.lastIndex) {
                    when {
                        entry.isSubmodule -> Unit
                        entry.isDir -> onOpen(GitRoute.Browse(entry.path))
                        else -> onOpen(GitRoute.File(entry.path))
                    }
                }
            }
            readme?.text?.let { text ->
                item(key = "readme") { Readme(readme, text) }
            }
        }
    }
}

@Composable
private fun LastCommit(author: String, subject: String, time: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(PrTheme.colors.card, RoundedCornerShape(12.dp))
            .border(1.dp, PrTheme.colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(24.dp).background(PrTheme.colors.border, CircleShape), contentAlignment = Alignment.Center) {
            Text(author.take(1).uppercase(), fontSize = 11.sp)
        }
        Text(subject, style = PrType.caption, color = PrTheme.colors.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(time, style = PrType.caption, color = PrTheme.colors.textMuted)
    }
}

@Composable
private fun EntryRow(entry: TreeEntry, flag: String?, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = if (first) 14.dp else 0.dp, topEnd = if (first) 14.dp else 0.dp, bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp)
    val c = gitColors
    Column(Modifier.fillMaxWidth().background(PrTheme.colors.bar, shape).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (entry.isDir) "▸" else if (entry.isSubmodule) "⇢" else "≡",
                color = if (entry.isDir) c.type else PrTheme.colors.textMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp),
            )
            Text(entry.name, style = CodeStyle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (flag != null) Text(flag, fontSize = 11.sp, color = if (flag == "new") c.added else c.modified)
        }
        if (!last) HorizontalDivider(color = PrTheme.colors.raised)
    }
}

@Composable
private fun Readme(blob: FileBlob, text: String) {
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth()
            .border(1.dp, PrTheme.colors.border, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(blob.path.substringAfterLast('/'), style = CodeStyle, color = PrTheme.colors.textMuted)
        if (isMarkdown(blob.path)) MarkdownText(remember(text) { forReading(text) }) else Text(text, style = CodeStyle)
    }
}

/** A file at HEAD, read only: numbered, coloured lines, or the rendered page for Markdown. */
@Composable
fun FileScreen(model: GitPanelModel, path: String, onBack: () -> Unit) {
    val repo = model.repo ?: return
    val file = loadOrError(path) { repo.file(path) }
    val markdown = isMarkdown(path)
    var rendered by remember { mutableStateOf(true) }
    GitScaffold(
        title = path.substringAfterLast('/'),
        subtitle = { MonoSubtitle(path.substringBeforeLast('/', repo.name) + "  ·  read only") },
        onBack = onBack,
        titleStyle = MonoTitle,
        actions = {
            if (markdown) FilterChip(selected = !rendered, onClick = { rendered = !rendered }, label = { Text("Source") }, modifier = Modifier.padding(end = 8.dp))
        },
    ) { padding ->
        val blob = file.value
        if (blob == null) { LoadingOrError(file.error, Modifier.padding(padding)); return@GitScaffold }
        val text = blob.text
        if (text == null) { LoadingOrError("Binary file (${blob.sizeBytes / 1024} KB); not shown.", Modifier.padding(padding)); return@GitScaffold }
        if (markdown && rendered) {
            MarkdownPane(text, Modifier.padding(padding))
            return@GitScaffold
        }
        CodePane(text, path.substringAfterLast('/'), if (blob.truncated) "File too large; only the first 1 MB is shown." else null, Modifier.padding(padding))
    }
}
