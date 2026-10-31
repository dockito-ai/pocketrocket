package com.pocketrocket.profiles

import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
import java.io.File

/**
 * Where accounts live. Everything is under ~/.pocketrocket/accounts/ so a terminal, Claude and git see
 * the same files the app does:
 *
 *     claude.json, github.json   the account lists (names and defaults, no secrets)
 *     bindings.json              which folder uses which accounts
 *     env.sh                     the same bindings for interactive shells (generated)
 *     claude/<id>/               a Claude account's CLAUDE_CONFIG_DIR (the first account is ~/.claude)
 *     github/<id>/gh/            a GitHub account's GH_CONFIG_DIR
 *     github/<id>/id_ed25519     its SSH key
 *     github/<id>/gitconfig      its commit identity and core.sshCommand
 *     github/known_hosts         GitHub's published host key
 *     dockito.json               the Dockito account list, as `dockito accounts` last gave it
 *     dockito/                   the DOCKITO_CONFIG_DIR every Dockito login shares (the CLI writes it)
 */
object AccountPaths {
    val home = File(TERMUX_HOME_DIR_PATH)
    val root = File(home, ".pocketrocket/accounts")

    val claudeList = File(root, "claude.json")
    val githubList = File(root, "github.json")
    val bindings = File(root, "bindings.json")
    val shellEnv = File(root, "env.sh")

    /** Claude Code's own default config dir, used by the first Claude account. */
    val defaultClaudeDir = File(home, ".claude")
    fun claudeDir(id: String) = File(root, "claude/$id")

    fun githubDir(id: String) = File(root, "github/$id")
    fun ghConfigDir(id: String) = File(githubDir(id), "gh")
    fun sshKey(id: String) = File(githubDir(id), "id_ed25519")
    fun gitConfig(id: String) = File(githubDir(id), "gitconfig")
    val githubKnownHosts = File(root, "github/known_hosts")

    val dockitoList = File(root, "dockito.json")
    /** The one DOCKITO_CONFIG_DIR: the CLI keeps all its logins here, each picked by DOCKITO_ACCOUNT. */
    val dockitoConfig = File(root, "dockito")

    val userGitConfig = File(home, ".gitconfig")
    val bashrc = File(home, ".bashrc")

    /** A folder name made from [name]: lowercase letters, digits and dashes, never empty. */
    fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "account" }

    /** [path] made absolute with `~` expanded and no trailing slash, the form bindings are stored in. */
    fun normalize(path: String): String {
        val expanded = path.trim().replaceFirst(Regex("^~(?=/|$)"), home.path)
        return File(expanded).absoluteFile.normalize().path.trimEnd('/').ifEmpty { "/" }
    }
}
