package com.pocketrocket.assistant

import android.content.Context
import android.os.Build
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A file to keep from an archive, by its path inside the archive, with its size and SHA-256. */
class PinnedFile(val entry: String, val name: String, val bytes: Long, val sha256: String) {
    fun matches(file: File): Boolean = file.length() == bytes && WakeFiles.sha256(file) == sha256
}

/** A release asset of sherpa-onnx, pinned, and the few files wanted from it. */
class PinnedArchive(val url: String, val bytes: Long, val sha256: String, val files: List<PinnedFile>)

/**
 * What Hey Rocket needs and isn't in the APK: sherpa-onnx's native library and its English keyword model, both Apache-2.0,
 * from the project's own GitHub releases. Only arm64: the library is the static build, so one .so and no second library for
 * Android's linker to find. The "-mobile" model is smaller but crashes this version of the library on its own test audio.
 */
object WakeFiles {
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    const val ABI = "arm64-v8a"
    const val LIBRARY_FILE = "libsherpa-onnx-jni.so"
    const val ENCODER_FILE = "encoder.int8.onnx"
    const val DECODER_FILE = "decoder.int8.onnx"
    const val JOINER_FILE = "joiner.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val KEYWORDS_FILE = "keywords.txt"

    /**
     * "Hey Rocket" in the model's own word pieces, from its bpe.model, so no tokeniser ships. The two tuning values were
     * picked on synthetic voices on a computer (16 of 18 heard, "hey racket" 3 of 6, other near words 0): real voices may differ.
     */
    const val KEYWORDS = "▁HE Y ▁RO CK ET @HEY_ROCKET\n"
    const val KEYWORD_SCORE = 1.0f
    const val KEYWORD_THRESHOLD = 0.25f

    val LIBRARY = PinnedArchive(
        "$RELEASES/v2.0.1/sherpa-onnx-v2.0.1-android-static-link-onnxruntime.tar.bz2",
        34_937_656L,
        "fe1c9ceffd86770218a1d4bba6b410d6f726b2b8dda6a5488499b05250b53aea",
        listOf(PinnedFile("./jniLibs/$ABI/$LIBRARY_FILE", LIBRARY_FILE, 24_177_488L, "491b1b6e287fdfe5e0e1ffa918f5e5556299cb14bea7b7b8a0c19468f4156193")),
    )

    val MODEL = PinnedArchive(
        "$RELEASES/kws-models/$MODEL_DIR.tar.bz2",
        17_626_723L,
        "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a",
        listOf(
            PinnedFile("$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", ENCODER_FILE, 4_807_159L, "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"),
            PinnedFile("$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", DECODER_FILE, 277_985L, "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a"),
            PinnedFile("$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", JOINER_FILE, 163_380L, "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"),
            PinnedFile("$MODEL_DIR/tokens.txt", TOKENS_FILE, 5_006L, "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"),
        ),
    )

    val ARCHIVES = listOf(LIBRARY, MODEL)
    val downloadBytes: Long = ARCHIVES.sumOf { it.bytes }
    val installedBytes: Long = ARCHIVES.sumOf { a -> a.files.sumOf { it.bytes } }

    fun megabytes(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

sealed interface WakeInstall {
    data object NotInstalled : WakeInstall
    data class Downloading(val done: Long, val total: Long) : WakeInstall
    data object Unpacking : WakeInstall
    data object Installed : WakeInstall
    data class Failed(val message: String) : WakeInstall
    data object Unsupported : WakeInstall
}

/** Fetches and checks [WakeFiles], only when asked. Unpacks with the tar and bzip2 setup already installed, so the APK gains no decompressor. */
object WakeModel {
    private const val DIR = "rocket-wake"
    private const val TIMEOUT_MS = 30_000
    private const val UNPACK_MINUTES = 5L

    private val _state = MutableStateFlow<WakeInstall>(WakeInstall.NotInstalled)
    val state: StateFlow<WakeInstall> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** The download in progress, so Cancel can cut a connect or read that's waiting on the network. */
    @Volatile private var connection: HttpURLConnection? = null
    const val DAMAGED = "Its files on the phone don't match. Turn it on to download them again."

    fun dir(context: Context) = File(context.applicationContext.noBackupFilesDir, DIR)

    /** Looks at what's on disk: only that everything is there at the right size, since it runs at app start. [verified] does the hashing. */
    fun refresh(context: Context) {
        val current = _state.value
        if (current is WakeInstall.Downloading || current == WakeInstall.Unpacking) return
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        val files = WakeFiles.ARCHIVES.flatMap { it.files }
        _state.value = when {
            abi != WakeFiles.ABI -> WakeInstall.Unsupported
            files.all { File(dir(context), it.name).length() == it.bytes } && File(dir(context), WakeFiles.KEYWORDS_FILE).exists() -> WakeInstall.Installed
            // Some there and not right: say so, rather than look as if it was never downloaded.
            files.any { File(dir(context), it.name).exists() } -> WakeInstall.Failed(DAMAGED)
            else -> WakeInstall.NotInstalled
        }
    }

    /** Every file checked against its pin, right before native code loads it. A mismatch shows on the switch. */
    fun verified(context: Context): Boolean =
        WakeFiles.ARCHIVES.all { a -> a.files.all { it.matches(File(dir(context), it.name)) } }.also { if (!it) _state.value = WakeInstall.Failed(DAMAGED) }

    fun install(context: Context) {
        val app = context.applicationContext
        if (_state.value == WakeInstall.Unsupported || job?.isActive == true) return
        _state.value = WakeInstall.Downloading(0, WakeFiles.downloadBytes)
        job = scope.launch {
            val work = File(app.cacheDir, DIR)
            try {
                work.deleteRecursively()
                work.mkdirs()
                install(app, work)
                _state.value = WakeInstall.Installed
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = WakeInstall.NotInstalled
                throw e
            } catch (e: Exception) {
                _state.value = WakeInstall.Failed(e.message ?: "The download failed.")
            } finally {
                work.deleteRecursively()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        // A blocked connect or read doesn't see the cancel until it times out: cut it, off the main thread.
        connection?.let { open -> scope.launch { runCatching { open.disconnect() } } }
    }

    private suspend fun install(app: Context, work: File) {
        if (work.usableSpace < WakeFiles.downloadBytes + WakeFiles.installedBytes) {
            throw IOException("Needs ${WakeFiles.megabytes(WakeFiles.downloadBytes + WakeFiles.installedBytes)} free on the phone.")
        }
        var before = 0L
        val downloads = WakeFiles.ARCHIVES.mapIndexed { i, archive ->
            val file = File(work, "$i.tar.bz2")
            download(archive, file, before)
            before += archive.bytes
            archive to file
        }
        _state.value = WakeInstall.Unpacking
        val stage = File(work, "stage").apply { mkdirs() }
        val target = dir(app).apply { deleteRecursively(); mkdirs() }
        downloads.forEach { (archive, file) ->
            unpack(file, stage, archive.files.map { it.entry })
            file.delete()
            archive.files.forEach { pin ->
                val unpacked = File(stage, pin.entry)
                if (!pin.matches(unpacked)) throw IOException("Not installing: ${pin.name} doesn't match its SHA-256.")
                val kept = File(target, pin.name)
                if (!unpacked.renameTo(kept)) throw IOException("Couldn't save ${pin.name}.")
                kept.setReadOnly()
            }
        }
        File(target, WakeFiles.KEYWORDS_FILE).writeText(WakeFiles.KEYWORDS)
    }

    private suspend fun download(archive: PinnedArchive, file: File, before: Long) {
        val connection = URL(archive.url).openConnection() as HttpURLConnection
        this.connection = connection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        val digest = MessageDigest.getInstance("SHA-256")
        val job = kotlin.coroutines.coroutineContext
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("The download failed (HTTP ${connection.responseCode}).")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        job.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        total += n
                        _state.value = WakeInstall.Downloading(before + total, WakeFiles.downloadBytes)
                    }
                }
            }
        } finally {
            this.connection = null
            connection.disconnect()
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != archive.sha256) throw IOException("Not installing: the download doesn't match its SHA-256.")
    }

    private fun unpack(archive: File, into: File, entries: List<String>) {
        val bin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
        val process = ProcessBuilder(listOf("$bin/tar", "-xjf", archive.path, "-C", into.path) + entries).redirectErrorStream(true).apply {
            environment().apply {
                put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
                put("PATH", bin)
                put("TMPDIR", into.path)
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
            }
        }.start()
        process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(UNPACK_MINUTES, TimeUnit.MINUTES)
        if (!finished) process.destroy()
        if (!finished || process.exitValue() != 0) throw IOException("Couldn't unpack the download. PocketRocket's setup may need finishing first.")
    }
}
