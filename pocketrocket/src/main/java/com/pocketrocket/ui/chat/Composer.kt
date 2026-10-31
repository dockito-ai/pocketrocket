package com.pocketrocket.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.pocketrocket.sessions.Draft
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.PendingAttachments
import androidx.compose.runtime.collectAsState
import com.pocketrocket.sessions.SlashCommand
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The chat row (Session.dc.html, Attachments.html option A): `+` for Photos, Files and Commands, the box (grows to a
 * few lines), and one round orange button that is the dictation mic while the box is empty and Send once it has text
 * or attachments. While Claude works and the box is empty it is Stop. Dictated words land in the box for editing;
 * nothing is sent automatically. Picked files are copied in at once and wait in a tray above the box.
 * Rocket's tab uses the same row with no `+` ([attachable] false) and its own talk button in place of dictation ([voice]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    sessionId: String,
    working: Boolean,
    onSend: (String, List<Attachment>) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    commands: List<SlashCommand> = emptyList(),
    draft: Draft? = null,
    insert: Draft? = null,
    active: Boolean = true,
    placeholder: String = "Message Claude",
    attachable: Boolean = true,
    voice: ComposerVoice? = null,
) {
    var field by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    /** Replaces the text, cursor at the end. */
    fun setText(value: String) { field = TextFieldValue(value, TextRange(value.length)) }
    var listening by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val attachments = remember(sessionId) { mutableStateListOf<Attachment>() }
    var attaching by remember { mutableStateOf(false) }
    val stop = working && text.isBlank() && attachments.isEmpty()
    var confirmStop by remember { mutableStateOf(false) }

    // Files or text shared to this session from another app (ShareActivity).
    val waiting by PendingAttachments.waiting.collectAsState()
    LaunchedEffect(waiting[sessionId] != null) {
        val handed = PendingAttachments.take(sessionId) ?: return@LaunchedEffect
        attachments += handed.attachments
        handed.text?.let { shared -> setText(if (field.text.isBlank()) shared else field.text + "\n" + shared) }
    }

    // Text handed back replaces what is in the box, once each: [draft] from the session (a message to edit, with its
    // attachments, ready to type), [insert] from the screen (a command picked in the controls).
    var appliedDraft by rememberSaveable(sessionId) { mutableIntStateOf(draft?.key ?: 0) }
    var appliedInsert by rememberSaveable(sessionId) { mutableIntStateOf(insert?.key ?: 0) }
    // What the box held before the last draft, for the Edit's Undo.
    var before by remember(sessionId) { mutableStateOf<Pair<String, List<Attachment>>?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(draft) {
        if (draft == null || draft.key == appliedDraft) return@LaunchedEffect
        appliedDraft = draft.key
        if (draft.undo) {
            before?.let { (was, files) -> setText(was); attachments.clear(); attachments += files }
            before = null
        } else {
            before = field.text to attachments.toList()
            setText(draft.text)
            attachments.clear()
            attachments += draft.attachments
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // Swiping to another session with the keyboard up moves the typing there, instead of leaving it in the last one.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(active) {
        if (active && imeVisible) focus.requestFocus()
    }
    LaunchedEffect(insert) {
        if (insert != null && insert.key != appliedInsert) { setText(insert.text); appliedInsert = insert.key }
    }

    // A hint (mic denied, nothing heard) shows for a few seconds.
    LaunchedEffect(hint) {
        if (hint != null) { delay(4000); hint = null }
    }

    val c = PrTheme.colors
    // The round button right of the box: the mic while it is empty (or listening), Send once there is text or an
    // attachment, Stop while Claude works and the box is empty.
    val talking = voice?.busy == true
    val action = when {
        listening || talking -> Action.Mic
        stop -> Action.Stop
        text.isNotBlank() || attachments.isNotEmpty() -> Action.Send
        else -> Action.Mic
    }
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(hint != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                hint.orEmpty(),
                style = PrType.caption,
                color = c.textMuted,
                modifier = Modifier.padding(start = 52.dp, bottom = 6.dp),
            )
        }
        SlashPicker(field.text, commands, onPick = { setText("/$it ") })
        if (attachments.isNotEmpty()) {
            AttachmentTray(attachments, onRemove = { attachments.remove(it) })
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachable) PlusButton(onClick = { attaching = true })
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(c.background)
                    .border(1.dp, c.border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    val spoken = voice?.hint
                    Text(
                        spoken ?: if (listening) "Listening…" else placeholder,
                        style = PrType.body,
                        color = if (listening || spoken != null) c.accent else c.textMuted,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    textStyle = PrType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val round = Modifier.size(44.dp)
            when (action) {
                Action.Mic -> if (voice != null) voice.button(round) else MicButton(
                    currentText = { text },
                    onTextChange = { setText(it) },
                    onListeningChange = { listening = it },
                    onHint = { hint = it },
                    modifier = round,
                    cancelKey = sent,
                )
                Action.Send -> Box(
                    round.clip(CircleShape).background(c.accent)
                        .clickable(role = Role.Button, onClickLabel = "Send") {
                            onSend(text, attachments.toList()); setText(""); attachments.clear(); sent++
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ChatIcons.Send, contentDescription = "Send", tint = c.onAccent, modifier = Modifier.size(20.dp))
                }
                Action.Stop -> Box(
                    round.clip(CircleShape).border(1.dp, c.redBorder, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Stop") { confirmStop = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(14.dp).background(c.redSoft, RoundedCornerShape(3.dp)))
                }
            }
        }
    }

    // Stop is easy to hit by mistake and throws away the turn's remaining work, so it asks first. The question goes
    // away by itself if Claude finishes meanwhile.
    LaunchedEffect(working) { if (!working) confirmStop = false }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop Claude?") },
            text = { Text("Claude stops where it is. What it has done so far stays.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
        )
    }

    if (attachable) AttachmentPicker(
        sessionId = sessionId,
        open = attaching,
        onDismiss = { attaching = false },
        onAdded = { attachments += it },
        onHint = { hint = it },
        onCommands = { if (!text.startsWith("/")) setText("/" + text); focus.requestFocus() },
    )
}

private enum class Action { Mic, Send, Stop }

/**
 * A talk button in place of the dictation mic, for a chat that listens and answers aloud (Rocket's tab). [busy]: listening or
 * speaking, so the button stays even with text in the box. [hint]: shown in the empty box while it's busy, like "Listening…".
 */
class ComposerVoice(val busy: Boolean, val hint: String?, val button: @Composable (Modifier) -> Unit)
