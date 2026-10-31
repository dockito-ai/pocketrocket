package com.pocketrocket.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrocket.sessions.Draft
import com.pocketrocket.sessions.SlashCommand
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.PendingAttachments
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.delay

/**
 * The message box (decision 81, Dictation.html; Attachments.html option A): one rounded box with the text on top
 * (grows to a few lines) and a row under it: `+` for Photos, Files and Commands, the model chip ([chip]), then the
 * dictation mic and Send, or Stop while Claude works and the box is empty (typed text still queues). The mic is always
 * there; it raises [dictator]'s listening panel, and kept words go in at the cursor, briefly highlighted. Nothing is
 * sent automatically. Picked files are copied in at once and wait in a tray above the box.
 * Rocket's tab uses the same box with no `+` ([attachable] false), no chip, and its own talk button in the mic's
 * place ([voice]).
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
    dictator: Dictator? = null,
    chip: ComposerChip? = null,
) {
    var field by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    /** Replaces the text, cursor at the end. */
    fun setText(value: String) { field = TextFieldValue(value, TextRange(value.length)) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val attachments = remember(sessionId) { mutableStateListOf<Attachment>() }
    var attaching by remember { mutableStateOf(false) }
    val stop = working && text.isBlank() && attachments.isEmpty()
    var confirmStop by remember { mutableStateOf(false) }

    // Dictated words go in at the cursor, highlighted for a moment.
    var highlight by remember { mutableStateOf<Inserted?>(null) }
    val glow = remember { Animatable(0f) }
    if (dictator != null) {
        dictator.onHint = { hint = it }
        dictator.onKeep = { words ->
            val now = field
            val inserted = insertAtCursor(now.text, now.selection.start, now.selection.end, words)
            field = TextFieldValue(inserted.text, TextRange(inserted.end))
            highlight = inserted
        }
    }
    LaunchedEffect(highlight) {
        if (highlight == null) return@LaunchedEffect
        glow.snapTo(1f)
        delay(300)
        glow.animateTo(0f, tween(1400))
        highlight = null
    }

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
    val sendable = text.isNotBlank() || attachments.isNotEmpty()
    val shown = highlight?.takeIf { it.text == text && glow.value > 0f }
    val highlighting = if (shown == null) VisualTransformation.None else VisualTransformation { input ->
        TransformedText(
            buildAnnotatedString {
                append(input)
                addStyle(SpanStyle(background = c.accent.copy(alpha = 0.18f * glow.value)), shown.start, shown.end)
            },
            OffsetMapping.Identity,
        )
    }
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(hint != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                hint.orEmpty(),
                style = PrType.caption,
                color = c.textMuted,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
            )
        }
        SlashPicker(field.text, commands, onPick = { setText("/$it ") })
        if (attachments.isNotEmpty()) {
            AttachmentTray(attachments, onRemove = { attachments.remove(it) })
            Spacer(Modifier.height(8.dp))
        }
        val box = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().clip(box).background(c.background).border(1.dp, c.border, box)
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(end = 4.dp)) {
                if (text.isEmpty()) {
                    val spoken = voice?.hint
                    Text(
                        spoken ?: placeholder,
                        style = PrType.body,
                        color = if (spoken != null) c.accent else c.textMuted,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        if (it.text != field.text) highlight = null
                        field = it
                    },
                    textStyle = PrType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 6,
                    visualTransformation = highlighting,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (attachable) PlusButton(onClick = { attaching = true }, size = 36.dp)
                if (chip != null) ModelChip(chip, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                val round = Modifier.size(36.dp)
                when {
                    voice != null -> voice.button(round)
                    dictator != null -> MicButton(dictator, onHint = { hint = it }, modifier = round)
                }
                when {
                    stop -> Box(
                        round.clip(CircleShape).background(c.raised)
                            .clickable(role = Role.Button, onClickLabel = "Stop") { confirmStop = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).background(c.redSoft, RoundedCornerShape(2.dp)))
                    }
                    else -> Box(
                        round.clip(CircleShape).background(if (sendable) c.accent else c.raised)
                            .clickable(enabled = sendable, role = Role.Button, onClickLabel = "Send") {
                                onSend(text, attachments.toList()); setText(""); attachments.clear()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            ChatIcons.Send, contentDescription = "Send",
                            tint = if (sendable) c.onAccent else c.textFaint, modifier = Modifier.size(18.dp),
                        )
                    }
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

/** The model chip's words ([modelChipText]) and what a tap does (opens the controls at the model). */
class ComposerChip(val text: String, val onClick: () -> Unit)

/** "Opus 5.5 · medium ▾": the effort muted, the caret faint, like the mockup. */
@Composable
private fun ModelChip(chip: ComposerChip, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    val name = chip.text.substringBefore(" · ")
    val effort = chip.text.substringAfter(" · ", "")
    Row(
        modifier.widthIn(min = 0.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Change model and effort", onClick = chip.onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(name)
                if (effort.isNotEmpty()) {
                    pushStyle(SpanStyle(color = c.textMuted)); append(" · $effort"); pop()
                }
                pushStyle(SpanStyle(color = c.textFaint)); append("  ▾"); pop()
            },
            style = PrType.small,
            color = c.textSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A talk button in place of the dictation mic, for a chat that listens and answers aloud (Rocket's tab). [busy]:
 * listening or speaking. [hint]: shown in the empty box while it's busy, like "Listening…".
 */
class ComposerVoice(val busy: Boolean, val hint: String?, val button: @Composable (Modifier) -> Unit)
