package com.pocketrocket.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.pocketrocket.notifications.SessionNotifier
import com.pocketrocket.sessions.Session
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.sessions.Unseen
import com.pocketrocket.ui.auth.AuthTab
import com.pocketrocket.ui.theme.PocketRocketTheme
import com.pocketrocket.ui.theme.rememberDarkTheme
import com.pocketrocket.workspaces.Workspaces

/**
 * The session view: the sessions of one workspace in a pager, opened on the one tapped in the grid or in a notification.
 * Its own activity so Home's navigation stays untouched and Back returns to the grid. The session in
 * front is resumed if it was paused, and gets no notifications while the activity is in front.
 */
class SessionActivity : ComponentActivity() {

    private val requested = mutableStateOf<String?>(null)
    private var current: String? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        requested.value = intent.getStringExtra(EXTRA_SESSION_ID)

        setContent {
            val dark = rememberDarkTheme()
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val all by SessionManager.sessions.collectAsState()
            // Only the requested session's workspace: its repos and their worktrees, not every project's.
            val scope = remember(requested.value) { all.firstOrNull { it.id == requested.value }?.let { scopeOf(it.state.value.folder) } }
            val sessions = if (scope == null) all else all.filter { scopeOf(it.state.value.folder) == scope }
            // Every session here was ended; there is nothing to show.
            LaunchedEffect(sessions.isEmpty()) { if (sessions.isEmpty()) finish() }
            if (sessions.isNotEmpty()) {
                val initial = remember { sessions.indexOfFirst { it.id == requested.value }.coerceAtLeast(0) }
                PocketRocketTheme(darkTheme = dark) {
                    SessionPagerScreen(sessions, initial, onBack = ::finish, requestedId = requested.value, onCurrent = ::onCurrent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SESSION_ID)?.let { requested.value = it }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SessionNotifier.setOnScreen(this, current)
        Unseen.setOnScreen(current)
    }

    override fun onPause() {
        resumed = false
        SessionNotifier.setOnScreen(this, null)
        Unseen.setOnScreen(null)
        super.onPause()
    }

    @Deprecated("Custom Tabs report closing through the activity result")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        AuthTab.onActivityResult(requestCode)
    }

    private fun onCurrent(session: Session) {
        current = session.id
        if (resumed) {
            SessionNotifier.setOnScreen(this, session.id)
            Unseen.setOnScreen(session.id)
        }
        // Opening a paused session resumes its conversation.
        if (session.state.value.status == SessionStatus.Paused) session.start()
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"

        /** The workspace a session's folder is in, or the folder itself when it is in none. */
        private fun scopeOf(folder: String): String = Workspaces.containing(folder)?.path ?: folder

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, SessionActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun open(context: Context, sessionId: String) {
            context.startActivity(intent(context, sessionId))
        }
    }
}
