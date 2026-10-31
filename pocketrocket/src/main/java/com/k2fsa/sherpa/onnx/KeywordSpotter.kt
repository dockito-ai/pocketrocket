// Copyright (c)  2024  Xiaomi Corporation
// Licensed under the Apache License, Version 2.0, http://www.apache.org/licenses/LICENSE-2.0
//
// From k2-fsa/sherpa-onnx v2.0.1, sherpa-onnx/kotlin-api: KeywordSpotter.kt, OnlineStream.kt, FeatureConfig.kt, QnnConfig.kt
// and OnlineRecognizer.kt's model config, trimmed to keyword spotting. PocketRocket's changes: the library is loaded by
// com.pocketrocket.assistant.WakeModel (a checked download, not jniLibs), so the loadLibrary blocks are gone.
//
// The native library finds these by name, so the package, class and field names must match the pinned release exactly.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f,
)

data class OnlineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OnlineParaformerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

data class OnlineZipformer2CtcModelConfig(
    var model: String = "",
)

data class OnlineNeMoCtcModelConfig(
    var model: String = "",
)

data class OnlineToneCtcModelConfig(
    var model: String = "",
)

data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    var zipformer2Ctc: OnlineZipformer2CtcModelConfig = OnlineZipformer2CtcModelConfig(),
    var neMoCtc: OnlineNeMoCtcModelConfig = OnlineNeMoCtcModelConfig(),
    var toneCtc: OnlineToneCtcModelConfig = OnlineToneCtcModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var maxActivePaths: Int = 4,
    var keywordsFile: String = "keywords.txt",
    var keywordsScore: Float = 1.5f,
    var keywordsThreshold: Float = 0.25f,
    var numTrailingBlanks: Int = 2,
)

class KeywordSpotterResult(
    val keyword: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
)

class KeywordSpotter(val config: KeywordSpotterConfig) {
    private var ptr: Long = newFromFile(config)

    init {
        require(ptr != 0L) { "Invalid KeywordSpotterConfig: failed to create native KeywordSpotter" }
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun createStream(keywords: String = ""): OnlineStream = OnlineStream(createStream(ptr, keywords))
    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)
    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)
    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)
    fun getResult(stream: OnlineStream): KeywordSpotterResult = getResult(ptr, stream.ptr)

    private external fun delete(ptr: Long)
    private external fun newFromFile(config: KeywordSpotterConfig): Long
    private external fun createStream(ptr: Long, keywords: String): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): KeywordSpotterResult
}

class OnlineStream(var ptr: Long = 0) {
    init {
        require(ptr != 0L) { "Failed to create native OnlineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) = acceptWaveform(ptr, samples, sampleRate)

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)
}
