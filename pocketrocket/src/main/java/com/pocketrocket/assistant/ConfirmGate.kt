package com.pocketrocket.assistant

import com.pocketrocket.sessions.PendingPermission
import com.pocketrocket.sessions.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What was heard in answer to "allow or deny?". */
enum class GateAnswer { Yes, No, Unclear }

/** How an approval ended. Only the first three let anything happen. */
enum class Answered(val allowed: Boolean = false, val denied: Boolean = false) {
    AllowByVoice(allowed = true),
    AllowByTap(allowed = true),
    AllowByFingerprint(allowed = true),
    DenyByVoice(denied = true),
    DenyByTap(denied = true),

    /** Ignored, dismissed, timed out or never clearly answered. A no: nothing runs. A session's request stays open. */
    NoAnswer,

    /** The request was answered elsewhere, or the session moved on. */
    Gone,

    /** It needs a fingerprint or screen lock and the phone has neither, so it was never shown. Nothing runs. */
    CantApprove,
}

/**
 * What the approval screen shows and says, written by the app from the real request or action. Never built from anything
 * Claude wrote about it: a request's own reason or summary isn't used. [speech] never carries a command unless it's plain and has no secret.
 */
data class Approval(
    val headline: String,
    /** Exactly what will happen: the whole command, or the branch, or the channel and message. */
    val detail: String,
    val mono: Boolean,
    val where: String?,
    val protection: Protection,
    val speech: String,
    /** For the activity log and the chat, with no command in it. */
    val record: String,
    val sessionId: String? = null,
    val requestId: String? = null,
)

/**
 * The only code that can say yes to a permission by voice. Claude never answers one: this reads the real request, says it,
 * and judges what it hears. Whole answers only, so anything else, including "yes but wait", is [GateAnswer.Unclear], and unclear is never a yes.
 */
object ConfirmGate {
    const val UNCLEAR = "I didn't get a clear yes, so I haven't allowed it. It's still waiting."
    const val ASK = "Allow or deny?"

    private val YES = setOf("yes", "yep", "yeah", "allow", "allow it", "approve", "approve it", "confirm", "go ahead")
    private val NO = setOf("no", "nope", "nah", "no thanks", "deny", "deny it", "reject", "reject it", "don't", "do not", "don't allow it", "do not allow it", "don't allow", "do not allow", "stop", "cancel")

    /** Whole final results only: pass null when listening ended with no final words. */
    fun hear(final: String?): GateAnswer {
        val words = final?.lowercase()?.replace('’', '\'')?.trim()?.trim('.', ',', '!', '?', ' ')?.replace(Regex("""\s+"""), " ") ?: return GateAnswer.Unclear
        return when (words) {
            in YES -> GateAnswer.Yes
            in NO -> GateAnswer.No
            else -> GateAnswer.Unclear
        }
    }

    /** The last check before anything is allowed, whatever screen or bug asked: a fingerprint request takes a fingerprint, and a locked phone only a fingerprint. */
    fun accepts(protection: Protection, answered: Answered, locked: Boolean): Boolean = when (answered) {
        Answered.AllowByFingerprint -> true
        Answered.AllowByVoice -> protection == Protection.SpokenYes && !locked
        Answered.AllowByTap -> protection != Protection.Fingerprint && !locked
        else -> true
    }

    fun ofPermission(state: SessionState, asked: PendingPermission): Approval {
        val protection = SensitiveActions.protectionOf(asked)
        val name = Spoken.name(state.title)
        val (verb, detail, mono) = when (asked.toolName) {
            "Bash" -> Triple("wants to run", asked.fullCommand(), true)
            "Edit", "MultiEdit", "NotebookEdit" -> Triple("wants to edit", asked.fullPath(), true)
            "Write" -> Triple("wants to write", asked.fullPath(), true)
            "Read" -> Triple("wants to read", asked.fullPath(), true)
            else -> Triple("wants to use ${asked.toolName}", asked.input.takeIf { it.length() > 0 }?.toString(2) ?: asked.summary, true)
        }
        val closely = if (asked.toolName == "Bash") Spoken.closely(detail) else Spoken.closely(detail.substringAfterLast('/'))
        val speech = when (protection) {
            Protection.SpokenYes -> "$name $verb: $closely. $ASK"
            Protection.Screen -> "$name has a request. Check your screen."
            Protection.Fingerprint -> "$name has a request that needs your fingerprint."
        }
        return Approval(
            headline = "${state.title} $verb",
            detail = detail,
            mono = mono,
            where = "In ${state.folder.substringAfter("/home/", state.folder)}",
            protection = protection,
            speech = speech,
            record = "${state.title}'s request",
            sessionId = state.id,
            requestId = asked.requestId,
        )
    }

    fun ofAction(action: RocketAction): Approval {
        val protection = SensitiveActions.protectionOf(action)
        fun approval(headline: String, detail: String, mono: Boolean, where: String?, said: String, record: String) = Approval(
            headline, detail, mono, where, protection,
            if (protection == Protection.Fingerprint) "$said That needs your fingerprint." else "$said $ASK",
            record,
        )
        return when (action) {
            is RocketAction.Push -> approval("Push ${action.repo}", "git push ${action.remote} ${action.branch}", true, "Using this workspace's GitHub account", "Push ${Spoken.name(action.branch)} to ${Spoken.name(action.remote)} in ${Spoken.name(action.repo)}.", "a push in ${action.repo}")
            is RocketAction.Merge -> approval("Merge in ${action.repo}", action.what, true, "Using this workspace's GitHub account", "Merge ${Spoken.name(action.what)} in ${Spoken.name(action.repo)}.", "a merge in ${action.repo}")
            is RocketAction.OpenPr -> approval("Open a pull request in ${action.repo}", action.title, false, "From ${action.branch}", "Open a pull request in ${Spoken.name(action.repo)} from ${Spoken.name(action.branch)}.", "a pull request in ${action.repo}")
            is RocketAction.SetMode -> approval("Switch ${action.session} to", action.mode, false, "It will ask you less from then on", "Switch ${Spoken.name(action.session)} to ${action.mode}.", "${action.mode} for ${action.session}")
            is RocketAction.DeleteBranch -> approval("Delete a branch ${if (action.onRemote) "on the remote" else "here"}", action.branch, true, "In ${action.repo}", "Delete a branch in ${Spoken.name(action.repo)}.", "deleting a branch in ${action.repo}")
            is RocketAction.ForcePush -> approval("Force-push, replacing what's there", "git push --force ${action.remote} ${action.branch}", true, "In ${action.repo}", "Force-push in ${Spoken.name(action.repo)}.", "a force-push in ${action.repo}")
            is RocketAction.DiscardChanges -> approval("Discard changes for good", action.what, true, "In ${action.repo}", "Discard changes in ${Spoken.name(action.repo)}.", "discarding changes in ${action.repo}")
            is RocketAction.SendMessage -> approval("Send to ${action.to}", action.message, false, null, "Send a message.", "a message to ${action.to}")
            is RocketAction.StartAgent -> approval("Start an agent in ${action.project}", action.mode + (action.model?.let { " on $it" } ?: ""), false, "It will ask you less than Manual", "Start an agent in ${Spoken.name(action.project)}, in ${action.mode}.", "an agent in ${action.project} in ${action.mode}")
            is RocketAction.MessageSession -> approval("Send to ${action.session}", action.message, false, "It's in Auto: this would run with no later check", "Send to ${Spoken.name(action.session)}: " + (Spoken.closely(action.message) ?: "check your screen") + ".", "a message to ${action.session}")
            is RocketAction.WhileLocked -> approval(action.what, "The phone is locked", false, null, action.what + ".", action.what.replaceFirstChar { it.lowercase() })
            is RocketAction.SaveHabit -> approval("Remember this from now on?", action.text, false, "You can delete it in Settings", "Remember this from now on: " + (Spoken.closely(action.text) ?: action.text) + ".", "saving a habit")
            is RocketAction.SetDefaultAccount -> approval("Make this the default ${action.service} account", action.account, false, action.instead?.let { "Instead of $it" }, "Make ${Spoken.name(action.account)} the default ${action.service} account.", "the default ${action.service} account")
            is RocketAction.InstallTool -> approval("Install a tool", action.tool, false, "Downloads it to this phone", "Install ${action.tool}.", "installing ${action.tool}")
            is RocketAction.InstallUpdate -> approval("Update", action.versions, false, action.warning, "Update ${action.names}." + (action.warning?.let { " $it." } ?: ""), "updating ${action.names}")
            is RocketAction.Unknown -> approval("Rocket doesn't recognise this", action.description, false, null, "Something I don't recognise.", "something it didn't recognise")
        }
    }
}

/** What a session's chat and the activity log say about how an answer was given. */
object ApprovalWords {
    fun how(answered: Answered): String? = when (answered) {
        Answered.AllowByVoice, Answered.DenyByVoice -> "by voice"
        Answered.AllowByFingerprint -> "with your fingerprint"
        Answered.AllowByTap, Answered.DenyByTap -> "through Rocket"
        Answered.NoAnswer, Answered.Gone, Answered.CantApprove -> null
    }

    /** "Allowed by voice · 09:14": a line in the session's chat, so it shows Rocket did it. */
    fun chatLine(answered: Answered, time: String): String? = how(answered)?.let { "${if (answered.allowed) "Allowed" else "Denied"} $it · $time" }

    /** A plain sentence for the log, with no command in it. */
    fun logLine(answered: Answered, record: String): String = when {
        answered.allowed -> "Allowed $record"
        answered.denied -> "Denied $record"
        answered == Answered.CantApprove -> "Couldn't ask about $record, the phone has no screen lock"
        else -> "Didn't allow $record, no clear answer"
    }

    fun logHow(answered: Answered): String = how(answered)?.replaceFirstChar { it.uppercase() } ?: "Nothing was done"
}

enum class GateVoiceState { Idle, Saying, Hearing }

/**
 * Says the approval and hears one answer, on its own speech parts, outside Claude and outside [VoiceLoop]. Only a recogniser's
 * final words count: a partial "yes" may still turn into "yes, but wait". Main thread only. What's heard isn't logged.
 */
class GateVoice(
    private val recogniser: Recogniser,
    private val speaker: Speaker,
    /** Read fresh for each step: quiet, can it be spoken, may the mic open. */
    private val conditions: () -> Conditions,
    private val onAnswer: (GateAnswer) -> Unit,
) {
    data class Conditions(val quiet: Boolean, val canSpeak: Boolean, val micAllowed: Boolean)

    private val _state = MutableStateFlow(GateVoiceState.Idle)
    val state: StateFlow<GateVoiceState> = _state.asStateFlow()

    /** Says [speech] if it may be heard, then listens if the mic may open. Otherwise the screen's buttons answer. */
    fun ask(speech: String) {
        cancel()
        val now = conditions()
        if (now.quiet) return
        if (now.canSpeak) say(speech) { listen() } else listen()
    }

    /** Says it and doesn't listen. */
    fun tell(speech: String) {
        cancel()
        val now = conditions()
        if (!now.quiet && now.canSpeak) say(speech) { _state.value = GateVoiceState.Idle }
    }

    /** Listens again, without repeating the question. */
    fun listen() {
        recogniser.cancel()
        val now = conditions()
        if (now.quiet || !now.micAllowed || !recogniser.isAvailable) {
            _state.value = GateVoiceState.Idle
            return
        }
        _state.value = GateVoiceState.Hearing
        recogniser.start(heard)
    }

    fun cancel() {
        speaker.stop()
        recogniser.cancel()
        _state.value = GateVoiceState.Idle
    }

    fun release() {
        cancel()
        speaker.release()
    }

    private fun say(speech: String, then: () -> Unit) {
        _state.value = GateVoiceState.Saying
        speaker.speak(speech, object : Speaker.Listener {
            override fun onStarted() {}
            override fun onDone() = then()
            // It's on screen either way.
            override fun onFailed(reason: String) = then()
        })
    }

    private fun finish(words: String?) {
        if (_state.value != GateVoiceState.Hearing) return
        _state.value = GateVoiceState.Idle
        recogniser.cancel()
        onAnswer(ConfirmGate.hear(words))
    }

    private val heard = object : Recogniser.Listener {
        override fun onHeard(text: String, final: Boolean) { if (final) finish(text) }
        override fun onLevel(level: Float) {}
        override fun onEnd(error: String?) = finish(null)
    }
}
