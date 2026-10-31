package com.pocketrocket.ui.claudeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.claudeconfig.Kept
import com.pocketrocket.claudeconfig.LocalMcpServer
import com.pocketrocket.claudeconfig.LocalMcpState
import com.pocketrocket.claudeconfig.McpDraft
import com.pocketrocket.claudeconfig.McpDrafts
import com.pocketrocket.claudeconfig.McpPreset
import com.pocketrocket.claudeconfig.McpTransport
import com.pocketrocket.claudeconfig.McpValue
import com.pocketrocket.claudeconfig.StdioAdvice
import com.pocketrocket.ui.chat.ChatSheet
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.RadioRow
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

/** One variable or header. A [saved] row has no value here at all: it stays as it is, or is replaced by typing a new one. */
private class ValueRow(key: String, val saved: Boolean) {
    var key by mutableStateOf(key)
    var value by mutableStateOf("")
}

private val SecretKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
private val PlainKeyboard = KeyboardOptions(autoCorrectEnabled = false)

/** A secret: hidden as typed, kept out of the keyboard's suggestions and history. */
@Composable
internal fun SecretField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    MockTextField(
        value, onValueChange, modifier, placeholder = placeholder, mono = true,
        keyboardOptions = SecretKeyboard, visualTransformation = PasswordVisualTransformation(),
    )
}

/**
 * Adding, or editing [server]. On an edit nothing secret is ever put back on screen: arguments, the URL and values show
 * as saved, and stay so unless replaced. Those aren't asked for, and can't be shown, since the list holds no values.
 */
@Composable
internal fun McpServerForm(server: LocalMcpServer?, state: LocalMcpState, onDismiss: () -> Unit, onReview: (McpDraft) -> Unit) {
    val c = PrTheme.colors
    val editing = server != null
    var preset by remember { mutableStateOf(McpPreset.Node) }
    var name by remember { mutableStateOf(server?.name.orEmpty()) }
    var command by remember { mutableStateOf(server?.command ?: McpPreset.Node.command) }
    var argsText by remember { mutableStateOf("") }
    var keepArgs by remember { mutableStateOf(editing && server?.argCount != 0) }
    var url by remember { mutableStateOf("") }
    var keepUrl by remember { mutableStateOf(editing) }
    var problem by remember { mutableStateOf<String?>(null) }
    val transport = server?.transport ?: preset.transport
    val stdio = transport == McpTransport.Stdio
    val rows = remember {
        mutableStateListOf<ValueRow>().apply {
            // The helper is the app's to manage, by the command. It isn't a value to worry anyone with.
            server?.envKeys?.filter { it != StdioAdvice.LD_PRELOAD }?.forEach { add(ValueRow(it, saved = true)) }
            server?.headerKeys?.forEach { add(ValueRow(it, saved = true)) }
        }
    }

    ChatSheet(onDismiss) {
        Text(if (editing) "Edit ${server?.name}" else "Add a server", style = mockText(17.sp, FontWeight.SemiBold, c.text))

        if (!editing) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                McpPreset.entries.forEach { option ->
                    RadioRow(option.label, option.note, selected = preset == option) {
                        preset = option
                        command = option.command
                        argsText = option.args
                        keepArgs = false
                        keepUrl = false
                    }
                }
            }
            Field("Name", "Letters, numbers, hyphens and underscores.") {
                MockTextField(name, { name = it }, placeholder = "my-server", mono = true, keyboardOptions = PlainKeyboard)
            }
        }

        if (stdio) {
            Field("Command") {
                MockTextField(command, { command = it }, placeholder = "node", mono = true, keyboardOptions = PlainKeyboard)
            }
            if (StdioAdvice.needsExecHelper(command)) {
                Text("${StdioAdvice.commandName(command)} needs a helper to start on this phone, so ${StdioAdvice.LD_PRELOAD} is set for it.", style = mockText(12.sp, color = c.textMuted))
            }
            if (keepArgs) {
                SavedLine("${server?.argCount} saved, hidden", "Replace") { keepArgs = false }
            } else {
                Field("Arguments", "One per line. A token goes under Variables, not here: arguments are shown when you confirm.") {
                    MockTextField(argsText, { argsText = it }, placeholder = "/path/to/script.js", singleLine = false, height = 96.dp, mono = true, keyboardOptions = PlainKeyboard)
                }
            }
        } else {
            if (keepUrl) {
                SavedLine("URL on ${server?.urlHost ?: "its host"} saved, hidden", "Replace") { keepUrl = false }
            } else {
                Field("URL", "A token goes under Headers if the server allows it: the URL is shown when you confirm.") {
                    MockTextField(url, { url = it }, placeholder = "https://example.com/mcp", mono = true, keyboardOptions = PlainKeyboard)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (stdio) "Variables" else "Headers", style = mockText(13.sp, color = c.text))
            Text("Values go only into Claude Code's config, and are never shown again.", style = mockText(12.sp, color = c.textMuted))
            rows.toList().forEach { row ->
                key(row) {
                    if (row.saved) {
                        SavedLine("${row.key}: saved, hidden", "Replace", secondary = "Delete", onSecondary = { rows.remove(row) }) {
                            rows[rows.indexOf(row)] = ValueRow(row.key, saved = false)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MockTextField(row.key, { row.key = it }, placeholder = if (stdio) "API_KEY" else "Authorization", mono = true, keyboardOptions = PlainKeyboard)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecretField(row.value, { row.value = it }, "Value", Modifier.weight(1f))
                                ActionPill("Delete", accent = false, enabled = true) { rows.remove(row) }
                            }
                        }
                    }
                }
            }
            ActionPill(if (stdio) "Add a variable" else "Add a header", accent = false, enabled = true) { rows.add(ValueRow("", saved = false)) }
        }

        problem?.let { Text(it, style = mockText(12.sp, color = c.redSoft)) }
        ConfigStatus(state.busy, state.notice)
        AccentButton(
            "Review",
            onClick = {
                val values = rows.filter { it.saved || it.key.isNotBlank() || it.value.isNotEmpty() }
                    .map { McpValue(it.key.trim(), if (it.saved) Kept.Saved else Kept.New(it.value)) }
                val draft = McpDraft(
                    name = name.trim(),
                    transport = transport,
                    command = command.trim(),
                    args = if (keepArgs) Kept.Saved else Kept.New(argsText.lines().map { it.trim() }.filter { it.isNotEmpty() }),
                    url = if (keepUrl) Kept.Saved else Kept.New(url.trim()),
                    env = if (stdio) values else emptyList(),
                    headers = if (stdio) emptyList() else values,
                )
                problem = McpDrafts.problem(draft)
                if (problem == null) onReview(draft)
            },
            enabled = state.busy == null,
            height = 44.dp,
            radius = 12.dp,
            fontSize = 14.sp,
        )
        Spacer(Modifier.imePadding())
    }
}

@Composable
private fun Field(label: String, hint: String? = null, content: @Composable () -> Unit) {
    val c = PrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = mockText(13.sp, color = c.text))
        content()
        hint?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
    }
}

/** Something already saved, not shown. */
@Composable
private fun SavedLine(text: String, action: String, secondary: String? = null, onSecondary: () -> Unit = {}, onAction: () -> Unit) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = mockText(12.sp, color = c.textMuted), modifier = Modifier.weight(1f))
        ActionPill(action, accent = false, enabled = true, onClick = onAction)
        secondary?.let { ActionPill(it, accent = false, enabled = true, onClick = onSecondary) }
    }
}
