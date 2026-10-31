package com.pocketrocket.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks light or dark. */
enum class ThemeMode(val label: String) { Dark("Dark"), Light("Light"), System("System") }

/** The app theme choice, stored in the module's SharedPreferences and observable while the app runs. */
object ThemePrefs {
    private const val FILE = "pocketrocket"
    private const val KEY = "theme_mode"
    private val DEFAULT = ThemeMode.Dark

    private var flow: MutableStateFlow<ThemeMode>? = null

    fun mode(context: Context): StateFlow<ThemeMode> = state(context).asStateFlow()

    fun set(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
        state(context).value = mode
    }

    @Synchronized
    private fun state(context: Context): MutableStateFlow<ThemeMode> = flow ?: MutableStateFlow(read(context)).also { flow = it }

    private fun read(context: Context): ThemeMode =
        prefs(context).getString(KEY, null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: DEFAULT

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
