package com.pocketrocket.health

/** How one check came out. */
enum class HealthState {
    Ok,
    NeedsAttention,

    /** Android gives no reliable way to read it; the row offers a guide instead. */
    CantCheck,

    /** Worth doing but not required; shown muted and left out of the attention counts. */
    Optional,
}

/** What a row's button does. */
sealed interface HealthFix {
    val label: String

    /** Opens a system screen (Xiaomi's own first where it has one, then Android's). */
    data class Open(val screen: SystemScreen, override val label: String = "Fix") : HealthFix

    /** Asks for a runtime permission; Health checks again when the app comes back. */
    data class Request(val permission: String, override val label: String = "Fix") : HealthFix

    /** A screen inside PocketRocket (a route of the app's back stack). */
    data class Navigate(val route: String, override val label: String = "Fix") : HealthFix

    /** Steps to follow by hand, with an optional screen to open from the guide. */
    data class Guide(
        val title: String,
        val steps: List<String>,
        val screen: SystemScreen? = null,
        val screenLabel: String = "Open settings",
        override val label: String = "Show me",
    ) : HealthFix
}

/** One row of the Health screen. */
data class HealthItem(
    val name: String,
    val state: HealthState,
    val note: String,
    val fix: HealthFix? = null,
)

/** A titled block of rows (KEEPING SESSIONS ALIVE, PERMISSIONS, SETUP, ACCOUNTS). */
data class HealthGroup(val title: String, val items: List<HealthItem>)

/** Everything one run of the checks found. */
data class HealthReport(
    val groups: List<HealthGroup>,
    val lastStop: LastStop.Stop?,
    val checkedAt: Long,
) {
    val attention: Int get() = groups.sumOf { g -> g.items.count { it.state == HealthState.NeedsAttention } }

    /** Needs-attention rows among the keep-alive ones, for the banner's "Fix the N items below". */
    val keepAliveAttention: Int
        get() = groups.firstOrNull { it.title == KEEP_ALIVE }?.items?.count { it.state == HealthState.NeedsAttention } ?: 0

    companion object {
        const val KEEP_ALIVE = "KEEPING SESSIONS ALIVE"
    }
}
