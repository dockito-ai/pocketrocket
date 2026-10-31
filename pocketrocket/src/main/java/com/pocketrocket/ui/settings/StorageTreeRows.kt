package com.pocketrocket.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.pocketrocket.usage.StorageNode
import com.pocketrocket.usage.StorageTree
import com.pocketrocket.usage.TreeRow
import com.pocketrocket.usage.UsageText
import com.pocketrocket.usage.flattenTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Levels that indent; deeper rows stay put and name their folder instead, so names keep their room. */
private const val INDENT_LEVELS = 3

/**
 * Which rows of the tree are open and what's been loaded. A level loads the first time it opens, in [scope], so it
 * stops on leaving the screen. Closing a row stops any load under it; what's loaded stays for next time.
 */
internal class StorageTreeState(private val tree: StorageTree, private val scope: CoroutineScope) {
    private val open = mutableStateMapOf<String, Boolean>()
    private val loaded = mutableStateMapOf<String, List<StorageNode>>()
    private val loads = HashMap<String, Job>()

    fun rows(): List<TreeRow> = flattenTree(tree.roots) { node -> if (open[node.id] == true) loaded[node.id] else null }

    fun isOpen(node: StorageNode) = open[node.id] == true

    fun isCounting(node: StorageNode) = isOpen(node) && node.id !in loaded

    fun toggle(node: StorageNode) {
        if (isOpen(node)) close(node) else open(node)
    }

    private fun open(node: StorageNode) {
        open[node.id] = true
        if (node.id in loaded) return
        lateinit var job: Job
        job = scope.launch {
            try {
                loaded[node.id] = withContext(Dispatchers.IO) { tree.children(node) }
            } finally {
                if (loads[node.id] === job) loads.remove(node.id)
            }
        }
        loads[node.id] = job
    }

    private fun close(node: StorageNode) {
        open.remove(node.id)
        loads.keys.filter { it == node.id || it.startsWith(node.id + "/") }.forEach { id ->
            loads.remove(id)?.cancel()
            open.remove(id)
        }
    }
}

/** One row of the tree: the Usage row look, indented, with a chevron that turns if it opens. */
@Composable
internal fun StorageRow(row: TreeRow, state: StorageTreeState) {
    val node = row.node
    val deep = row.depth > INDENT_LEVELS
    val note = listOfNotNull(node.note, node.place?.takeIf { deep }?.let { "in $it" }).joinToString(" · ")
    ValueRow(
        name = node.name,
        value = if (state.isCounting(node)) "Counting…" else UsageText.storage(node.bytes),
        note = note,
        modifier = Modifier.padding(start = 12.dp * minOf(row.depth, INDENT_LEVELS)),
        onClick = if (node.opens) ({ state.toggle(node) }) else null,
        trailing = { Chevron(node.opens, state.isOpen(node)) },
    )
}

/** Always takes its width, so the figures of rows that don't open still line up with those that do. */
@Composable
internal fun Chevron(shown: Boolean, open: Boolean) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Box(Modifier.width(10.dp), contentAlignment = Alignment.Center) {
        if (shown) Text("›", style = PrType.body.copy(color = PrTheme.colors.textMuted), modifier = Modifier.rotate(turn))
    }
}
