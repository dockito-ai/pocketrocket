package com.pocketrocket.claudeconfig

/** Servers of one state, in the order the list shows them. */
data class McpGroup(val state: McpState, val servers: List<McpServer>)

/**
 * The list as shown. [loadingPlugins] are plugin servers not connected yet while things still load; plugins often
 * report `needs-auth` or `failed` early and then drop off (`docs/claude-config.md`), so they are only counted.
 * [hiddenCopies] are plugin servers that repeat a connector of the same name and aren't connected.
 */
data class McpListing(val groups: List<McpGroup>, val loadingPlugins: Int = 0, val hiddenCopies: Int = 0) {
    fun count(state: McpState): Int = groups.firstOrNull { it.state == state }?.servers?.size ?: 0
    val total: Int get() = groups.sumOf { it.servers.size }
}

/** Sorting and hiding for the server list. Pure. */
object McpGroups {
    /** Connected first, then what needs doing about it, then the rest. */
    private val ORDER = listOf(McpState.Connected, McpState.NeedsAuth, McpState.Failed, McpState.Disabled, McpState.Pending, McpState.Unknown)

    /** [settled] is false while [McpRefresh] is still waiting for servers to load. */
    fun of(servers: List<McpServer>, settled: Boolean): McpListing {
        val connectors = servers.filter { it.isConnector }.map { it.displayName.lowercase() }.toSet()
        var copies = 0
        var loading = 0
        val shown = servers.filter { server ->
            val quietPlugin = server.isPlugin && server.state != McpState.Connected
            when {
                quietPlugin && server.displayName.lowercase() in connectors -> { copies++; false }
                quietPlugin && !settled -> { loading++; false }
                else -> true
            }
        }
        val groups = ORDER.mapNotNull { state ->
            shown.filter { it.state == state }
                .sortedBy { it.displayName.lowercase() }
                .takeIf { it.isNotEmpty() }
                ?.let { McpGroup(state, it) }
        }
        return McpListing(groups, loading, copies)
    }

    /** "4 connected · 3 need sign-in · 1 failed". */
    fun summary(listing: McpListing): String = listing.groups.mapNotNull { group ->
        val n = group.servers.size
        when (group.state) {
            McpState.Connected -> "$n connected"
            McpState.NeedsAuth -> if (n == 1) "1 needs sign-in" else "$n need sign-in"
            McpState.Failed -> "$n failed"
            McpState.Disabled -> "$n off"
            McpState.Pending -> "$n connecting"
            McpState.Unknown -> null
        }
    }.joinToString(" · ")
}

/**
 * When to ask `mcp_status` again. Servers start `pending`, and connectors aren't listed at all at first (by 25 s in
 * the probe), so "nothing pending" alone can stop too early. Pure.
 */
object McpRefresh {
    /** How long after a process starts its connectors may still turn up. */
    const val CONNECTOR_WINDOW_MS = 30_000L

    /** Longest one refresh keeps asking, whatever it hears. */
    const val MAX_MS = 60_000L

    /** Nothing pending, and either connectors have turned up or the window for them has passed. */
    fun settled(servers: List<McpServer>, sinceProcessStartMs: Long): Boolean =
        servers.none { it.state == McpState.Pending } &&
            (servers.any { it.isConnector } || sinceProcessStartMs >= CONNECTOR_WINDOW_MS)

    /** Gaps between asks: quick at first, then every 5 s. */
    fun delayMs(attempt: Int): Long = when (attempt) {
        0, 1 -> 1_000L
        2 -> 2_000L
        3 -> 3_000L
        else -> 5_000L
    }
}
