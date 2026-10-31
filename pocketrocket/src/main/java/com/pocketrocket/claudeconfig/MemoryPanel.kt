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
import java.io.File

/**
 * One `CLAUDE.md` being edited. [refused] says why it can't be, and then the file is left alone. [stamp] is what the
 * file was when opened or last saved. [conflict] is set when a save found the file changed since: nothing was written.
 */
data class MemoryState(
    val loading: Boolean = true,
    val refused: String? = null,
    val text: String = "",
    val saved: String = "",
    val stamp: MemoryStamp = MemoryStamp.Missing,
    val saving: Boolean = false,
    val conflict: MemoryStamp? = null,
    val problem: String? = null,
    val justSaved: Boolean = false,
) {
    val editable: Boolean get() = !loading && refused == null
    val dirty: Boolean get() = text != saved
    val canSave: Boolean get() = editable && dirty && !saving && conflict == null
}

/** The editor's logic over [MemoryFile], for as long as [scope] lives. Saves only when asked. Call from one thread. */
class MemoryPanel(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    /** Reads the file, dropping anything typed. Also how a conflict takes the other edit. */
    fun load() {
        _state.value = MemoryState()
        scope.launch {
            _state.value = when (val read = withContext(io) { MemoryFile.read(file) }) {
                is MemoryRead.Text -> MemoryState(loading = false, text = read.text, saved = read.text, stamp = read.stamp)
                is MemoryRead.TooLarge -> MemoryState(
                    loading = false,
                    refused = "It's ${MemoryFile.sizeLabel(read.bytes)}, over the ${MemoryFile.sizeLabel(MemoryFile.MAX_BYTES.toLong())} this editor takes. Edit it in a terminal.",
                )
                MemoryRead.NotText -> MemoryState(loading = false, refused = "It isn't plain UTF-8 text, so saving here could damage it. Edit it in a terminal.")
                is MemoryRead.Unreadable -> MemoryState(loading = false, refused = read.reason)
            }
        }
    }

    fun edit(text: String) = _state.update { it.copy(text = text, justSaved = false, problem = null) }

    fun save() {
        if (_state.value.canSave) write(_state.value.stamp)
    }

    /** After a conflict, on purpose. Replaces only what the conflict saw: if it moved again, that is a new conflict. */
    fun overwrite() {
        _state.value.conflict?.let { seen ->
            _state.update { it.copy(conflict = null) }
            write(seen)
        }
    }

    /** After a conflict: go on typing. The next save asks again. */
    fun keepEditing() = _state.update { it.copy(conflict = null) }

    private fun write(expected: MemoryStamp) {
        val text = _state.value.text
        _state.update { it.copy(saving = true, problem = null, justSaved = false) }
        scope.launch {
            val result = withContext(io) { MemoryFile.save(file, text, expected) }
            _state.update { now ->
                when (result) {
                    // What was typed during the save, if anything, stays and still counts as unsaved.
                    is MemorySave.Saved -> now.copy(saving = false, saved = text, stamp = result.stamp, justSaved = now.text == text)
                    is MemorySave.Conflict -> now.copy(saving = false, conflict = result.current)
                    is MemorySave.Failed -> now.copy(saving = false, problem = result.reason)
                }
            }
        }
    }
}
