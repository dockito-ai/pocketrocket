package com.pocketrocket.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.pocketrocket.MainActivity
import kotlinx.coroutines.NonCancellable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.attachments.Attachments
import com.pocketrocket.sessions.attachments.PendingAttachments
import com.pocketrocket.ui.chat.ChatSheet
import com.pocketrocket.ui.chat.SessionActivity
import com.pocketrocket.ui.sessions.displayPath
import com.pocketrocket.ui.sessions.statusLabel
import com.pocketrocket.ui.theme.PocketRocketTheme
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.pocketrocket.ui.theme.status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share to PocketRocket (R16, Attachments.html): a sheet over the app that shared, asking which session gets the
 * files. The files are copied into that session's attachments and its chat opens with them in the tray; shared text
 * (a link from Chrome) lands in its chat box instead. Nothing is sent until the user taps Send.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        val uris = sharedUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { uris.isEmpty() && it.isNotBlank() }
        if (uris.isEmpty() && text == null) { finish(); return }

        setContent {
            PocketRocketTheme {
                val sessions by SessionManager.sessions.collectAsState()
                val ordered = remember(sessions) {
                    sessions.filterNot { it.state.value.demo }.sortedWith(
                        compareByDescending<Session> { it.state.value.status == SessionStatus.WaitingForPermission }.thenByDescending { it.lastActiveAt },
                    )
                }
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                ShareSheet(
                    what = if (text != null) "Send text to…" else "Send ${uris.size} ${if (uris.size == 1) "file" else "files"} to…",
                    detail = text ?: uris.joinToString { it.lastPathSegment?.substringAfterLast('/') ?: "file" },
                    sessions = ordered,
                    busy = busy,
                    onDismiss = ::finish,
                    onPick = { session ->
                        busy = true
                        scope.launch {
                            // Finish the copy and the hand-off even if the sheet goes away meanwhile.
                            val handed = withContext(Dispatchers.IO + NonCancellable) {
                                if (text != null) {
                                    PendingAttachments.offerText(session.id, text); true
                                } else {
                                    val added = uris.mapNotNull { runCatching { Attachments.import(this@ShareActivity, it, session.id) }.getOrNull() }
                                    PendingAttachments.offer(session.id, added)
                                    added.isNotEmpty()
                                }
                            }
                            if (!handed) {
                                Toast.makeText(this@ShareActivity, "Couldn't read the shared files", Toast.LENGTH_SHORT).show()
                                finish()
                                return@launch
                            }
                            // Into PocketRocket's own task (not the sharing app's), with Sessions under the chat so Back
                            // goes there; MainActivity is singleTask, so a running app is brought forward, not restarted.
                            startActivities(
                                arrayOf(
                                    Intent(this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    SessionActivity.intent(this@ShareActivity, session.id),
                                ),
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

@Composable
private fun ShareSheet(
    what: String,
    detail: String,
    sessions: List<Session>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPick: (Session) -> Unit,
) {
    val c = PrTheme.colors
    var picked by remember(sessions) { mutableStateOf(sessions.firstOrNull()) }
    ChatSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(what, style = PrType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = c.text)
            Text(detail, style = PrType.caption, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (sessions.isEmpty()) {
            Text("No sessions yet. Start one in PocketRocket, then share again.", style = PrType.body, color = c.textSoft)
            return@ChatSheet
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sessions.forEach { session ->
                val state by session.state.collectAsState()
                val on = session == picked
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(c.background)
                        .border(1.dp, if (on) c.accent else c.border, shape)
                        .clickable(role = Role.RadioButton) { picked = session }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.status(state.shownStatus)))
                    Column(Modifier.weight(1f)) {
                        Text(state.title, style = PrType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${state.statusLabel} · ${displayPath(state.folder)}", style = PrType.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (on) c.accent else c.borderStrong, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
                    }
                }
            }
        }
        val ready = picked != null && !busy
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (ready) c.accent else c.accent.copy(alpha = 0.5f))
                .clickable(enabled = ready, role = Role.Button) { picked?.let(onPick) },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (busy) "Copying…" else "Attach and open chat", style = PrType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = c.onAccent)
        }
    }
}
