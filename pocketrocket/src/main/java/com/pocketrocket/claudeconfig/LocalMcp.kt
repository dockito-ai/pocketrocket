package com.pocketrocket.claudeconfig

import com.pocketrocket.browser.ChromiumMcp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How a change to the list went. Every message has been scrubbed. */
sealed interface McpChange {
    data object Done : McpChange

    /** Nothing changed. */
    data class Failed(val message: String) : McpChange

    /** The new entry was refused, and the old one is back as it was. */
    data class Restored(val message: String) : McpChange

    /** The new entry was refused and the old one couldn't be put back: the server is gone. Very unlikely, so it must say so. */
    data class Lost(val message: String) : McpChange
}

/**
 * Add, edit and remove for one account, through [cli]. Blocking; run off the main thread and under [AccountLocks].
 * [readConfig] gives the text of that account's `.claude.json` (null when there is none); it may throw. Values live
 * only in local variables here, for as long as one call takes.
 */
class LocalMcpOps(private val cli: ConfigCli, private val readConfig: () -> String?, private val execHelper: String) {

    /** The servers, or null with [problem] set. */
    class Listing(val servers: List<LocalMcpServer>?, val problem: String?)

    fun list(): Listing {
        val text = try {
            readConfig()
        } catch (e: Exception) {
            return Listing(null, "Couldn't read this account's config.")
        }
        if (text == null) return Listing(emptyList(), null)
        val servers = McpConfigFile.servers(text) ?: return Listing(null, "Couldn't read this account's config.")
        return Listing(servers, null)
    }

    fun add(draft: McpDraft): McpChange {
        val config = when (val built = McpDrafts.build(draft, null, execHelper)) {
            is McpBuild.Problem -> return McpChange.Failed(built.text)
            is McpBuild.Ready -> built.config
        }
        return addJson(draft.name, config).let { if (it == null) McpChange.Done else McpChange.Failed(it) }
    }

    /**
     * Remove then add: Claude Code has no edit. Everything that can be refused here is refused before the remove, and
     * the old entry is read first, so a refused add puts it back unchanged.
     */
    fun edit(draft: McpDraft): McpChange {
        if (draft.name == ChromiumMcp.SERVER) return McpChange.Failed("${draft.name} is managed in Settings, Tools.")
        val text = try {
            readConfig()
        } catch (e: Exception) {
            null
        } ?: return McpChange.Failed("Couldn't read this account's config, so nothing was changed.")
        val old = McpConfigFile.entry(text, draft.name) ?: return McpChange.Failed("${draft.name} isn't in this account's config any more.")
        val config = when (val built = McpDrafts.build(draft, old, execHelper)) {
            is McpBuild.Problem -> return McpChange.Failed(built.text)
            is McpBuild.Ready -> built.config
        }
        val removed = cli.run(McpCommands.remove(draft.name), null, ConfigCli.QUICK_MS)
        if (!removed.ok) return McpChange.Failed(CliText.failure(removed, COULD_NOT_CHANGE, listOf(old, config)))

        val refused = addJson(draft.name, config, old) ?: return McpChange.Done
        return if (addJson(draft.name, old, config) == null) McpChange.Restored(refused) else McpChange.Lost(refused)
    }

    fun remove(name: String): McpChange {
        if (name == ChromiumMcp.SERVER) return McpChange.Failed("$name is managed in Settings, Tools.")
        val output = cli.run(McpCommands.remove(name), null, ConfigCli.QUICK_MS)
        return if (output.ok) McpChange.Done else McpChange.Failed(CliText.failure(output, COULD_NOT_CHANGE))
    }

    /** Null when added, else why not. The command's text is scrubbed of [config] and of [also]. */
    private fun addJson(name: String, config: JSONObject, also: JSONObject? = null): String? {
        val output = cli.run(McpCommands.addJson(name, config), null, ConfigCli.QUICK_MS)
        return if (output.ok) null else CliText.failure(output, COULD_NOT_CHANGE, listOfNotNull(config, also))
    }

    private companion object {
        const val COULD_NOT_CHANGE = "Claude Code couldn't change it, and gave no reason."
    }
}

/** A row of the account screen's list: the entry, and what the last check said of it, if it still applies. */
data class LocalMcpRow(val server: LocalMcpServer, val status: McpServer?, val line: String, val help: String?)

/** Joins the file's list with `mcp_status`. Pure. Only [McpServer.state] from a check made after the last change counts as a result. */
object LocalMcpRows {
    fun of(
        servers: List<LocalMcpServer>,
        statuses: List<McpServer>,
        checkedAt: Long?,
        changedAt: Map<String, Long>,
        checking: Boolean,
        execHelper: String,
    ): List<LocalMcpRow> = servers.map { server ->
        val fresh = checkedAt != null && checkedAt >= (changedAt[server.name] ?: 0L)
        val status = statuses.firstOrNull { it.name == server.name && (it.scope == null || it.scope == "user") }?.takeIf { fresh }
        val hasHelper = StdioAdvice.LD_PRELOAD in server.envKeys
        LocalMcpRow(server, status, line(status, checking, fresh, checkedAt), StdioAdvice.explain(status?.error, hasHelper, execHelper))
    }

    private fun line(status: McpServer?, checking: Boolean, fresh: Boolean, checkedAt: Long?): String = when {
        status == null && checking -> "Checking whether it starts"
        status == null && checkedAt == null -> "Not checked yet"
        status == null && !fresh -> "Changed, not checked yet"
        status == null -> "Not seen in the last check"
        status.state == McpState.Connected -> (if (status.toolCount == 1) "Connected · 1 tool" else "Connected · ${status.toolCount} tools")
        status.state == McpState.Failed -> status.error ?: "Couldn't start"
        status.state == McpState.NeedsAuth -> "Needs sign-in"
        status.state == McpState.Disabled -> "Off in the checked folder"
        status.state == McpState.Pending -> "Still starting"
        else -> "State unknown"
    }
}

/** What the account screen's local server list shows. Holds nothing secret. */
data class LocalMcpState(
    val servers: List<LocalMcpServer> = emptyList(),
    val loaded: Boolean = false,
    val problem: String? = null,
    /** What is under way, in words. One thing at a time. */
    val busy: String? = null,
    val notice: ServerNotice? = null,
    /** When each server last changed here, so an older check doesn't vouch for it. */
    val changedAt: Map<String, Long> = emptyMap(),
)

/**
 * The list and its actions for one account, for as long as [scope] lives. A change that has begun finishes even if the
 * screen goes: the blocking work in [io] can't be cancelled halfway, so an edit never stops between remove and add.
 * [onChanged] asks for a real status, since nothing here can tell whether a server starts.
 */
class LocalMcpPanel(
    private val ops: LocalMcpOps,
    private val scope: CoroutineScope,
    private val lock: Any,
    private val onChanged: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LocalMcpState())
    val state: StateFlow<LocalMcpState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            val listing = withContext(io) { synchronized(lock) { ops.list() } }
            apply(listing)
        }
    }

    private fun apply(listing: LocalMcpOps.Listing) = _state.update {
        it.copy(servers = listing.servers ?: it.servers, loaded = true, problem = listing.problem)
    }

    /** [onSaved] runs only when the change went in, so a refused form stays open with what was typed. */
    fun add(draft: McpDraft, onSaved: () -> Unit) =
        change(draft.name, "Adding ${draft.name}", onSaved, "Added ${draft.name}. New sessions on this account will start it; running ones keep what they have. Checking whether it starts.") { ops.add(draft) }

    fun edit(draft: McpDraft, onSaved: () -> Unit) =
        change(draft.name, "Saving ${draft.name}", onSaved, "Saved ${draft.name}. New sessions on this account will use it; running ones keep what they have. Checking whether it starts.") { ops.edit(draft) }

    fun remove(name: String) =
        change(name, "Removing $name", {}, "Removed $name. New sessions on this account won't start it; running ones keep it until they end.") { ops.remove(name) }

    private fun change(name: String, busy: String, onSaved: () -> Unit, done: String, work: () -> McpChange) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busy, notice = null) }
        scope.launch {
            val (change, listing) = withContext(io) { synchronized(lock) { work().let { it to ops.list() } } }
            apply(listing)
            _state.update {
                it.copy(
                    busy = null,
                    notice = notice(change, name, done),
                    changedAt = if (change is McpChange.Failed) it.changedAt else it.changedAt + (name to now()),
                )
            }
            if (change == McpChange.Done) onSaved()
            if (change !is McpChange.Failed) onChanged()
        }
    }

    private fun notice(change: McpChange, name: String, done: String): ServerNotice = when (change) {
        McpChange.Done -> ServerNotice(done, ServerNotice.Kind.Info)
        is McpChange.Failed -> ServerNotice(change.message, ServerNotice.Kind.Problem)
        is McpChange.Restored -> ServerNotice("Claude Code refused the change: ${change.message}\nThe old $name is back as it was.", ServerNotice.Kind.Problem)
        is McpChange.Lost -> ServerNotice("Claude Code refused the change: ${change.message}\nThe old $name couldn't be put back either, so it is no longer on this account. Add it again.", ServerNotice.Kind.Problem)
    }
}
