package com.pocketrocket.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.GraphCommit
import com.pocketrocket.git.GraphEdge
import com.pocketrocket.git.GraphRef
import com.pocketrocket.git.GraphRow
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

private val LaneGap = 20.dp
private val LaneStart = 14.dp
private val RowHeight = 50.dp
private const val MAX_LANES = 10

/** Lane colours in order, from the theme: the first lane (usually the default branch) orange, then green, blue... */
@Composable
private fun laneColors(): List<Color> = PrTheme.colors.let { listOf(it.accent, it.green, it.blue, it.purple, it.amber, it.teal, it.redSoft, it.lilac) }

/**
 * The Graph tab (BranchesPRs.html §5): "This branch" / "All branches", then the commits with their lanes on the
 * left, branch names and PR numbers on the commits. Tap opens a commit's files; long-press opens [CommitActions].
 * [prFor] gives the open PR number of a branch name, when known.
 */
@Composable
fun GraphTab(model: GitPanelModel, prFor: (String) -> Int?, onOpen: (GitRoute) -> Unit, onOpenPr: (Int) -> Unit) {
    val c = PrTheme.colors
    val commits = model.graph
    val rows = model.graphRows
    var pressed by remember { mutableStateOf<GraphCommit?>(null) }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (model.graph.isEmpty()) model.loadGraph(reset = true) }
    LaunchedEffect(state, commits.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (commits.isNotEmpty() && it >= commits.size - 40) model.loadMoreGraph() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GitChip("This branch", on = !model.graphAll) { model.showGraph(false) }
            GitChip("All branches", on = model.graphAll) { model.showGraph(true) }
        }
        if (commits.isEmpty() || rows.size != commits.size) {
            if (model.graphEnd && commits.isEmpty()) Text("No commits yet.", style = mockText(13.sp), color = c.textMuted, modifier = Modifier.padding(24.dp))
            else LoadingOrError(null)
            return@Column
        }
        val lanes = rows.maxOf { it.width }.coerceIn(1, MAX_LANES)
        val graphWidth = LaneStart + LaneGap * (lanes - 1) + 16.dp
        val colors = laneColors()
        LazyColumn(state = state, contentPadding = PaddingValues(start = 4.dp, end = 18.dp, bottom = 12.dp)) {
            itemsIndexed(commits, key = { _, item -> item.sha }) { i, commit ->
                GraphCommitRow(
                    commit, rows[i], graphWidth, colors, model.defaultBranch, prFor,
                    onClick = { onOpen(GitRoute.CommitDetail(commit.toCommit())) },
                    onLongClick = { pressed = commit },
                )
            }
        }
    }

    pressed?.let { commit ->
        CommitActions(model, commit, prFor, onDismiss = { pressed = null }, onOpen = onOpen, onOpenPr = onOpenPr)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GraphCommitRow(
    commit: GraphCommit,
    row: GraphRow,
    graphWidth: androidx.compose.ui.unit.Dp,
    colors: List<Color>,
    defaultBranch: String?,
    prFor: (String) -> Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().height(RowHeight).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(graphWidth).fillMaxHeight()) { drawLanes(row, colors) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(commit.subject, style = mockText(12.5.sp, color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val prs = LinkedHashSet<Int>()
                commit.refs.forEach { ref ->
                    RefPill(ref, ref.kind == GraphRef.Kind.Local && ref.name == defaultBranch)
                    if (ref.kind != GraphRef.Kind.Tag) prFor(ref.shortName)?.let { prs += it }
                }
                prs.forEach { Pill("#$it", c.purple, c.purpleSurface, mono = true) }
                Text(
                    "${commit.author} · ${shortAge(commit.time)}",
                    style = mockText(11.sp, color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (commit.refs.isEmpty() && prs.isEmpty()) 0.dp else 4.dp),
                )
            }
        }
    }
}

/** A branch or tag name on a commit: the default branch orange, local green, origin/ blue, tags plain. */
@Composable
private fun RefPill(ref: GraphRef, isDefault: Boolean) {
    val c = PrTheme.colors
    when {
        ref.kind == GraphRef.Kind.Tag -> Pill(ref.name, c.textSoft, c.raised, mono = true)
        ref.kind == GraphRef.Kind.Remote -> Pill(ref.name, c.blue, c.blueSurface, mono = true)
        isDefault -> Pill(ref.name, c.accent, c.accentPill, mono = true)
        else -> Pill(ref.name, c.green, c.greenSurface, mono = true)
    }
}

/**
 * One row of lanes: lines in the top half from the row above into the middle, in the bottom half from the middle
 * down, then the commit's dot. A line that changes lane is a curve; each takes the colour of the lane at its far end
 * from the dot's row (the branch it belongs to).
 */
private fun DrawScope.drawLanes(row: GraphRow, colors: List<Color>) {
    val gap = LaneGap.toPx()
    val start = LaneStart.toPx()
    val stroke = 2.dp.toPx()
    val mid = size.height / 2f
    fun x(lane: Int) = start + lane * gap
    fun color(lane: Int) = colors[lane % colors.size]
    fun edge(e: GraphEdge, y0: Float, y1: Float, color: Color) {
        if (e.from == e.to) {
            drawLine(color, Offset(x(e.from), y0), Offset(x(e.to), y1), strokeWidth = stroke)
        } else {
            val ym = (y0 + y1) / 2f
            val path = Path().apply {
                moveTo(x(e.from), y0)
                cubicTo(x(e.from), ym, x(e.to), ym, x(e.to), y1)
            }
            drawPath(path, color, style = Stroke(width = stroke))
        }
    }
    row.top.forEach { edge(it, 0f, mid, color(it.from)) }
    row.bottom.forEach { edge(it, mid, size.height, color(it.to)) }
    drawCircle(color(row.lane), radius = 5.dp.toPx(), center = Offset(x(row.lane), mid))
}

/**
 * Long-press on a commit (BranchesPRs.html §5): its files, Check out when it is a branch tip, New branch here,
 * Open PR when a branch on it has one, Copy SHA.
 */
@Composable
private fun CommitActions(
    model: GitPanelModel,
    commit: GraphCommit,
    prFor: (String) -> Int?,
    onDismiss: () -> Unit,
    onOpen: (GitRoute) -> Unit,
    onOpenPr: (Int) -> Unit,
) {
    val context = LocalContext.current
    val c = PrTheme.colors
    var naming by remember { mutableStateOf(false) }
    val head = model.status?.branch?.head
    val branchRefs = commit.refs.filter { it.kind != GraphRef.Kind.Tag }
    // A local branch first, else a remote one (checked out as a local branch of the same name); not the current one.
    val target = (branchRefs.filter { it.kind == GraphRef.Kind.Local } + branchRefs.filter { it.kind == GraphRef.Kind.Remote })
        .firstOrNull { it.shortName != head }
    val pr = branchRefs.firstNotNullOfOrNull { prFor(it.shortName) }

    if (!naming) GitSheet(onDismiss) {
        Text(commit.subject, style = mockText(15.sp, FontWeight.SemiBold, c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            (listOf(commit.shortSha) + commit.refs.map { it.name } + listOfNotNull(pr?.let { "#$it" })).joinToString(" · "),
            style = mockText(12.sp, color = c.textMuted, mono = true),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        SheetItem("See files and diffs") { onDismiss(); onOpen(GitRoute.CommitDetail(commit.toCommit())) }
        if (target != null) SheetItem("Check out", mono = target.shortName) { onDismiss(); model.checkout(target) }
        SheetItem("New branch here") { naming = true }
        if (pr != null) SheetItem("Open PR #$pr") { onDismiss(); onOpenPr(pr) }
        SheetItem("Copy SHA", last = true) { onDismiss(); copyText(context, commit.sha, "SHA") }
    }
    if (naming) NameDialog(
        title = "New branch here",
        note = "At ${commit.shortSha} ${commit.subject}, and switched to.",
        placeholder = "feat/something",
        confirm = "Create",
        onDismiss = onDismiss,
        onConfirm = { onDismiss(); model.newBranchAt(it, commit.sha) },
    )
}
