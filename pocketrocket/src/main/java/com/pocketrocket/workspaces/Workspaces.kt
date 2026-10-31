package com.pocketrocket.workspaces

import com.pocketrocket.profiles.AccountPaths
import com.pocketrocket.profiles.AccountWiring
import com.pocketrocket.profiles.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A folder Shankar marked as a workspace (BRD R6, decision 19). [path] is absolute, without a trailing slash. */
data class Workspace(val path: String, val name: String) {
    val dir: File get() = File(path)
}

/**
 * The workspace list, in one small file: ~/.pocketrocket/workspaces.json, `{"workspaces":[{"path","name"}]}`.
 * A workspace is any folder; the structure under ~/projects is Shankar's. Accounts live in
 * [AccountWiring]'s bindings, keyed by the same path, so removing a workspace from the list unbinds it
 * but never touches its files.
 */
object Workspaces {
    val projectsRoot: File get() = File(AccountPaths.home, "projects")
    private val file: File get() = File(AccountPaths.home, ".pocketrocket/workspaces.json")

    private val _state by lazy { MutableStateFlow(load()) }
    val state: StateFlow<List<Workspace>> get() = _state.asStateFlow()
    val all: List<Workspace> get() = _state.value

    fun get(path: String): Workspace? = AccountPaths.normalize(path).let { p -> all.firstOrNull { it.path == p } }

    /** The workspace [folder] is in (itself or the nearest one above it), or null. */
    fun containing(folder: String): Workspace? = WorkspaceCli.containing(all, AccountPaths.normalize(folder))

    /** Marks [path] as a workspace, creating the folder, and binds it to the given accounts (null: default). */
    fun add(path: String, name: String, claude: String?, github: String?, dockito: String? = null): Workspace {
        val ws = Workspace(AccountPaths.normalize(path), name.trim().ifEmpty { File(path).name })
        ws.dir.mkdirs()
        mutate { list -> list.filterNot { it.path == ws.path } + ws }
        AccountWiring.bind(ws.path, claude, github, dockito)
        return ws
    }

    fun rename(path: String, name: String) {
        if (name.isBlank()) return
        mutate { list -> list.map { if (it.path == path) it.copy(name = name.trim()) else it } }
    }

    /**
     * Moves a workspace's folder on disk to [newPath] (which must not exist yet) and carries its accounts
     * along. Workspaces inside it move too. Returns an error message, or null when it worked.
     */
    fun move(path: String, newPath: String): String? {
        val from = File(path)
        val to = File(AccountPaths.normalize(newPath))
        if (to.path == from.path) return null
        if (to.exists()) return "${display(to.path)} already exists."
        if (to.path.startsWith(from.path + "/")) return "A workspace can't move inside itself."
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) return "Couldn't move the folder."
        fun moved(p: String) = if (p == from.path || p.startsWith(from.path + "/")) to.path + p.removePrefix(from.path) else p
        AccountWiring.bindings.value.filter { moved(it.folder) != it.folder }.forEach {
            AccountWiring.unbind(it.folder)
            AccountWiring.bind(moved(it.folder), it.claude, it.github, it.dockito)
        }
        mutate { list -> list.map { it.copy(path = moved(it.path)) } }
        return null
    }

    /** Takes [path] off the list and drops its account binding. Its files stay. */
    fun remove(path: String) {
        mutate { list -> list.filterNot { it.path == path } }
        AccountWiring.unbind(path)
    }

    /** "work/pocketrocket" for a folder under ~/projects, "~/x" under home, else the full path. */
    fun display(path: String): String {
        val root = projectsRoot.path
        return when {
            path == root -> "~/projects"
            path.startsWith("$root/") -> path.removePrefix("$root/")
            path == AccountPaths.home.path -> "~"
            path.startsWith(AccountPaths.home.path + "/") -> "~/" + path.removePrefix(AccountPaths.home.path + "/")
            else -> path
        }
    }

    /** "~/projects/work/pocketrocket": the full path written from home. */
    fun tilde(path: String): String =
        if (path.startsWith(AccountPaths.home.path)) "~" + path.removePrefix(AccountPaths.home.path) else path

    @Synchronized
    private fun mutate(change: (List<Workspace>) -> List<Workspace>) {
        val next = change(_state.value).sortedBy { it.path }
        if (next == _state.value) return
        val json = JSONObject().put("workspaces", JSONArray(next.map { JSONObject().put("path", it.path).put("name", it.name) }))
        file.parentFile?.mkdirs()
        writeAtomically(file, json.toString(2))
        _state.value = next
    }

    private fun load(): List<Workspace> {
        val array = runCatching { JSONObject(file.readText()).getJSONArray("workspaces") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path").ifBlank { return@mapNotNull null }
            Workspace(path, o.optString("name").ifBlank { File(path).name })
        }.sortedBy { it.path }
    }
}
