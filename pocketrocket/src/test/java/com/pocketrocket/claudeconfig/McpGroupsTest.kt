package com.pocketrocket.claudeconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpGroupsTest {
    private fun connector(name: String, state: McpState) = McpServer("claude.ai $name", state, scope = "claudeai", transport = "claudeai-proxy")
    private fun plugin(name: String, state: McpState) = McpServer("plugin:design:$name", state)
    private fun local(name: String, state: McpState) = McpServer(name, state, scope = "user", transport = "stdio")

    @Test fun connectedFirstThenWhatNeedsDoing() {
        val listing = McpGroups.of(
            listOf(local("off", McpState.Disabled), local("bad", McpState.Failed), connector("Drive", McpState.NeedsAuth), local("ok", McpState.Connected)),
            settled = true,
        )
        assertEquals(listOf(McpState.Connected, McpState.NeedsAuth, McpState.Failed, McpState.Disabled), listing.groups.map { it.state })
    }

    @Test fun earlyPluginEntriesAreNotAProblem() {
        val listing = McpGroups.of(listOf(plugin("slack", McpState.NeedsAuth), plugin("thing", McpState.Failed), local("ok", McpState.Connected)), settled = false)
        assertEquals(listOf(McpState.Connected), listing.groups.map { it.state })
        assertEquals(2, listing.loadingPlugins)
    }

    @Test fun onceSettledAPluginFailureShows() {
        val listing = McpGroups.of(listOf(plugin("thing", McpState.Failed)), settled = true)
        assertEquals(1, listing.count(McpState.Failed))
        assertEquals(0, listing.loadingPlugins)
    }

    @Test fun aPluginCopyOfAConnectorIsHiddenWhateverTheCase() {
        val listing = McpGroups.of(listOf(connector("Gmail", McpState.Connected), plugin("gmail", McpState.Failed)), settled = true)
        assertEquals(1, listing.total)
        assertEquals(1, listing.hiddenCopies)
    }

    @Test fun aConnectedPluginStays() {
        val listing = McpGroups.of(listOf(connector("Slack", McpState.NeedsAuth), plugin("slack", McpState.Connected)), settled = false)
        assertEquals(2, listing.total)
    }

    @Test fun aLongListIsSortedWithinItsGroup() {
        val many = (1..25).map { connector("C${25 - it}", McpState.NeedsAuth) }
        val names = McpGroups.of(many, settled = true).groups.single().servers.map { it.displayName }
        assertEquals(names.sortedBy { it.lowercase() }, names)
        assertEquals(25, names.size)
    }

    @Test fun summaryWordsAgree() {
        val listing = McpGroups.of(listOf(local("a", McpState.Connected), connector("B", McpState.NeedsAuth), connector("C", McpState.NeedsAuth)), settled = true)
        assertEquals("1 connected · 2 need sign-in", McpGroups.summary(listing))
    }

    @Test fun pendingKeepsItLoading() = assertFalse(McpRefresh.settled(listOf(connector("A", McpState.Pending)), 90_000))

    @Test fun nothingPendingIsntEnoughBeforeConnectorsCould() {
        val servers = listOf(local("a", McpState.Connected))
        assertFalse(McpRefresh.settled(servers, 1_000))
        assertTrue(McpRefresh.settled(servers, McpRefresh.CONNECTOR_WINDOW_MS))
    }

    @Test fun connectorsListedAndNothingPendingIsDone() = assertTrue(McpRefresh.settled(listOf(connector("A", McpState.NeedsAuth)), 1_000))

    @Test fun askingBacksOffAndNeverRacesAhead() {
        val gaps = (0..10).map(McpRefresh::delayMs)
        assertEquals(gaps.sorted(), gaps)
        assertTrue(gaps.first() >= 1_000)
        assertEquals(5_000L, gaps.last())
    }
}
