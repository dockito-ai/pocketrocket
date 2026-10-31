package com.pocketrocket.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** An installed voice for the picker. */
data class VoiceOption(val id: String, val label: String, val note: String, val language: String, val quality: Int)

/** The phone's installed Android voices, across its speech engines. Rocket reads commands aloud, so never one that needs the network. */
object Voices {
    private const val SEPARATOR = "::"
    private const val ENGINE_TIMEOUT_MS = 5_000L
    private const val NOT_INSTALLED = "notInstalled"

    fun id(engine: String, name: String) = "$engine$SEPARATOR$name"

    fun engineOf(id: String?): String? = id?.substringBefore(SEPARATOR, "")?.ifEmpty { null }

    fun nameOf(id: String?): String? = id?.substringAfter(SEPARATOR)

    fun isOnDevice(needsNetwork: Boolean, features: Set<String>): Boolean = !needsNetwork && NOT_INSTALLED !in features

    /**
     * The voice used when none is picked: the phone's language, its own country's accent first, then the highest quality the
     * engine offers. The engine's default voice is often a low-quality one, which is what sounded odd.
     */
    fun <T> best(voices: List<T>, phone: Locale, locale: (T) -> Locale, quality: (T) -> Int): T? =
        voices.filter { locale(it).language == phone.language }.ifEmpty { voices }
            .maxWithOrNull(compareBy<T>({ locale(it).country == phone.country }, { quality(it) }))

    /** The phone's language first, then best quality, then by name. */
    fun sorted(options: List<VoiceOption>, language: String): List<VoiceOption> =
        options.sortedWith(compareBy({ it.language != language }, { it.language }, { -it.quality }, { it.label }))

    /** Asks each engine in turn; one that doesn't answer is left out. */
    suspend fun installed(context: Context): List<VoiceOption> {
        val app = context.applicationContext
        val engines = connect(app, null)?.let { tts ->
            try { tts.engines.map { it.name to it.label } } finally { tts.shutdown() }
        }.orEmpty()
        val several = engines.size > 1
        val options = engines.flatMap { (engine, label) ->
            val tts = connect(app, engine) ?: return@flatMap emptyList()
            try {
                runCatching { tts.voices }.getOrNull().orEmpty()
                    .filter { isOnDevice(it.isNetworkConnectionRequired, it.features.orEmpty()) }
                    .map { voice ->
                        VoiceOption(
                            id = id(engine, voice.name),
                            label = voice.locale.getDisplayName(Locale.getDefault()),
                            note = listOfNotNull(voice.name, qualityWord(voice.quality), label.takeIf { several }).joinToString(" · "),
                            language = voice.locale.language,
                            quality = voice.quality,
                        )
                    }
            } finally {
                tts.shutdown()
            }
        }
        return sorted(options, Locale.getDefault().language)
    }

    private suspend fun connect(context: Context, engine: String?): TextToSpeech? {
        val started = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(context, { started.complete(it == TextToSpeech.SUCCESS) }, engine)
        if (withTimeoutOrNull(ENGINE_TIMEOUT_MS) { started.await() } == true) return tts
        tts.shutdown()
        return null
    }

    private fun qualityWord(quality: Int): String? = when {
        quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "very high quality"
        quality >= android.speech.tts.Voice.QUALITY_HIGH -> "high quality"
        else -> null
    }
}
