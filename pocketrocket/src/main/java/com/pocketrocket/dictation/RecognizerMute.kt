package com.pocketrocket.dictation

import android.content.Context
import android.media.AudioManager

/**
 * Silences the speech recognizer's own start and stop sounds while dictation restarts it through pauses (decision 81).
 * Recognizers play them on different streams (Google's on the system/sonification and music streams, some phones on
 * notifications), so the three are muted with ADJUST_MUTE, which keeps each stream's volume level. A stream that was
 * already muted is left alone and never unmuted. Ring and alarm are never touched, nor Do Not Disturb (a mute Android
 * refuses is skipped).
 *
 * Which streams this muted is written to preferences first, so if the app dies while muted the next start puts them
 * back ([restoreAfterCrash], from app start). A volume key press also unmutes a stream by itself.
 */
class RecognizerMute(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val muted = mutableSetOf<Int>()

    val active: Boolean get() = muted.isNotEmpty()

    fun mute() {
        if (audio == null || active) return
        val toMute = STREAMS.filter { runCatching { !audio.isStreamMute(it) }.getOrDefault(false) }
        // Saved before muting, so a crash in between still restores.
        remember(app, toMute.toSet())
        val ringer = audio.ringerMode
        toMute.forEach { stream ->
            if (runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }.isFailure) return@forEach
            if (audio.ringerMode != ringer) {
                // On phones where this stream follows the ringer, muting it switched the ringer: undo and leave it be.
                runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
                runCatching { audio.ringerMode = ringer }
            } else {
                muted += stream
            }
        }
        remember(app, muted)
    }

    fun restore() {
        if (audio == null) return
        unmute(audio, muted)
        muted.clear()
        remember(app, emptySet())
    }

    companion object {
        private val STREAMS = listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION)
        private const val PREFS = "dictation"
        private const val KEY = "muted_streams"

        /** App start: unmutes whatever a dictation that never finished left muted. */
        fun restoreAfterCrash(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val left = prefs.getStringSet(KEY, null)?.mapNotNull(String::toIntOrNull).orEmpty()
            if (left.isEmpty()) return
            context.getSystemService(AudioManager::class.java)?.let { unmute(it, left) }
            prefs.edit().remove(KEY).apply()
        }

        private fun unmute(audio: AudioManager, streams: Collection<Int>) = streams.forEach { stream ->
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }

        private fun remember(context: Context, streams: Set<Int>) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (streams.isEmpty()) prefs.remove(KEY) else prefs.putStringSet(KEY, streams.map(Int::toString).toSet())
            // commit, not apply: it must be on disk before the mute it guards.
            prefs.commit()
        }
    }
}
