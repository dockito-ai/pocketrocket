package com.pocketrocket.profiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Something kept in an [AccountRegistry]: a stable [id] (its folder name) and a [name] shown in the app. */
interface AccountEntry {
    val id: String
    val name: String
}

/** A registry's contents: the accounts in the order they were added, and which one is the default. */
data class AccountList<T : AccountEntry>(val accounts: List<T>, val defaultId: String?) {
    val default: T? get() = accounts.firstOrNull { it.id == defaultId } ?: accounts.firstOrNull()
    fun get(id: String?): T? = accounts.firstOrNull { it.id == id }
    fun isDefault(entry: T) = entry.id == default?.id
}

/**
 * A small list of accounts saved as JSON in [file] (`{"default": id, "accounts": [...]}`), read once and
 * written on every change (temp file + rename). Holds names and ids only, never credentials. [seed] fills
 * a missing file. [onChange] runs after every write (the wiring regenerates git and shell config from it).
 */
class AccountRegistry<T : AccountEntry>(
    private val file: File,
    private val read: (JSONObject) -> T?,
    private val write: (T) -> JSONObject,
    private val seed: () -> List<T> = { emptyList() },
    private val onChange: () -> Unit = {},
) {
    private val _state by lazy { MutableStateFlow(load()) }
    val state: StateFlow<AccountList<T>> get() = _state.asStateFlow()
    val current: AccountList<T> get() = _state.value

    /** Adds [entry], or replaces the one with the same id. The first account becomes the default. */
    fun put(entry: T) = mutate { list ->
        val accounts = if (list.get(entry.id) != null) list.accounts.map { if (it.id == entry.id) entry else it } else list.accounts + entry
        AccountList(accounts, list.defaultId ?: entry.id)
    }

    fun update(id: String, change: (T) -> T) = mutate { list ->
        list.copy(accounts = list.accounts.map { if (it.id == id) change(it) else it })
    }

    fun remove(id: String) = mutate { list ->
        val accounts = list.accounts.filterNot { it.id == id }
        AccountList(accounts, list.defaultId.takeUnless { it == id } ?: accounts.firstOrNull()?.id)
    }

    fun setDefault(id: String) = mutate { list -> if (list.get(id) == null) list else list.copy(defaultId = id) }

    /** Replaces the whole list, for accounts whose own tool keeps the real list (Dockito's CLI). */
    fun replace(accounts: List<T>, defaultId: String?) = mutate { AccountList(accounts, defaultId ?: accounts.firstOrNull()?.id) }

    /** An id for a new account named [name] that no existing account uses. */
    fun freeId(name: String, reserved: Set<String> = emptySet()): String {
        val base = AccountPaths.slug(name)
        val taken = current.accounts.map { it.id }.toSet() + reserved
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }

    @Synchronized
    private fun mutate(change: (AccountList<T>) -> AccountList<T>) {
        val next = change(_state.value)
        if (next == _state.value) return
        save(next)
        _state.value = next
        onChange()
    }

    private fun load(): AccountList<T> {
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (obj == null) {
            val seeded = seed()
            return AccountList(seeded, seeded.firstOrNull()?.id).also { if (seeded.isNotEmpty()) save(it) }
        }
        val array = obj.optJSONArray("accounts") ?: JSONArray()
        val accounts = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { runCatching { read(it) }.getOrNull() } }
        val defaultId = obj.optString("default").takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        return AccountList(accounts, defaultId)
    }

    private fun save(list: AccountList<T>) {
        val json = JSONObject()
            .put("default", list.default?.id ?: JSONObject.NULL)
            .put("accounts", JSONArray(list.accounts.map(write)))
        writeAtomically(file, json.toString(2))
    }
}

/** Writes [text] to a temp file next to [file] and renames it over, so readers never see half a file. */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}
