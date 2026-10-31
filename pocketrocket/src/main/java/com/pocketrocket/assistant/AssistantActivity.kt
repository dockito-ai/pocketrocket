package com.pocketrocket.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.pocketrocket.notifications.WakeNotifier
import com.pocketrocket.setup.SetupPrefs
import com.pocketrocket.ui.rocket.VoicePopup
import com.pocketrocket.ui.theme.PocketRocketTheme
import com.pocketrocket.ui.theme.rememberDarkTheme

/**
 * The popup: a capsule over the current app and the lock screen, for the wake word, Android's assist request and the earbud
 * long-press. Same [VoiceLoop], [Router] and [Conversation] as the tab; on a locked phone [Router] keeps code out. It reads
 * nothing of the assist request: no screen contents, no text from the app that was open.
 */
class AssistantActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val byWakeWord = mutableStateOf(false)
    private val asked = mutableIntStateOf(0)
    private var holding = false
    private val leave = Runnable { finish() }

    override fun onResume() {
        super.onResume()
        ScreenLaunch.resumed(javaClass)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val refusal = when {
            !SetupPrefs.isDone(this) -> "Finish setting up PocketRocket first."
            !AssistantPrefs.settings(this).value.enabled -> "Rocket is off. Turn it on in Settings › Assistant."
            else -> null
        }
        if (refusal != null) {
            Toast.makeText(this, refusal, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WakeWord.hold(HOLD, true)
        holding = true
        WakeNotifier.clearHeard(this)
        byWakeWord.value = intent.getBooleanExtra(EXTRA_WAKE_WORD, false)
        setContent {
            PocketRocketTheme(darkTheme = rememberDarkTheme()) {
                VoicePopup(byWakeWord.value, asked.intValue, ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WakeNotifier.clearHeard(this)
        byWakeWord.value = intent.getBooleanExtra(EXTRA_WAKE_WORD, false)
        asked.intValue++
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(leave)
    }

    /** It's a popup: leaving ends it, so a pressed power button can't leave an answer on the lock screen. Briefly, since waking the screen can bounce it. */
    override fun onStop() {
        super.onStop()
        main.postDelayed(leave, LEAVE_MS)
    }

    override fun onDestroy() {
        main.removeCallbacks(leave)
        if (holding) WakeWord.hold(HOLD, false)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    companion object {
        private const val HOLD = "popup"
        private const val EXTRA_WAKE_WORD = "com.pocketrocket.extra.WAKE_WORD"
        private const val LEAVE_MS = 700L

        /** Straight away where Android allows it, otherwise, or if it didn't come up ([ScreenLaunch]), through a notification. */
        fun openFromWakeWord(context: Context) {
            val intent = Intent(context, AssistantActivity::class.java).putExtra(EXTRA_WAKE_WORD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ScreenLaunch.open(context, AssistantActivity::class.java, intent, canTry = canOpenNow(context)) {
                WakeNotifier.heard(context, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
    }
}
