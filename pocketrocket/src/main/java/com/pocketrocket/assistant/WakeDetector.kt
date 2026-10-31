package com.pocketrocket.assistant

import android.annotation.SuppressLint
import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.IOException

/** Spots the wake word in 16 kHz mono audio, on the phone. One thread at a time; the audio is looked at and dropped. */
interface WakeDetector {
    /** Feeds [samples], -1 to 1; true when the word was just heard. */
    fun heard(samples: FloatArray): Boolean
    fun release()
}

/** [WakeDetector] on sherpa-onnx keyword spotting, from the files [WakeModel] fetched and checked. */
class SherpaWakeDetector private constructor(dir: File) : WakeDetector {
    private val spotter = KeywordSpotter(
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, WakeFiles.ENCODER_FILE).path,
                    decoder = File(dir, WakeFiles.DECODER_FILE).path,
                    joiner = File(dir, WakeFiles.JOINER_FILE).path,
                ),
                tokens = File(dir, WakeFiles.TOKENS_FILE).path,
                numThreads = 1,
                modelType = "zipformer2",
            ),
            keywordsFile = File(dir, WakeFiles.KEYWORDS_FILE).path,
            keywordsScore = WakeFiles.KEYWORD_SCORE,
            keywordsThreshold = WakeFiles.KEYWORD_THRESHOLD,
            numTrailingBlanks = 1,
        ),
    )
    /**
     * Two streams, the second [OFFSET_FRAMES] behind. Measured with sherpa-onnx 2.0.1 on this model on a computer: one stream
     * misses the word almost every time it lands on certain spots that come round about every 1.6 seconds of listening, and a
     * stream on the phone runs for hours. A second stream's blind spots fall elsewhere.
     */
    private var streams = listOf(spotter.createStream())
    private var frames = 0

    override fun heard(samples: FloatArray): Boolean {
        if (++frames == OFFSET_FRAMES + 1) streams = streams + spotter.createStream()
        var found = false
        for (stream in streams) {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotEmpty()) {
                    spotter.reset(stream)
                    found = true
                }
            }
        }
        if (found) restart()
        return found
    }

    /** Both streams start again, staggered as before, so one word isn't heard twice. */
    private fun restart() {
        streams.forEach { it.release() }
        streams = listOf(spotter.createStream())
        frames = 0
    }

    override fun release() {
        streams.forEach { it.release() }
        spotter.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 0.8 s of 100 ms frames: half the blind spots' spacing. */
        const val OFFSET_FRAMES = 8
        private var loaded = false

        /**
         * Checks the files against their pins, loads the library the first time, and starts the model. Loading a .so from the
         * app's own files works because the app targets SDK 28, as Termux itself needs to. Throws if anything is wrong.
         */
        @SuppressLint("UnsafeDynamicallyLoadedCode")
        @Synchronized
        fun open(context: Context): SherpaWakeDetector {
            if (!WakeModel.verified(context)) throw IOException("Hey Rocket's files don't match their SHA-256.")
            val dir = WakeModel.dir(context)
            if (!loaded) {
                System.load(File(dir, WakeFiles.LIBRARY_FILE).path)
                loaded = true
            }
            return SherpaWakeDetector(dir)
        }
    }
}
