package com.pocketrocket.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/** A Markdown pipe table: a header row, a `|---|:---:|` separator and body rows. */
data class MarkdownTableBlock(val header: List<String>, val align: List<MarkdownTableBlock.Align>, val rows: List<List<String>>) {
    enum class Align { Start, Center, End }

    companion object {
        private val SeparatorCell = Regex(":?-+:?")

        /** The column alignments if [line] is a separator row with [columns] cells, else null. */
        fun separator(line: String, columns: Int): List<Align>? {
            if ('|' !in line) return null
            val cells = cells(line)
            if (cells.size != columns || !cells.all { SeparatorCell.matches(it) }) return null
            return cells.map {
                when {
                    it.startsWith(':') && it.endsWith(':') -> Align.Center
                    it.endsWith(':') -> Align.End
                    else -> Align.Start
                }
            }
        }

        /** The trimmed cells of a `| a | b |` row. `\|` and pipes inside `code` stay in the cell. */
        fun cells(line: String): List<String> {
            var row = line.trim()
            if (row.startsWith('|')) row = row.drop(1)
            if (row.endsWith('|') && !row.endsWith("\\|")) row = row.dropLast(1)
            val cells = mutableListOf<String>()
            val cell = StringBuilder()
            var inCode = false
            var i = 0
            while (i < row.length) {
                val ch = row[i]
                when {
                    ch == '\\' && i + 1 < row.length && row[i + 1] == '|' -> { cell.append('|'); i++ }
                    ch == '`' -> { inCode = !inCode; cell.append(ch) }
                    ch == '|' && !inCode -> { cells += cell.toString().trim(); cell.clear() }
                    else -> cell.append(ch)
                }
                i++
            }
            cells += cell.toString().trim()
            return cells
        }
    }
}

/** Widest a column's narrowest fit may be, so one long URL can't hog the row. */
private val MaxMinColumn = 140.dp

/**
 * Draws [table] as a grid. Columns take their natural width when the table fits the screen;
 * otherwise they shrink and wrap, and only scroll sideways when even that doesn't fit.
 */
@Composable
internal fun MarkdownTable(table: MarkdownTableBlock, spans: InlineStyles, color: Color) {
    val c = PrTheme.colors
    val style = PrType.body.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val columns = table.header.size
    val rows = listOf(table.header) + table.rows.map { row -> List(columns) { row.getOrElse(it) { "" } } }
    val grid = remember { Grid() }
    val shape = RoundedCornerShape(8.dp)

    BoxWithConstraints {
        val available = constraints.maxWidth
        val maxMin = with(LocalDensity.current) { MaxMinColumn.roundToPx() }
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    row.forEachIndexed { col, cell ->
                        Text(
                            inline(cell, spans),
                            style = style,
                            color = if (r == 0) c.text else color,
                            fontWeight = if (r == 0) FontWeight.SemiBold else null,
                            textAlign = when (table.align[col]) {
                                MarkdownTableBlock.Align.Start -> TextAlign.Start
                                MarkdownTableBlock.Align.Center -> TextAlign.Center
                                MarkdownTableBlock.Align.End -> TextAlign.End
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            },
            modifier = Modifier.horizontalScroll(rememberScrollState()).clip(shape)
                .drawBehind {
                    val line = 1.dp.toPx()
                    drawRect(c.raised, size = Size(size.width, grid.rowBottoms.firstOrNull()?.toFloat() ?: 0f))
                    grid.rowBottoms.dropLast(1).forEach { y -> drawRect(c.border, Offset(0f, y - line / 2), Size(size.width, line)) }
                }
                .border(1.dp, c.border, shape),
        ) { measurables, _ ->
            val natural = IntArray(columns)
            val narrowest = IntArray(columns)
            measurables.forEachIndexed { i, m ->
                val col = i % columns
                natural[col] = maxOf(natural[col], m.maxIntrinsicWidth(Constraints.Infinity))
                narrowest[col] = maxOf(narrowest[col], minOf(m.minIntrinsicWidth(Constraints.Infinity), maxMin))
            }
            val widths = columnWidths(natural, narrowest, available)
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % columns])) }
            val bottoms = IntArray(rows.size)
            var y = 0
            for (r in rows.indices) {
                y += (0 until columns).maxOf { placeables[r * columns + it].height }
                bottoms[r] = y
            }
            grid.rowBottoms = bottoms.toList()
            layout(widths.sum(), y) {
                placeables.forEachIndexed { i, p ->
                    val r = i / columns
                    val x = (0 until i % columns).sumOf { widths[it] }
                    p.place(x, if (r == 0) 0 else bottoms[r - 1])
                }
            }
        }
    }
}

/** Where each row ends, kept from layout for drawing the header and row lines. */
private class Grid { var rowBottoms: List<Int> = emptyList() }

/**
 * Natural widths if they fit in [available]; otherwise every column gets its narrowest width
 * and the room left is shared in proportion to how much more each column wants.
 */
internal fun columnWidths(natural: IntArray, narrowest: IntArray, available: Int): IntArray {
    if (natural.sum() <= available) return natural
    val spare = available - narrowest.sum()
    if (spare <= 0) return narrowest
    val wants = natural.indices.map { natural[it] - narrowest[it] }
    val totalWant = wants.sum().coerceAtLeast(1)
    return IntArray(natural.size) { narrowest[it] + (spare.toLong() * wants[it] / totalWant).toInt() }
}
