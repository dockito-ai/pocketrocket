package com.pocketrocket

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import com.pocketrocket.auth.ClaudeLogin
import com.pocketrocket.auth.LoginState
import kotlinx.coroutines.launch
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.SetupPrefs
import com.pocketrocket.setup.SetupRunner
import com.pocketrocket.termux.Termux
import com.pocketrocket.ui.AppScreen
import com.pocketrocket.ui.PocketRocketApp
import com.pocketrocket.ui.auth.AuthTab
import com.pocketrocket.ui.theme.PocketRocketTheme
import com.pocketrocket.ui.theme.rememberDarkTheme
import com.pocketrocket.updater.ClaudeUpdater

/** The launcher entry. Hosts PocketRocket's Compose screens; the terminal is TermuxActivity, opened from Home. */
class MainActivity : ComponentActivity() {

    /** Bumped when an intent asks for the Sessions tab ([EXTRA_SESSIONS]). */
    private val showSessions = mutableIntStateOf(0)

    /** The clone a notification tap asked for ([EXTRA_CLONE]), with a counter so the same one can be asked twice. */
    private val showClone = androidx.compose.runtime.mutableStateOf<Pair<Int, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) onIntent(intent)

        val start = when {
            SetupPrefs.isDone(this) -> AppScreen.Home
            SetupRunner.state.value.phase != SetupPhase.Idle -> AppScreen.Setup
            else -> AppScreen.Welcome
        }
        // At most once a day, look for a newer Claude Code (R9). Nothing is installed until Update is tapped.
        if (start == AppScreen.Home) ClaudeUpdater.onAppOpen(this)
        // Close the sign-in sheet as soon as Claude Code reports the login finished. Collected for the
        // activity's whole life, not only while it is in front, because the sheet is in front then.
        lifecycleScope.launch {
            ClaudeLogin.state.collect {
                if (it is LoginState.Verifying || it is LoginState.SignedIn || it is LoginState.Failed) AuthTab.close(this@MainActivity)
            }
        }
        setContent {
            val dark = rememberDarkTheme()
            LaunchedEffect(dark) { applySystemBars(dark) }
            PocketRocketTheme(darkTheme = dark) {
                PocketRocketApp(start, onOpenTerminal = { Termux.openTerminal(this) }, showSessions = showSessions.intValue, showClone = showClone.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onIntent(intent)
    }

    private fun onIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SESSIONS, false) == true) showSessions.intValue++
        intent?.getIntExtra(EXTRA_CLONE, -1)?.takeIf { it > 0 }?.let { showClone.value = it to (showClone.value?.second ?: 0) + 1 }
    }

    companion object {
        /** Open on Home's Sessions tab, leaving whatever screen was on top. */
        const val EXTRA_SESSIONS = "com.pocketrocket.extra.SESSIONS"

        /** Open on a clone (its job id, decision 42): the repo's workspace when it landed, else the clone screen. */
        const val EXTRA_CLONE = "com.pocketrocket.extra.CLONE"
    }

    @Deprecated("Custom Tabs report closing through the activity result")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        AuthTab.onActivityResult(requestCode)
    }

    /** Status and navigation bar icons follow the app theme, not the phone's. */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
