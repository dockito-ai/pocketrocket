package com.pocketrocket.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLayoutTest {

    private fun commit(sha: String, vararg parents: String) = GraphCommit(sha, parents.toList(), emptyList(), "a", 0L, sha)

    private fun e(from: Int, to: Int) = GraphEdge(from, to)

    @Test
    fun parsesLogWithDecorations() {
        val out = "c3\u001fc2\u001fHEAD -> refs/heads/feat/x, refs/remotes/origin/feat/x, refs/remotes/origin/HEAD, tag: refs/tags/v1\u001fShankar\u001f300\u001fAdd x, y\u001e\n" +
            "c2\u001fc1 b1\u001f\u001fClaude\u001f200\u001fMerge b\u001e\n" +
            "c1\u001f\u001frefs/heads/main, refs/stash\u001fShankar\u001f100\u001fFirst\u001e\n"
        val commits = GitParsers.graph(out)
        assertEquals(listOf("c3", "c2", "c1"), commits.map { it.sha })
        assertEquals(listOf("c1", "b1"), commits[1].parents)
        assertTrue(commits[2].parents.isEmpty())
        assertEquals("Add x, y", commits[0].subject)
        assertEquals(300L, commits[0].time)
        assertEquals(
            listOf(
                GraphRef("feat/x", GraphRef.Kind.Local, head = true),
                GraphRef("origin/feat/x", GraphRef.Kind.Remote),
                GraphRef("v1", GraphRef.Kind.Tag),
            ),
            commits[0].refs,
        )
        assertEquals("feat/x", commits[0].refs[1].shortName)
        assertEquals(listOf(GraphRef("main", GraphRef.Kind.Local)), commits[2].refs)
        assertTrue(commits[1].refs.isEmpty())
    }

    @Test
    fun linearHistoryStaysInOneLane() {
        val rows = GraphLayout.layout(listOf(commit("c", "b"), commit("b", "a"), commit("a")))
        assertEquals(listOf(0, 0, 0), rows.map { it.lane })
        assertEquals(emptyList<GraphEdge>(), rows[0].top)
        assertEquals(listOf(e(0, 0)), rows[0].bottom)
        assertEquals(listOf(e(0, 0)), rows[1].top)
        assertEquals(listOf(e(0, 0)), rows[1].bottom)
        assertEquals(listOf(e(0, 0)), rows[2].top)
        assertEquals(emptyList<GraphEdge>(), rows[2].bottom)
        assertEquals(listOf(1, 1, 1), rows.map { it.width })
    }

    @Test
    fun forkJoinsAtTheCommonParent() {
        // Two branch tips x and y on top of b.
        val rows = GraphLayout.layout(listOf(commit("x", "b"), commit("y", "b"), commit("b", "a"), commit("a")))
        assertEquals(listOf(0, 1, 0, 0), rows.map { it.lane })
        // y starts a second lane while x's line passes by.
        assertEquals(listOf(e(0, 0)), rows[1].top)
        assertEquals(listOf(e(0, 0), e(1, 1)), rows[1].bottom)
        assertEquals(2, rows[1].width)
        // At b both lines come in; lane 1 bends into lane 0 and ends.
        assertEquals(listOf(e(0, 0), e(1, 0)), rows[2].top)
        assertEquals(listOf(e(0, 0)), rows[2].bottom)
        assertEquals(1, rows[3].width)
    }

    @Test
    fun mergeOpensALaneForTheSecondParent() {
        // m merges p2 into p1; both come from a.
        val rows = GraphLayout.layout(listOf(commit("m", "p1", "p2"), commit("p1", "a"), commit("p2", "a"), commit("a")))
        assertEquals(listOf(0, 0, 1, 0), rows.map { it.lane })
        assertEquals(listOf(e(0, 0), e(0, 1)), rows[0].bottom)
        assertEquals(listOf(e(0, 0), e(1, 1)), rows[1].top)
        assertEquals(listOf(e(0, 0), e(1, 1)), rows[1].bottom)
        assertEquals(listOf(e(0, 0), e(1, 1)), rows[2].top)
        assertEquals(listOf(e(0, 0), e(1, 1)), rows[2].bottom)
        assertEquals(listOf(e(0, 0), e(1, 0)), rows[3].top)
        assertEquals(emptyList<GraphEdge>(), rows[3].bottom)
    }

    @Test
    fun mergeIntoALaneAlreadyWaiting() {
        // t is a tip on b; m (another tip) merges b and c. b's lane already exists, so m's second line joins it.
        val rows = GraphLayout.layout(listOf(commit("t", "b"), commit("m", "c", "b"), commit("c", "b"), commit("b")))
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.lane })
        assertEquals(listOf(e(0, 0), e(1, 1), e(1, 0)), rows[1].bottom)
        assertEquals(listOf(e(0, 0), e(1, 0)), rows[3].top)
    }

    @Test
    fun freedLaneIsReused() {
        // After y's lane ends at b, the next tip z takes lane 1 again instead of opening lane 2.
        val rows = GraphLayout.layout(
            listOf(commit("x", "b"), commit("y", "b"), commit("b", "a"), commit("z", "a"), commit("a")),
        )
        assertEquals(listOf(0, 1, 0, 1, 0), rows.map { it.lane })
        assertEquals(2, rows.maxOf { it.width })
    }
}
