package com.pocketrocket.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.git.ChecksSummary
import com.pocketrocket.git.PrFilter
import com.pocketrocket.git.PullRequest
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * The PRs tab (BranchesPRs.html §2): Open / Mine / To review / Closed, then one card per PR with its checks and
 * review state. Without GitHub (no account, offline, another host) it shows the model's short note instead.
 */
@Composable
fun PrTab(model: GitPanelModel, onOpen: (GitRoute) -> Unit) {
    val c = PrTheme.colors
    val note = model.prNote
    val me = model.prs?.me
    Column(Modifier.fillMaxSize()) {
        if (note == null || model.openPrs != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PrFilter.entries.forEach { f ->
                    val label = if (f == PrFilter.ToReview && (model.toReview ?: 0) > 0) "${f.label} · ${model.toReview}" else f.label
                    GitChip(label, on = model.prFilter == f) { model.showPrs(f) }
                }
            }
        }
        val list = model.prList
        when {
            note != null -> Text(note, style = mockText(13.sp, color = c.textMuted), modifier = Modifier.padding(24.dp))
            list == null -> LoadingOrError(null)
            list.isEmpty() -> Text(
                when (model.prFilter) {
                    PrFilter.Open -> "No open pull requests."
                    PrFilter.Mine -> "You have no open pull requests here."
                    PrFilter.ToReview -> "Nothing waiting for your review."
                    PrFilter.Closed -> "No closed pull requests."
                },
                style = mockText(13.sp, color = c.textMuted),
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.number }) { pr -> PrCard(pr, me) { onOpen(GitRoute.Pr(pr.number)) } }
            }
        }
    }
}

/** One PR (`.pr`): title, number, branch, author and age, then pills; outlined amber when your review is asked for. */
@Composable
private fun PrCard(pr: PullRequest, me: String?, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val asked = pr.isOpen && pr.requested(me)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, if (asked) c.amberEdge else c.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(pr.title, style = mockText(14.sp, FontWeight.Medium, c.text), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${pr.number}", style = mockText(12.sp, color = c.textMuted, mono = true))
            Text(pr.headRef, style = mockText(12.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("· ${if (pr.byMe(me)) "you" else pr.author} · ${shortAge(pr.updated)}", style = mockText(12.sp, color = c.textMuted), maxLines = 1)
        }
        val pills = prPills(pr, me)
        if (pills.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills.forEach { (text, color) -> Pill(text, color) } }
    }
}

/** The pills of a PR card, as text and colour: state (draft, merged, closed), checks, then review. */
@Composable
private fun prPills(pr: PullRequest, me: String?): List<Pair<String, Color>> {
    val c = PrTheme.colors
    val pills = mutableListOf<Pair<String, Color>>()
    when {
        pr.state == "MERGED" -> pills += "Merged" to c.purple
        pr.state == "CLOSED" -> pills += "Closed" to c.redSoft
        pr.isDraft -> pills += "Draft" to c.textMuted
    }
    checksPill(ChecksSummary.of(pr.checks))?.let { pills += it }
    when {
        pr.isOpen && pr.requested(me) -> pills += "review requested" to c.amber
        pr.reviewDecision == "CHANGES_REQUESTED" -> pills += "changes requested" to c.redSoft
        pr.approvals > 0 -> pills += (if (pr.approvals == 1) "1 approval" else "${pr.approvals} approvals") to c.blue
    }
    return pills
}

/** "✓ 4/4 checks" green, "● 2 running" amber, "✗ 1 failing" red, or null without checks. */
@Composable
fun checksPill(s: ChecksSummary): Pair<String, Color>? {
    val c = PrTheme.colors
    return when {
        s.total == 0 -> null
        s.failing > 0 -> "✗ ${s.failing} failing" to c.redSoft
        s.running > 0 -> "● ${s.running} running" to c.amber
        else -> "✓ ${s.passing}/${s.total} checks" to c.green
    }
}
