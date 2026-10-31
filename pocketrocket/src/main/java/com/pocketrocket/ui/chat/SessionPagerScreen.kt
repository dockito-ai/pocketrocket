package com.pocketrocket.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketrocket.git.GitRepo
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.termux.Termux
import com.pocketrocket.ui.accounts.TextPrompt
import com.pocketrocket.ui.git.GitScreens
import com.pocketrocket.ui.sessions.NewSessionScreen
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * All sessions side by side (Session.dc.html): the top bar with the folder, Git and terminal; the switcher chips;
 * the chat of the session in front. Swipe left and right between sessions, or tap a chip. [requestedId] jumps to a
 * session (a notification tapped while the pager is open); [onCurrent] hears which session is in front.
 */
@Composable
fun SessionPagerScreen(
    sessions: List<Session>,
    initialIndex: Int,
    onBack: () -> Unit,
    requestedId: String? = null,
    onCurrent: (Session) -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = initialIndex) { sessions.size }
    val scope = rememberCoroutineScope()
    val current = sessions.getOrNull(pager.currentPage)
    val context = LocalContext.current
    var newSession by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var ending by remember { mutableStateOf<Session?>(null) }

    LaunchedEffect(requestedId) {
        val index = sessions.indexOfFirst { it.id == requestedId }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    LaunchedEffect(current?.id) { current?.let(onCurrent) }

    Column(Modifier.fillMaxSize().background(PrTheme.colors.background)) {
        current?.let { TopBar(it, sessions, onBack) }
        SessionSwitcher(
            sessions,
            current = pager.currentPage,
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            onNew = { newSession = true },
            onRename = { renaming = it },
            onEnd = { ending = it },
            modifier = Modifier.padding(bottom = 10.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = PrTheme.colors.border)
        HorizontalPager(
            state = pager,
            key = { sessions[it].id },
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
        ) { page ->
            ChatPage(sessions[page], active = page == pager.settledPage)
        }
    }

    renaming?.let { session ->
        TextPrompt("Rename session", "Name", session.state.value.title, "Save", onDismiss = { renaming = null }) {
            session.rename(it)
            renaming = null
        }
    }
    // Ending the last one closes the activity (SessionActivity); otherwise the pager settles on a neighbour.
    ending?.let { EndSessionDialog(it) { ending = null } }

    if (newSession) {
        // The same full-screen New session as Home, starting in this session's folder.
        Dialog(onDismissRequest = { newSession = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            NewSessionScreen(
                folder = current?.state?.value?.folder.orEmpty(),
                resume = false,
                onClose = { newSession = false },
                onStarted = { newSession = false },
            )
        }
    }
}

/** Back, the folder and how many sessions need you, the Git button with the change count, the terminal. */
@Composable
private fun TopBar(session: Session, sessions: List<Session>, onBack: () -> Unit) {
    val c = PrTheme.colors
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val needYou = sessions.count { it.state.collectAsState().value.status == SessionStatus.WaitingForPermission }
    val subtitle = "${sessions.size} ${if (sessions.size == 1) "session" else "sessions"}" +
        if (needYou > 0) " · $needYou needs you" else ""

    // Changed files in the folder's repo; null when it is not a repo. Read on open (even mid-turn, or the
    // button stays hidden until the turn ends) and again after each turn. A read that fails once the
    // folder is known to be a repo (git busy mid-turn) keeps the last count rather than hiding the button.
    var changed by remember(state.folder) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.folder, state.status) {
        if (state.status == SessionStatus.Working && changed != null) return@LaunchedEffect
        val count = withContext(Dispatchers.IO) {
            runCatching { GitRepo(context, File(state.folder)).status().changes.map { it.path }.distinct().size }.getOrNull()
        }
        if (count != null || changed == null) changed = count
    }

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClickLabel = "Back to sessions", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ChatIcons.Back, contentDescription = "Back", tint = c.text, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(folderName(state.folder), style = PrType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = PrType.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        changed?.let { count ->
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.border, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { GitScreens.open(context, state.folder) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(ChatIcons.Branch, contentDescription = null, tint = c.textSoft, modifier = Modifier.size(16.dp))
                Text(if (count == 0) "Git" else "$count changed", style = PrType.caption, color = c.textSoft)
            }
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClickLabel = "Open in terminal") { Termux.openTerminal(context) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ChatIcons.Terminal, contentDescription = "Open in terminal", tint = c.textSoft, modifier = Modifier.size(20.dp))
        }
    }
}
