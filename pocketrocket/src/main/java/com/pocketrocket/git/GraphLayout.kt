package com.pocketrocket.git

/** A line in one half of a graph row, from lane [from] at one edge to lane [to] at the other. */
data class GraphEdge(val from: Int, val to: Int)

/**
 * How one commit's row of the graph is drawn: its dot in [lane]; [top] lines run from the row's top edge to its
 * middle (from = lane at the top, to = lane at the middle), [bottom] lines from the middle to the bottom edge
 * (from = lane at the middle, to = lane at the bottom). A straight line has from == to. [width] is the number of
 * lanes the row uses.
 */
data class GraphRow(val lane: Int, val top: List<GraphEdge>, val bottom: List<GraphEdge>, val width: Int)

/**
 * Lane assignment for commits in `git log --topo-order` order (children before parents), like `git log --graph`:
 * one lane per open line of history. A commit takes the lane that was waiting for it (or the first free one when
 * nothing was: a branch tip), its first parent continues that lane, every further parent (a merge) gets a lane of
 * its own unless one is already waiting for it, and lanes waiting for the same commit (a fork, seen from below)
 * join it there. Lanes are not shifted when one ends; the gap is reused. Pure Kotlin, unit-tested.
 */
object GraphLayout {

    fun layout(commits: List<GraphCommit>): List<GraphRow> {
        // lanes[i] is the sha the line in lane i is heading for, or null when the lane is free.
        val lanes = ArrayList<String?>()
        val rows = ArrayList<GraphRow>(commits.size)
        for (commit in commits) {
            var lane = lanes.indexOf(commit.sha)
            if (lane < 0) {
                lane = lanes.indexOf(null)
                if (lane < 0) { lanes.add(null); lane = lanes.lastIndex }
            }

            // Top half: every open line comes in; those heading for this commit bend into its lane.
            val top = ArrayList<GraphEdge>()
            lanes.forEachIndexed { i, sha ->
                when {
                    sha == null -> Unit
                    sha == commit.sha -> top += GraphEdge(i, lane)
                    else -> top += GraphEdge(i, i)
                }
            }
            for (i in lanes.indices) if (lanes[i] == commit.sha) lanes[i] = null

            // The first parent carries on in this lane; other parents (a merge) take a waiting lane or a new one.
            lanes[lane] = commit.parents.firstOrNull()
            val forks = ArrayList<Int>()
            for (parent in commit.parents.drop(1)) {
                var target = lanes.indexOf(parent)
                if (target < 0) {
                    target = lanes.indexOf(null)
                    if (target < 0) { lanes.add(null); target = lanes.lastIndex }
                    lanes[target] = parent
                    forks += target
                } else {
                    forks += target
                }
            }

            // Bottom half: lines carry on straight; this commit's lines leave from its dot.
            val bottom = ArrayList<GraphEdge>()
            lanes.forEachIndexed { i, sha ->
                if (sha == null) return@forEachIndexed
                if (i == lane) bottom += GraphEdge(lane, lane)
                else if (i !in forks || top.any { it.from == i && it.to == i }) bottom += GraphEdge(i, i)
            }
            forks.distinct().forEach { if (it != lane) bottom += GraphEdge(lane, it) }

            while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.lastIndex)
            val width = maxOf(
                lanes.size,
                (top.maxOfOrNull { maxOf(it.from, it.to) } ?: 0) + 1,
                (bottom.maxOfOrNull { maxOf(it.from, it.to) } ?: 0) + 1,
                lane + 1,
            )
            rows += GraphRow(lane, top, bottom, width)
        }
        return rows
    }
}
