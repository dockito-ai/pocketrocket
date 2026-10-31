package com.pocketrocket.ui.git

import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pocketrocket.git.Diff
import com.pocketrocket.git.DiffRow
import com.pocketrocket.git.DiffSource
import com.pocketrocket.git.GitException

/**
 * A unified diff of one file. Long lines wrap so the whole change reads at phone width; rows are in a
 * LazyColumn, so only what is on screen is laid out, however long the diff.
 */
@Composable
fun DiffScreen(model: GitPanelModel, source: DiffSource, path: String, onBack: () -> Unit) {
    val repo = model.repo ?: return
    DiffView(path, repo.name, source, load = { repo.diff(source) }, onBack = onBack)
}

/** One file of a PR's diff (`gh pr diff`, split per file), in the same viewer. */
@Composable
fun PrDiffScreen(model: GitPanelModel, number: Int, path: String, onBack: () -> Unit) {
    DiffView(path, "#$number", number to path, load = {
        model.prFiles(number).firstOrNull { it.change.path == path }?.diff ?: throw GitException("$path is not in PR #$number.")
    }, onBack = onBack)
}

@Composable
private fun DiffView(path: String, rootName: String, key: Any, load: suspend () -> Diff, onBack: () -> Unit) {
    val diff = loadOrError(key) { load() }
    val c = gitColors
    GitScaffold(
        title = path.substringAfterLast('/'),
        subtitle = {
            val d = diff.value
            val dir = path.substringBeforeLast('/', rootName)
            MonoSubtitleCounts(dir, d?.added, d?.deleted)
        },
        onBack = onBack,
        titleStyle = MonoTitle,
        divider = true,
    ) { padding ->
        val d = diff.value
        if (d == null) { LoadingOrError(diff.error, Modifier.padding(padding)); return@GitScaffold }
        if (d.rows.isEmpty()) {
            LoadingOrError(if (d.binary) "Binary file; no text diff." else "No changes to show.", Modifier.padding(padding))
            return@GitScaffold
        }
        // Width of the number column follows the largest line number.
        val digits = d.rows.maxOf { (it as? DiffRow.Line)?.let { l -> maxOf(l.oldNumber ?: 0, l.newNumber ?: 0) } ?: 0 }.toString().length
        val indent = TextIndent(restLine = monoCharWidth() * (digits + 3))
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(d.rows, contentType = { _, row -> row::class }) { _, row ->
                when (row) {
                    is DiffRow.Hunk -> Text(
                        row.header,
                        style = CodeStyle,
                        color = c.hunk,
                        modifier = Modifier.fillMaxWidth().background(c.hunkBackground).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    is DiffRow.Line -> DiffLine(row, digits, indent)
                    is DiffRow.Note -> Text(
                        row.text,
                        style = CodeStyle,
                        fontStyle = FontStyle.Italic,
                        color = PrTheme.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            if (d.truncated) item {
                Text("Diff too large; the rest is not shown.", style = mockText(13.sp), color = PrTheme.colors.textMuted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/**
 * One diff line as a single Text (cheap to lay out while flinging): a gutter of number and sign, then
 * the code. A hanging indent the width of the gutter keeps wrapped code lined up under the code.
 */
@Composable
private fun DiffLine(line: DiffRow.Line, digits: Int, indent: TextIndent) {
    val c = gitColors
    val (background, color, sign) = when (line.kind) {
        '+' -> Triple(c.addedBackground, c.addedText, "+ ")
        '-' -> Triple(c.removedBackground, c.removedText, "− ")
        else -> Triple(Color.Transparent, PrTheme.colors.textSoft, "  ")
    }
    val text = remember(line, color) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.lineNumber)) { append((line.newNumber ?: line.oldNumber)?.toString().orEmpty().padStart(digits)) }
            append(' ')
            append(sign)
            append(line.text.replace("\t", "    "))
        }
    }
    Text(
        text,
        style = CodeStyle.copy(textIndent = indent),
        color = color,
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp),
    )
}

@Composable
private fun MonoSubtitleCounts(dir: String, added: Int?, deleted: Int?) {
    val c = gitColors
    Text(
        buildAnnotatedString {
            append(dir)
            if (added != null) { append(" · "); withStyle(SpanStyle(color = c.added)) { append("+$added") } }
            if (deleted != null) { append(" "); withStyle(SpanStyle(color = c.removed)) { append("−$deleted") } }
        },
        style = CodeStyle,
        color = PrTheme.colors.textMuted,
        maxLines = 1,
    )
}
