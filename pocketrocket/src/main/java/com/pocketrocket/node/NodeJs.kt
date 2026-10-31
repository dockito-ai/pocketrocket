package com.pocketrocket.node

import android.content.Context
import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.termux.TermuxCommand
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Node.js and npm as Tools shows them: their versions and the package's size on the phone. */
data class NodeState(
    val checked: Boolean = false,
    val node: String? = null,
    val npm: String? = null,
    val sizeMb: Int? = null,
) {
    val installed: Boolean get() = node != null
}

/**
 * Node.js and npm, a built-in tool (decision 64): first-run setup installs Termux's nodejs-lts with the base set;
 * the app never installs or updates it. [refresh] runs `node --version` and `npm --version` the way the app runs
 * every tool (through Termux's environment), once per app run unless asked again, off the main thread.
 */
object NodeJs {
    private const val PACKAGE = "nodejs-lts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
    private val status = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/status")

    private val _state = MutableStateFlow(NodeState())
    val state: StateFlow<NodeState> = _state.asStateFlow()

    @Volatile private var reading = false

    fun refresh(context: Context, force: Boolean = false) {
        if (reading || (_state.value.checked && !force)) return
        reading = true
        val app = context.applicationContext
        scope.launch {
            try {
                val node = version(app, "node")
                val npm = if (node != null) version(app, "npm") else null
                _state.update { NodeState(checked = true, node = node, npm = npm, sizeMb = installedKb(status.takeIf { it.isFile }?.readText(), PACKAGE)?.let { (it + 512) / 1024 }) }
            } finally {
                reading = false
            }
        }
    }

    /** `<tool> --version`'s first line, or null when it isn't there or fails. Blocking. */
    private fun version(context: Context, tool: String): String? {
        val file = File(bin, tool)
        if (!file.exists()) return null
        val lines = mutableListOf<String>()
        val exit = runCatching {
            TermuxCommand.run(context, file.path, arrayOf("--version"), AccountPaths.home, { synchronized(lines) { lines += it } })
        }.getOrDefault(-1)
        return if (exit == 0) synchronized(lines) { lines.firstOrNull { it.isNotBlank() }?.trim() } else null
    }

    /** A package's Installed-Size (KB) from dpkg's status file, or null when it isn't installed. */
    internal fun installedKb(statusText: String?, pkg: String): Int? =
        statusText?.split("\n\n")
            ?.firstOrNull { block -> block.lineSequence().any { it == "Package: $pkg" } && block.contains("Status: install ok installed") }
            ?.lineSequence()?.firstOrNull { it.startsWith("Installed-Size:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
}
