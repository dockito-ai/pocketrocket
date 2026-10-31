package com.pocketrocket.ui.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The browser sheet for Claude's sign-in page: a partial Chrome Custom Tab (a tall bottom sheet over our
 * screen) in the app's colours, with no share, bookmark or download extras. Browsers without partial tabs
 * show it full screen. Not a WebView: Google sign-in refuses embedded WebViews, and the tab shares the
 * browser's claude.ai session and passkeys.
 */
object AuthTab {
    private const val REQUEST = 0x7a51

    /** The sheet covers this much of the screen; the strip above it shows our "signing in" header. */
    const val SHEET_FRACTION = 0.82f

    private val _isOpen = MutableStateFlow(false)
    /** True while the sign-in sheet is showing. */
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private var session: CustomTabsSession? = null
    private var connection: CustomTabsServiceConnection? = null

    private val callback = object : CustomTabsCallback() {
        override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
            if (navigationEvent == TAB_HIDDEN) _isOpen.value = false
        }
    }

    /** Binds to the default browser's Custom Tabs service ahead of time, so the sheet opens fast and as a sheet. */
    fun warmUp(context: Context) {
        if (connection != null) return
        val pkg = CustomTabsClient.getPackageName(context, null) ?: return
        val conn = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0)
                session = client.newSession(callback)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                session = null
                connection = null
            }
        }
        if (runCatching { CustomTabsClient.bindCustomTabsService(context.applicationContext, pkg, conn) }.getOrDefault(false)) {
            connection = conn
        }
    }

    /** Opens [url] in the sheet. [toolbarColor] is the app's surface colour. Returns false when no browser can. */
    @Suppress("DEPRECATION")
    fun open(activity: Activity, url: String, toolbarColor: Int, dark: Boolean): Boolean {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColor)
            .setNavigationBarColor(toolbarColor)
            .setNavigationBarDividerColor(toolbarColor)
            .build()
        val height = (activity.resources.displayMetrics.heightPixels * SHEET_FRACTION).toInt()
        val builder = (session?.let { CustomTabsIntent.Builder(it) } ?: CustomTabsIntent.Builder())
            .setDefaultColorSchemeParams(colors)
            .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setBookmarksButtonEnabled(false)
            .setDownloadButtonEnabled(false)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .setInitialActivityHeightPx(height, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
            .setToolbarCornerRadiusDp(16)
        val intent = builder.build().intent.setData(Uri.parse(url))
        return try {
            // startActivityForResult keeps the tab in our task (needed for the sheet) and lets us close it.
            activity.startActivityForResult(intent, REQUEST)
            _isOpen.value = true
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Closes the sheet once sign-in is done, and brings our screen back to the front. */
    fun close(activity: Activity) {
        if (!_isOpen.value) return
        _isOpen.value = false
        activity.finishActivity(REQUEST)
        runCatching {
            activity.startActivity(
                Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /** Call from the host activity's onActivityResult: the user closed the sheet. */
    fun onActivityResult(requestCode: Int) {
        if (requestCode == REQUEST) _isOpen.value = false
    }
}
