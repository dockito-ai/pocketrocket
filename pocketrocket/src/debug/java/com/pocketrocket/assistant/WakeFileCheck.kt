package com.pocketrocket.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Debug builds only: runs Hey Rocket's real detector, library and gain on a WAV file instead of the mic, so the emulator can
 * prove detection without a voice. `adb shell am broadcast -n app.pocket/com.pocketrocket.assistant.WakeFileCheck -a
 * app.pocket.wake.CHECK --es wav <16 kHz mono 16-bit WAV, or a folder of them>`; `-a app.pocket.wake.INSTALL` downloads the
 * files first. Results go to logcat under PocketRocketWakeCheck.
 */
class WakeFileCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            "app.pocket.wake.INSTALL" -> { WakeModel.refresh(app); WakeModel.install(app); Log.i(TAG, "install started") }
            "app.pocket.wake.CHECK" -> {
                val path = intent.getStringExtra("wav") ?: return
                // Not goAsync: a folder of clips outlasts a broadcast's time limit. The app is open while this runs.
                thread(name = "wake-check") {
                    try {
                        check(app, File(path))
                    } catch (t: Throwable) {
                        Log.e(TAG, "check failed: $t")
                    }
                }
            }
        }
    }

    private fun check(app: Context, target: File) {
        val files = if (target.isDirectory) target.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name } else listOf(target)
        var heardPos = 0
        var heardNeg = 0
        for (file in files) {
            // A fresh detector for each file, as after each start of the mic.
            val detector = SherpaWakeDetector.open(app)
            val gain = WakeGain()
            val samples = pcm(file)
            val frame = SherpaWakeDetector.SAMPLE_RATE / 10
            var at = -1
            var i = 0
            while (i < samples.size && at < 0) {
                val chunk = FloatArray(minOf(frame, samples.size - i)) { samples[i + it] }
                gain.apply(chunk)
                if (detector.heard(chunk)) at = i
                i += frame
            }
            detector.release()
            val heard = at >= 0
            if (heard && file.name.startsWith("pos")) heardPos++
            if (heard && file.name.startsWith("neg")) heardNeg++
            Log.i(TAG, "${file.name}: ${if (heard) "HEARD at %.1f s".format(at / 16000f) else "not heard"}")
        }
        Log.i(TAG, "done: ${files.size} files, heard ${files.count { it.name.startsWith("pos") }.let { "$heardPos of $it positives" }}, $heardNeg negatives")
    }

    /** 16 kHz mono 16-bit PCM from a WAV's data chunk, as -1 to 1. */
    private fun pcm(file: File): FloatArray {
        val bytes = RandomAccessFile(file, "r").use { r -> ByteArray(r.length().toInt()).also { r.readFully(it) } }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                return FloatArray(n) { buf.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    private companion object {
        const val TAG = "PocketRocketWakeCheck"
    }
}
