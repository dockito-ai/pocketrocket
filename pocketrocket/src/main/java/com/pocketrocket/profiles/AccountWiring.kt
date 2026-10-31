package com.pocketrocket.profiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Which accounts a folder uses. [claude], [github] and [dockito] are account ids; null means "the
 * default", and follows it when the default changes. Workspaces (Phase B) store their explicit choice here.
 */
data class FolderBinding(val folder: String, val claude: String?, val github: String?, val dockito: String? = null)

/**
 * Per-folder account wiring (decisions 20 and 21). Folders are bound to a Claude and a GitHub account in
 * one small file, ~/.pocketrocket/accounts/bindings.json; everything else is generated from it:
 *
 * - [envFor] gives the environment for anything started in a folder: CLAUDE_CONFIG_DIR of its Claude
 *   account (absent for ~/.claude), GH_CONFIG_DIR of its GitHub account, and Dockito's shared
 *   DOCKITO_CONFIG_DIR with DOCKITO_ACCOUNT when the folder picked its own Dockito account. Sessions use it.
 * - ~/.gitconfig gets a managed block: an `[include]` of the default GitHub account's git config, then an
 *   `[includeIf "gitdir:<folder>/"]` per bound folder, so git anywhere under that folder (app, Claude or
 *   terminal) uses that account's identity and key. Lines outside the block are left alone.
 * - ~/.pocketrocket/accounts/env.sh does the same as [envFor] for interactive shells, on every prompt.
 *
 * A folder without a binding, or under none, uses the defaults. The global `gh auth switch` is never
 * used: two sessions in two folders can run as two GitHub accounts at once.
 */
object AccountWiring {
    private val _bindings by lazy { MutableStateFlow(load()) }
    val bindings: StateFlow<List<FolderBinding>> get() = _bindings.asStateFlow()

    /**
     * Binds [folder] (and everything under it) to the given accounts; null means the default. [dockito]
     * keeps the folder's own Dockito choice unless given.
     */
    fun bind(folder: String, claude: String?, github: String?, dockito: String? = ownBinding(folder)?.dockito) = mutate { list ->
        val path = AccountPaths.normalize(folder)
        list.filterNot { it.folder == path } + FolderBinding(path, claude, github, dockito)
    }

    private fun ownBinding(folder: String): FolderBinding? =
        AccountPaths.normalize(folder).let { path -> _bindings.value.firstOrNull { it.folder == path } }

    fun unbind(folder: String) = mutate { list -> list.filterNot { it.folder == AccountPaths.normalize(folder) } }

    /** The binding that applies to [folder]: its own, or the nearest bound folder above it. */
    fun bindingFor(folder: String): FolderBinding? {
        val path = AccountPaths.normalize(folder)
        return _bindings.value
            .filter { path == it.folder || path.startsWith(it.folder.trimEnd('/') + "/") }
            .maxByOrNull { it.folder.length }
    }

    fun claudeFor(folder: String): ClaudeAccountEntry =
        bindingFor(folder)?.claude?.let(ClaudeAccounts::get) ?: ClaudeAccounts.default

    fun githubFor(folder: String): GitHubAccountEntry? =
        bindingFor(folder)?.github?.let(GitHubAccounts::get) ?: GitHubAccounts.default

    fun dockitoFor(folder: String): DockitoAccountEntry? = boundDockito(folder) ?: DockitoAccounts.default

    /**
     * The Dockito account [folder] picked itself, or null to follow the default: the CLI's login in use,
     * so DOCKITO_ACCOUNT is left unset and `dockito switch` in a terminal takes effect there.
     */
    private fun boundDockito(folder: String): DockitoAccountEntry? = bindingFor(folder)?.dockito?.let(DockitoAccounts::get)

    /**
     * The environment for a process started in [folder]. [claude] overrides the folder's Claude account
     * (a session can run on another account than its workspace).
     */
    fun envFor(folder: String, claude: ClaudeAccountEntry = claudeFor(folder)): Map<String, String> =
        buildMap {
            claude.configDir?.let { put("CLAUDE_CONFIG_DIR", it.path) }
            putAll(toolEnvFor(folder))
        }

    /**
     * [envFor] without the Claude part: GH_CONFIG_DIR when there is a GitHub account, and Dockito's
     * DOCKITO_CONFIG_DIR (plus DOCKITO_ACCOUNT when the folder picked one). Sessions add it to their Claude
     * profile's environment.
     */
    fun toolEnvFor(folder: String): Map<String, String> = buildMap {
        githubFor(folder)?.let { put("GH_CONFIG_DIR", it.ghConfigDir.path) }
        putAll(DockitoAccounts.env(boundDockito(folder)))
    }

    /** Folders bound explicitly to an account, for "in use by". */
    fun foldersUsing(claude: String? = null, github: String? = null, dockito: String? = null): List<String> = _bindings.value
        .filter { (claude != null && it.claude == claude) || (github != null && it.github == github) || (dockito != null && it.dockito == dockito) }
        .map { it.folder }

    /** Drops a removed account from every binding, so those folders use the default again. */
    internal fun forget(claude: String? = null, github: String? = null, dockito: String? = null) = mutate { list ->
        list.map {
            it.copy(
                claude = it.claude.takeUnless { id -> claude != null && id == claude },
                github = it.github.takeUnless { id -> github != null && id == github },
                dockito = it.dockito.takeUnless { id -> dockito != null && id == dockito },
            )
        }
    }

    /** Rewrites ~/.gitconfig's managed block and env.sh from the bindings and the current defaults. */
    @Synchronized
    fun refresh() {
        if (!AccountPaths.home.isDirectory) return // before the first-run setup
        runCatching { writeGitConfig() }
        runCatching { writeShellEnv() }
    }

    @Synchronized
    private fun mutate(change: (List<FolderBinding>) -> List<FolderBinding>) {
        val next = change(_bindings.value)
        if (next == _bindings.value) return
        val json = JSONObject().put("bindings", JSONArray(next.map {
            JSONObject().put("folder", it.folder)
                .put("claude", it.claude ?: JSONObject.NULL)
                .put("github", it.github ?: JSONObject.NULL)
                .put("dockito", it.dockito ?: JSONObject.NULL)
        }))
        writeAtomically(AccountPaths.bindings, json.toString(2))
        _bindings.value = next
        refresh()
    }

    private fun load(): List<FolderBinding> {
        val array = runCatching { JSONObject(AccountPaths.bindings.readText()).getJSONArray("bindings") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            FolderBinding(o.getString("folder"), o.stringOrNull("claude"), o.stringOrNull("github"), o.stringOrNull("dockito"))
        }
    }

    private fun JSONObject.stringOrNull(key: String) = if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

    // ---- ~/.gitconfig ----

    private const val BEGIN = "# >>> PocketRocket accounts (generated, change them in the app) >>>"
    private const val END = "# <<< PocketRocket accounts <<<"
    private val BLOCK = Regex("\\n*" + Regex.escape(BEGIN) + ".*?" + Regex.escape(END) + "\\n?", RegexOption.DOT_MATCHES_ALL)

    /** The managed block: default account first, then bound folders, deeper ones last so they win. */
    internal fun gitConfigBlock(): String {
        val lines = mutableListOf<String>()
        GitHubAccounts.default?.let { lines += "[include]"; lines += "\tpath = ${it.gitConfig.path}" }
        _bindings.value.sortedBy { it.folder.length }.forEach { b ->
            val account = b.github?.let(GitHubAccounts::get) ?: return@forEach
            lines += "[includeIf \"gitdir:${b.folder.trimEnd('/')}/\"]"
            lines += "\tpath = ${account.gitConfig.path}"
        }
        return if (lines.isEmpty()) "" else (listOf(BEGIN) + lines + END).joinToString("\n", postfix = "\n")
    }

    private fun writeGitConfig() {
        val file = AccountPaths.userGitConfig
        val old = if (file.exists()) file.readText() else ""
        val rest = old.replace(BLOCK, "\n").trimEnd()
        val block = gitConfigBlock()
        val text = when {
            block.isEmpty() -> if (rest.isEmpty()) "" else rest + "\n"
            rest.isEmpty() -> block
            else -> "$rest\n\n$block"
        }
        if (text == old) return
        if (text.isEmpty()) file.delete() else writeAtomically(file, text)
    }

    // ---- interactive shells ----

    private const val BASHRC_LINE = "[ -f ~/.pocketrocket/accounts/env.sh ] && . ~/.pocketrocket/accounts/env.sh  # PocketRocket accounts"

    /**
     * env.sh: a function run before every prompt that sets CLAUDE_CONFIG_DIR, GH_CONFIG_DIR and
     * DOCKITO_ACCOUNT for the current directory, mirroring [envFor] (DOCKITO_CONFIG_DIR is the same
     * everywhere, so it is exported once). ~/.bashrc sources it (one line,
     * added once).
     */
    internal fun shellEnv(): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        fun pair(claude: ClaudeAccountEntry, github: GitHubAccountEntry?, dockito: DockitoAccountEntry?) =
            "__pocketrocket_set ${q(claude.configDir?.path.orEmpty())} ${q(github?.ghConfigDir?.path.orEmpty())} ${q(dockito?.email.orEmpty())}"
        return buildString {
            append("# Generated by PocketRocket from its account settings; rewritten on every change.\n")
            append("export DOCKITO_CONFIG_DIR=${q(AccountPaths.dockitoConfig.path)}\n")
            append("__pocketrocket_set() {\n")
            append("  if [ -n \"\$1\" ]; then export CLAUDE_CONFIG_DIR=\"\$1\"; else unset CLAUDE_CONFIG_DIR; fi\n")
            append("  if [ -n \"\$2\" ]; then export GH_CONFIG_DIR=\"\$2\"; else unset GH_CONFIG_DIR; fi\n")
            append("  if [ -n \"\$3\" ]; then export DOCKITO_ACCOUNT=\"\$3\"; else unset DOCKITO_ACCOUNT; fi\n")
            append("}\n")
            append("__pocketrocket_accounts() {\n")
            append("  case \"\$PWD/\" in\n")
            _bindings.value.sortedByDescending { it.folder.length }.forEach { b ->
                append("    ${q(b.folder.trimEnd('/') + "/")}*) ${pair(claudeFor(b.folder), githubFor(b.folder), boundDockito(b.folder))} ;;\n")
            }
            append("    *) ${pair(ClaudeAccounts.default, GitHubAccounts.default, null)} ;;\n")
            append("  esac\n")
            append("}\n")
            append("case \";\$PROMPT_COMMAND;\" in\n")
            append("  *\";__pocketrocket_accounts;\"*) ;;\n")
            append("  *) PROMPT_COMMAND=\"__pocketrocket_accounts\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\" ;;\n")
            append("esac\n")
            append("__pocketrocket_accounts\n")
        }
    }

    private fun writeShellEnv() {
        val text = shellEnv()
        val file = AccountPaths.shellEnv
        if (!file.exists() || file.readText() != text) writeAtomically(file, text)
        val rc: File = AccountPaths.bashrc
        val current = if (rc.exists()) rc.readText() else ""
        if (!current.contains(BASHRC_LINE)) {
            rc.writeText(current.let { if (it.isEmpty() || it.endsWith("\n")) it else it + "\n" } + BASHRC_LINE + "\n")
        }
    }
}
