package com.pocketrocket.claudeconfig

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Install or update stopped at a command the marketplace declares: to show, and run only if accepted. */
data class CommandToAccept(val pluginId: String, val isUpdate: Boolean, val shown: ShownCommand)

/** What the account screen's plugin section shows. Holds no values except a non-secret option's own, in [form]. */
data class PluginsState(
    val plugins: List<PluginInfo> = emptyList(),
    val loaded: Boolean = false,
    val problem: String? = null,
    /** What is under way, in words. One thing at a time per account. */
    val busy: String? = null,
    val notice: ServerNotice? = null,
    val marketplaces: List<MarketplaceInfo> = emptyList(),
    val marketplacesLoaded: Boolean = false,
    val available: List<AvailablePlugin> = emptyList(),
    val availableLoaded: Boolean = false,
    val details: PluginDetails? = null,
    val form: PluginForm? = null,
    val toAccept: CommandToAccept? = null,
)

/**
 * Plugins for one account, through [cli], for as long as [scope] lives. Every command runs in [io]; every failure ends
 * in [state], never in an exception. A change that has begun finishes even if the screen goes.
 */
class PluginPanel(
    private val cli: ConfigCli,
    private val scope: CoroutineScope,
    private val lock: Any,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(PluginsState())
    val state: StateFlow<PluginsState> = _state.asStateFlow()

    /** Runs [work] with the lock held, off the main thread, if nothing else is under way. */
    private fun task(busy: String, work: () -> (PluginsState) -> PluginsState) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busy, notice = null) }
        scope.launch {
            val next = withContext(io) { synchronized(lock) { work() } }
            _state.update { next(it).copy(busy = null) }
        }
    }

    private fun run(arguments: List<String>, timeoutMs: Long = ConfigCli.QUICK_MS, stdin: String? = null) = cli.run(arguments, stdin, timeoutMs)

    private fun problem(text: String) = ServerNotice(text, ServerNotice.Kind.Problem)

    /** `plugin list`, as a state change. */
    private fun listed(): (PluginsState) -> PluginsState {
        val output = run(PluginCommands.list)
        val plugins = PluginParser.list(output)
        val problem = if (plugins == null) CliText.failure(output, "Claude Code didn't list its plugins.") else null
        return { it.copy(plugins = plugins ?: it.plugins, loaded = true, problem = problem) }
    }

    fun load() = task("Asking Claude Code for its plugins") { listed() }

    fun loadMarketplaces() = task("Asking Claude Code for its marketplaces") {
        val output = run(PluginCommands.marketplaces)
        val found = PluginParser.marketplaces(output)
        val failure = if (found == null) problem(CliText.failure(output, "Claude Code didn't list its marketplaces.")) else null
        return@task { it.copy(marketplaces = found ?: it.marketplaces, marketplacesLoaded = true, notice = failure) }
    }

    /** About 3,600 rows from the official directory, so only when asked. Needs no login, may need the network. */
    fun loadAvailable() = task("Asking marketplaces what they offer") {
        val output = run(PluginCommands.available, ConfigCli.NETWORK_MS)
        val found = PluginParser.available(output)
        val failure = if (found == null) problem(CliText.failure(output, "Claude Code didn't list what is available. You can still install by id.")) else null
        return@task { it.copy(available = found ?: it.available, availableLoaded = true, notice = failure) }
    }

    fun openDetails(id: String) = task("Reading $id") {
        val output = run(PluginCommands.details(id))
        val details = PluginParser.details(id, output)
        val failure = if (details == null) problem(CliText.failure(output, "Claude Code didn't describe it.")) else null
        return@task { it.copy(details = details, notice = failure) }
    }

    fun closeDetails() = _state.update { it.copy(details = null) }

    fun openForm(id: String) = task("Reading the options of $id") {
        val output = run(PluginCommands.configureRead(id))
        val form = PluginParser.form(output)
        val failure = if (form == null) problem(CliText.failure(output, "Claude Code didn't list its options.")) else null
        return@task { it.copy(form = form, notice = failure) }
    }

    fun closeForm() = _state.update { it.copy(form = null) }

    /** Values go on stdin, and only those the user changed. The form stays open if they are refused. */
    fun saveForm(form: PluginForm, typed: Map<String, String>) {
        val values = PluginValues.changed(form, typed)
        PluginValues.problem(form, values)?.let { text ->
            _state.update { it.copy(notice = problem(text)) }
            return
        }
        if (values.isEmpty()) {
            closeForm()
            return
        }
        task("Saving the options of ${form.pluginId}") {
            val outcome = PluginParser.saved(run(PluginCommands.configureSave(form.pluginId), stdin = PluginValues.stdin(values)), values.values)
            val listing = if (outcome.ok) listed() else ({ s: PluginsState -> s })
            return@task { s -> listing(s).copy(form = if (outcome.ok) null else s.form, notice = notice(outcome)) }
        }
    }

    private fun notice(outcome: PluginOutcome) =
        ServerNotice(outcome.message, if (outcome.ok) ServerNotice.Kind.Info else ServerNotice.Kind.Problem)

    /** A change to the installed list. The list is read again whatever happened, as a failure can leave it changed. */
    private fun change(busy: String, arguments: List<String>, timeoutMs: Long, fallback: String, onOutcome: (PluginOutcome) -> CommandToAccept? = { null }) = task(busy) {
        val outcome = PluginParser.outcome(run(arguments, timeoutMs), fallback)
        val listing = listed()
        return@task { s ->
            val shown = onOutcome(outcome)
            listing(s).copy(toAccept = shown, notice = notice(if (outcome.ok) outcome.copy(message = outcome.message + NEW_SESSIONS) else outcome).takeIf { shown == null })
        }
    }

    fun setEnabled(plugin: PluginInfo, enabled: Boolean) {
        if (plugin.readOnly) return
        val arguments = if (enabled) PluginCommands.enable(plugin.id) else PluginCommands.disable(plugin.id)
        change(if (enabled) "Enabling ${plugin.name}" else "Disabling ${plugin.name}", arguments, ConfigCli.QUICK_MS, "Claude Code couldn't change it.")
    }

    fun uninstall(plugin: PluginInfo) {
        if (plugin.readOnly) return
        change("Uninstalling ${plugin.name}", PluginCommands.uninstall(plugin.id), ConfigCli.QUICK_MS, "Claude Code couldn't uninstall it.")
    }

    fun update(plugin: PluginInfo, accept: ShownCommand? = null) {
        if (plugin.readOnly) return
        change("Updating ${plugin.name}", PluginCommands.update(plugin.id, accept), ConfigCli.NETWORK_MS, "Claude Code couldn't update it.") { outcome ->
            acceptance(plugin.id, true, outcome, accept)
        }
    }

    /** Only after the user has seen where it comes from. */
    fun install(id: String, accept: ShownCommand? = null) {
        if (!PluginCommands.isSafeArgument(id)) {
            _state.update { it.copy(notice = problem("Enter a plugin as name or name@marketplace.")) }
            return
        }
        change("Installing $id", PluginCommands.install(id, accept), ConfigCli.NETWORK_MS, "Claude Code couldn't install it.") { outcome ->
            acceptance(id, false, outcome, accept)
        }
    }

    /** A command is asked about once per refusal: what was just accepted and still refused is a failure to show. */
    private fun acceptance(id: String, isUpdate: Boolean, outcome: PluginOutcome, accepted: ShownCommand?): CommandToAccept? =
        outcome.shownCommand?.takeIf { !outcome.ok && it.sha256 != accepted?.sha256 }?.let { CommandToAccept(id, isUpdate, it) }

    fun accept(request: CommandToAccept) {
        _state.update { it.copy(toAccept = null) }
        if (request.isUpdate) {
            _state.value.plugins.firstOrNull { it.id == request.pluginId }?.let { update(it, request.shown) }
        } else {
            install(request.pluginId, request.shown)
        }
    }

    fun decline() = _state.update { it.copy(toAccept = null, notice = ServerNotice("Left as it was. The command didn't run.", ServerNotice.Kind.Info)) }

    private fun marketplaceChange(busy: String, arguments: List<String>, timeoutMs: Long, fallback: String) = task(busy) {
        val outcome = PluginParser.outcome(run(arguments, timeoutMs), fallback)
        val output = run(PluginCommands.marketplaces)
        val found = PluginParser.marketplaces(output)
        val listing = listed()
        return@task { s ->
            listing(s).copy(
                marketplaces = found ?: s.marketplaces,
                marketplacesLoaded = true,
                // What it offers has changed: read it again next time it is wanted.
                available = emptyList(),
                availableLoaded = false,
                notice = notice(outcome),
            )
        }
    }

    fun addMarketplace(source: String) {
        val trimmed = source.trim()
        if (!PluginCommands.isSafeArgument(trimmed)) {
            _state.update { it.copy(notice = problem("Enter a GitHub repo as owner/repo, a git or https URL, or a path.")) }
            return
        }
        marketplaceChange("Adding the marketplace", PluginCommands.marketplaceAdd(trimmed), ConfigCli.NETWORK_MS, "Claude Code couldn't add it.")
    }

    fun updateMarketplace(name: String) =
        marketplaceChange("Updating $name", PluginCommands.marketplaceUpdate(name), ConfigCli.NETWORK_MS, "Claude Code couldn't update it.")

    fun removeMarketplace(name: String) =
        marketplaceChange("Removing $name", PluginCommands.marketplaceRemove(name), ConfigCli.QUICK_MS, "Claude Code couldn't remove it.")

    private companion object {
        const val NEW_SESSIONS = " New sessions on this account will have the change; running ones keep what they have."
    }
}
