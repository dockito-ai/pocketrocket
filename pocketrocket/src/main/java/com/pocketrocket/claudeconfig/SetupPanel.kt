package com.pocketrocket.claudeconfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Skills, settings and output styles as last asked. [problem]s are fit to show; [unsupported] means this version doesn't answer. */
data class SetupState(
    val live: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val skills: List<SkillInfo> = emptyList(),
    val skillsUnsupported: Boolean = false,
    val skillsProblem: String? = null,
    val canReloadSkills: Boolean = false,
    val settings: SettingsView? = null,
    val settingsUnsupported: Boolean = false,
    val settingsProblem: String? = null,
    val styles: List<String> = emptyList(),
    val notice: String? = null,
    val noticeIsProblem: Boolean = false,
)

/**
 * Skills, settings, output style and effort over one [McpHost], in the manner of [McpPanel]: nothing happens until a
 * method is called, every failure ends in [state], and one thing runs at a time. Only a running session reloads or
 * changes anything; a short-lived process has nothing to apply to, and would write to its own empty folder.
 */
class SetupPanel(private val host: McpHost, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SetupState(live = host.live, canReloadSkills = host.live))
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private fun work(block: suspend (ControlChannel) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null, noticeIsProblem = false) }
        scope.launch {
            try {
                host.withChannel { connection -> block(connection.channel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not e.message: nothing here prints what a process was given.
                _state.update { it.copy(settingsProblem = COULD_NOT_ASK, skillsProblem = COULD_NOT_ASK) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Asks for everything. Over a session this costs nothing; over a probe it starts one process. */
    fun refresh() = work { channel ->
        readSkills(channel)
        readSettings(channel)
        if (host.live) {
            val styles = (channel.outputStyles() as? ControlReply.Success)?.let { SetupChoices.outputStyles(it.body) }.orEmpty()
            _state.update { it.copy(styles = styles) }
        }
        _state.update { it.copy(loaded = true) }
    }

    /** Only the settings (for the effort on the chat box's model chip). */
    fun refreshSettings() = work { channel -> readSettings(channel) }

    /** `reload_skills`, then the list again: skills changed on disk don't show before it. */
    fun reloadSkills() {
        if (!host.live) return
        work { channel ->
            when (val reply = channel.reloadSkills()) {
                is ControlReply.Success -> {
                    readSkills(channel)
                    notice("Skills reloaded.")
                }
                is ControlReply.Refused -> {
                    if (reply.unsupported) _state.update { it.copy(canReloadSkills = false) }
                    problem(reply)
                }
                is ControlReply.NoAnswer -> problem(reply)
            }
        }
    }

    /** For this session only. Read back, because Claude Code answers success for any name. */
    fun pickStyle(style: String) {
        if (!canChange(SetupChoices.OUTPUT_STYLE_KEY)) return
        work { channel ->
            val reply = channel.applyOutputStyle(style)
            if (reply !is ControlReply.Success) return@work problem(reply)
            readSettings(channel)
            if (SetupChoices.currentStyle(_state.value.settings) == style) notice("$style for this session only. It isn't saved.")
            else notice("Claude Code didn't take that style.", isProblem = true)
        }
    }

    fun saveStyleForFolder(style: String) {
        if (!canChange(SetupChoices.OUTPUT_STYLE_KEY)) return
        work { channel ->
            val reply = channel.saveOutputStyleForFolder(style)
            if (reply !is ControlReply.Success) return@work problem(reply)
            readSettings(channel)
            notice("Saved in this folder's .claude/settings.local.json. New sessions here start with $style. It can be changed from here but not removed.")
        }
    }

    /** For this session only. Read back, because a level that doesn't apply is answered with success and ignored. */
    fun pickEffort(level: String) {
        if (!canChange(SetupChoices.EFFORT_KEY)) return
        work { channel ->
            val reply = channel.applyEffort(level)
            if (reply !is ControlReply.Success) return@work problem(reply)
            readSettings(channel)
            if (_state.value.settings?.appliedEffort == level) notice("$level for this session only. It isn't saved.")
            else notice("Claude Code didn't take that level.", isProblem = true)
        }
    }

    fun saveEffortForAccount(level: String) {
        if (!canChange(SetupChoices.EFFORT_KEY)) return
        work { channel ->
            val reply = channel.saveEffortForAccount(level)
            if (reply !is ControlReply.Success) return@work problem(reply)
            readSettings(channel)
            notice("Saved on this account for this model. New sessions with it start at $level; this one keeps what it has.")
        }
    }

    private fun canChange(key: String) = host.live && _state.value.settings?.isManaged(key) != true

    private suspend fun readSkills(channel: ControlChannel) {
        val reply = channel.skillsDialog()
        val skills = (reply as? ControlReply.Success)?.let { SkillsParser.parse(it.body) }
        _state.update {
            if (skills != null) it.copy(skills = skills, skillsProblem = null, skillsUnsupported = false)
            else it.copy(skillsUnsupported = (reply as? ControlReply.Refused)?.unsupported == true, skillsProblem = textOf(reply))
        }
    }

    private suspend fun readSettings(channel: ControlChannel) {
        val reply = channel.getSettings()
        val settings = (reply as? ControlReply.Success)?.let { SettingsParser.parse(it.body) }
        _state.update {
            if (settings != null) it.copy(settings = settings, settingsProblem = null, settingsUnsupported = false)
            else it.copy(settingsUnsupported = (reply as? ControlReply.Refused)?.unsupported == true, settingsProblem = textOf(reply))
        }
    }

    private fun notice(text: String, isProblem: Boolean = false) = _state.update { it.copy(notice = text, noticeIsProblem = isProblem) }

    private fun problem(reply: ControlReply) = notice(textOf(reply), isProblem = true)

    private fun textOf(reply: ControlReply): String = when (reply) {
        is ControlReply.Refused -> McpParser.scrub(reply.message) ?: COULD_NOT_ASK
        is ControlReply.NoAnswer -> reply.reason
        is ControlReply.Success -> COULD_NOT_READ
    }

    private companion object {
        const val COULD_NOT_ASK = "Couldn't ask Claude Code."
        const val COULD_NOT_READ = "Claude Code answered with something this app doesn't understand."
    }
}
