package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.AccountCli
import com.pocketrocket.claudeconfig.AccountLocks
import com.pocketrocket.claudeconfig.AvailablePlugin
import com.pocketrocket.claudeconfig.McpState
import com.pocketrocket.claudeconfig.PluginCatalog
import com.pocketrocket.claudeconfig.PluginDetails
import com.pocketrocket.claudeconfig.PluginForm
import com.pocketrocket.claudeconfig.PluginInfo
import com.pocketrocket.claudeconfig.PluginOption
import com.pocketrocket.claudeconfig.PluginPanel
import com.pocketrocket.claudeconfig.PluginsState
import com.pocketrocket.profiles.ClaudeAccountEntry
import com.pocketrocket.ui.accounts.Confirm
import com.pocketrocket.ui.accounts.QuietCard
import com.pocketrocket.ui.accounts.SectionLabel
import com.pocketrocket.ui.accounts.StatusDot
import com.pocketrocket.ui.chat.ChatSheet
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.RadioRow
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/**
 * The account's plugins, through `claude plugin` with that account's config dir. Only on a button, like Connections, so
 * opening the page starts nothing extra. Plugins run code on the phone, so what installs them always shows the source.
 */
@Composable
internal fun PluginsSection(entry: ClaudeAccountEntry) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val panel = remember(entry.id) { PluginPanel(AccountCli(context, entry.configDir), scope, AccountLocks.of(entry.id)) }
    val state by panel.state.collectAsState()
    var adding by remember(entry.id) { mutableStateOf(false) }
    var showMarketplaces by remember(entry.id) { mutableStateOf(false) }
    var uninstalling by remember(entry.id) { mutableStateOf<PluginInfo?>(null) }

    val c = PrTheme.colors
    val free = state.busy == null
    QuietCard {
        SectionLabel("Plugins")
        if (!state.loaded) {
            Text("See this account's plugins. Starts Claude Code for a moment; no message is sent and no usage is counted.", style = mockText(13.sp, color = c.textMuted))
        } else {
            Text("Each plugin runs code on this phone with the app's access. Changes apply to new sessions, not running ones.", style = mockText(13.sp, color = c.textMuted))
            state.problem?.let { Text("Couldn't list them: $it", style = mockText(12.sp, color = c.redSoft)) }
            if (state.plugins.isEmpty() && state.problem == null) Text("None installed.", style = mockText(13.sp, color = c.textMuted))
            state.plugins.forEach { plugin ->
                key(plugin.id) {
                    PluginRow(plugin, panel, free, onUninstall = { uninstalling = plugin })
                }
            }
        }
        ConfigStatus(state.busy, state.notice.takeIf { !adding && !showMarketplaces && state.form == null })
        if (!state.loaded) {
            OutlineButton("Show plugins", onClick = panel::load, enabled = free, modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Add a plugin", onClick = { adding = true }, enabled = free, modifier = Modifier.weight(1f))
                OutlineButton("Marketplaces", onClick = { showMarketplaces = true }, enabled = free, modifier = Modifier.weight(1f))
            }
        }
    }

    if (adding) AddPluginSheet(state, panel, onDismiss = { adding = false })
    if (showMarketplaces) MarketplacesSheet(state, panel, onDismiss = { showMarketplaces = false })
    state.details?.let { DetailsSheet(it, onDismiss = panel::closeDetails) }
    state.form?.let { OptionsSheet(it, state, panel) }
    uninstalling?.let { plugin ->
        Confirm(
            "Uninstall ${plugin.name}?",
            "Removes it from this account, with its saved data. New sessions won't have it; running ones keep it until they end.",
            "Uninstall",
            onDismiss = { uninstalling = null },
        ) { panel.uninstall(plugin) }
    }
    state.toAccept?.let { request ->
        ReviewDialog("Run this command?", "Run it", onDismiss = panel::decline, onConfirm = { panel.accept(request) }) {
            Text("${request.pluginId} is installed by running a command from its marketplace, on this phone, with the app's access:", style = mockText(14.sp, color = c.textMuted))
            Text(request.shown.command, style = mockText(12.sp, color = c.text, mono = true))
            Text("Only this exact command is accepted. Don't run it unless you trust where it comes from.", style = mockText(12.sp, color = c.amber))
        }
    }
}

@Composable
private fun PluginRow(plugin: PluginInfo, panel: PluginPanel, free: Boolean, onUninstall: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    var expanded by remember { mutableStateOf(false) }
    val line = listOfNotNull(
        plugin.version,
        when (plugin.enabled) {
            true -> "on"
            false -> "off"
            null -> null
        },
        plugin.marketplace,
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.iconTile, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(stateColor(if (plugin.enabled == true) McpState.Connected else McpState.Disabled))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(plugin.name, style = mockText(14.sp, color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = mockText(11.sp, color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plugin.readOnlyReason?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
                if (plugin.mcpServers.isNotEmpty()) {
                    Text("MCP servers: " + plugin.mcpServers.joinToString(", "), style = mockText(12.sp, color = c.textMuted))
                }
                ActionPill("Details and token cost", accent = false, enabled = free) { panel.openDetails(plugin.id) }
                if (!plugin.readOnly) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (plugin.enabled == true) ActionPill("Disable", accent = false, enabled = free) { panel.setEnabled(plugin, false) }
                        if (plugin.enabled == false) ActionPill("Enable", accent = false, enabled = free) { panel.setEnabled(plugin, true) }
                        if (plugin.hasOptions) ActionPill("Configure", accent = false, enabled = free) { panel.openForm(plugin.id) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("Update", accent = false, enabled = free) { panel.update(plugin) }
                        ActionPill("Uninstall", accent = false, enabled = free, onClick = onUninstall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsSheet(details: PluginDetails, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    ChatSheet(onDismiss) {
        Text(details.pluginId, style = mockText(17.sp, FontWeight.SemiBold, c.text))
        details.alwaysOn?.let { Text("Always on: $it", style = mockText(13.sp, color = c.text)) }
        Text(details.text, style = mockText(11.sp, color = c.textSoft, mono = true))
    }
}

/**
 * `plugin configure`. A secret field starts blank and blank keeps what is saved: Claude Code never hands a sensitive
 * value back. What is typed lives only here, and goes on stdin. There is no way to clear a value by leaving it blank.
 */
@Composable
private fun OptionsSheet(form: PluginForm, state: PluginsState, panel: PluginPanel) {
    val c = PrTheme.colors
    val typed = remember(form.pluginId) { mutableStateMapOf<String, String>() }
    ChatSheet(onDismiss = panel::closeForm) {
        Text("Options of ${form.displayName}", style = mockText(17.sp, FontWeight.SemiBold, c.text))
        Text("Saved in Claude Code's config. New sessions on this account will use them.", style = mockText(12.sp, color = c.textMuted))
        form.options.forEach { option ->
            key(option.key) { OptionField(option, typed[option.key], onChange = { typed[option.key] = it }) }
        }
        ConfigStatus(state.busy, state.notice)
        AccentButton("Save", onClick = { panel.saveForm(form, typed.toMap()) }, enabled = state.busy == null, height = 44.dp, radius = 12.dp, fontSize = 14.sp)
        Spacer(Modifier.imePadding())
    }
}

@Composable
private fun OptionField(option: PluginOption, typed: String?, onChange: (String) -> Unit) {
    val c = PrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(option.title + if (option.required) " · required" else "", style = mockText(13.sp, color = c.text))
        option.description?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
        when {
            option.secret -> SecretField(typed.orEmpty(), onChange, if (option.configured) "Saved. Leave blank to keep it." else "Value")
            option.choices.isNotEmpty() -> option.choices.forEach { choice ->
                RadioRow(choice, null, selected = (typed ?: option.current) == choice) { onChange(choice) }
            }
            else -> MockTextField(typed ?: option.current.orEmpty(), onChange, placeholder = "Value")
        }
    }
}

/** Search what marketplaces offer, or name a plugin. Nothing installs without the source on screen and a yes. */
@Composable
private fun AddPluginSheet(state: PluginsState, panel: PluginPanel, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    var query by remember { mutableStateOf("") }
    var typedId by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf<String?>(null) }

    // One at a time, as the panel runs them: each starts when the one before has ended.
    LaunchedEffect(state.busy, state.marketplacesLoaded, state.availableLoaded) {
        if (state.busy == null) {
            if (!state.marketplacesLoaded) panel.loadMarketplaces() else if (!state.availableLoaded) panel.loadAvailable()
        }
    }

    ChatSheet(onDismiss) {
        Text("Add a plugin", style = mockText(17.sp, FontWeight.SemiBold, c.text))
        MockTextField(query, { query = it }, placeholder = "Search")
        ConfigStatus(state.busy, state.notice)
        val found = remember(state.available, state.plugins, query) { PluginCatalog.search(state.available, state.plugins, query) }
        found.forEach { plugin -> key(plugin.id) { AvailableRow(plugin, enabled = state.busy == null) { installing = plugin.id } } }
        if (state.availableLoaded && found.isEmpty()) Text("Nothing matches.", style = mockText(13.sp, color = c.textMuted))
        if (found.size == PluginCatalog.MAX_SHOWN) Text("Showing the first ${PluginCatalog.MAX_SHOWN}. Search to narrow it down.", style = mockText(12.sp, color = c.textMuted))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Or by name", style = mockText(13.sp, color = c.text))
            MockTextField(typedId, { typedId = it }, placeholder = "name@marketplace", mono = true)
            OutlineButton("Install…", onClick = { installing = typedId.trim() }, enabled = state.busy == null && typedId.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.imePadding())
    }

    installing?.let { id ->
        ReviewDialog(
            "Install $id?", "Install",
            onDismiss = { installing = null },
            onConfirm = {
                installing = null
                panel.install(id)
                onDismiss()
            },
        ) {
            Text("From:", style = mockText(14.sp, color = c.textMuted))
            Text(PluginCatalog.sourceOf(id, state.available, state.marketplaces), style = mockText(12.sp, color = c.text, mono = true))
            Text("A plugin runs code on this phone with the app's access to your files and accounts. Install only what you trust. Applies to new sessions.", style = mockText(14.sp, color = c.textMuted))
        }
    }
}

@Composable
private fun AvailableRow(plugin: AvailablePlugin, enabled: Boolean, onClick: () -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.iconTile, shape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(plugin.name, style = mockText(14.sp, color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        plugin.description?.let { Text(it, style = mockText(12.sp, color = c.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(plugin.source, style = mockText(11.sp, color = c.textFaint, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MarketplacesSheet(state: PluginsState, panel: PluginPanel, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    var source by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val free = state.busy == null

    LaunchedEffect(Unit) { if (!state.marketplacesLoaded) panel.loadMarketplaces() }

    ChatSheet(onDismiss) {
        Text("Marketplaces", style = mockText(17.sp, FontWeight.SemiBold, c.text))
        Text("Where plugins are offered from, on this account.", style = mockText(12.sp, color = c.textMuted))
        ConfigStatus(state.busy, state.notice)
        if (state.marketplacesLoaded && state.marketplaces.isEmpty()) Text("None added.", style = mockText(13.sp, color = c.textMuted))
        state.marketplaces.forEach { marketplace ->
            key(marketplace.name) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(marketplace.name, style = mockText(14.sp, color = c.text))
                    Text(marketplace.source, style = mockText(11.sp, color = c.textMuted, mono = true))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("Update", accent = false, enabled = free) { panel.updateMarketplace(marketplace.name) }
                        ActionPill("Remove", accent = false, enabled = free) { removing = marketplace.name }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add one", style = mockText(13.sp, color = c.text))
            MockTextField(source, { source = it }, placeholder = "owner/repo, a URL or a path", mono = true)
            OutlineButton("Add…", onClick = { adding = source.trim() }, enabled = free && source.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.imePadding())
    }

    adding?.let { value ->
        ReviewDialog(
            "Add this marketplace?", "Add",
            onDismiss = { adding = null },
            onConfirm = {
                adding = null
                source = ""
                panel.addMarketplace(value)
            },
        ) {
            Text(value, style = mockText(12.sp, color = c.text, mono = true))
            Text("Claude Code downloads its list. Its plugins can then be installed here, and they run code on this phone, so add only what you trust.", style = mockText(14.sp, color = c.textMuted))
        }
    }
    removing?.let { name ->
        Confirm(
            "Remove $name?",
            "This also uninstalls every plugin from it on this account, and deletes their options, secrets and data.",
            "Remove",
            onDismiss = { removing = null },
        ) { panel.removeMarketplace(name) }
    }
}
