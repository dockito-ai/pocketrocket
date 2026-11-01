package com.pocketrocket.fluttertool

import android.content.Context
import android.util.Log
import com.pocketrocket.termux.DpkgStatus
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * The scripts the Flutter & Dart and Rust add-ons install from setup/, kept current at every app start (decision 105),
 * so a phone that installed the add-ons with an older APK gets this APK's fixes without a reinstall:
 *
 * - the `flutter` launcher ([FlutterTool.LAUNCHER_RELATIVE]), only while it is ours ([LAUNCHER_MARK]); `dart` and `fvm`
 *   link to it by name, so they follow;
 * - `shasum` in the launcher's own bin folder ([SHASUM_RELATIVE]), while the launcher is ours;
 * - `rustup` ([RUSTUP_RELATIVE]) while dpkg has Rust's `rust` package, and only when there is none or it is ours
 *   ([RUSTUP_MARK]); a rustup from anywhere else is never touched.
 *
 * Each is written only when it differs, through a temporary file and a rename (a running copy is never half-written),
 * mode 755.
 */
object FlutterScripts {
    private const val TAG = "PocketRocketFlutterScripts"

    /** In setup/flutter's header: the launcher at $PREFIX/bin/flutter is ours. */
    const val LAUNCHER_MARK = "PocketRocket launcher for Flutter"

    /** The setup script's FLUTTER_BIN_DIR, the launcher's PR_BIN_DIR, and its `shasum`. */
    const val BIN_DIR_RELATIVE = "lib/pocketrocket-flutter-bin"
    const val SHASUM_RELATIVE = "$BIN_DIR_RELATIVE/shasum"

    /** The Rust add-on's rustup (the setup script's RUST_RUSTUP and RUST_RUSTUP_MARK). */
    const val RUSTUP_RELATIVE = "bin/rustup"
    const val RUSTUP_MARK = "rustup (PocketRocket)"

    /** Whether [file] is a file holding [mark]. */
    fun carries(file: File, mark: String): Boolean =
        file.isFile && runCatching { file.readText().contains(mark) }.getOrDefault(false)

    /**
     * Brings the scripts under [prefix] up to [asset] (a setup/ file's bytes by name), as described above. Returns the
     * names it wrote. A script [asset] can't give (a stub build) is skipped.
     */
    fun sync(prefix: File, rustInstalled: Boolean, asset: (String) -> ByteArray): List<String> {
        val wanted = mutableListOf<Pair<String, File>>()
        val launcher = File(prefix, FlutterTool.LAUNCHER_RELATIVE)
        if (carries(launcher, LAUNCHER_MARK)) {
            wanted += "flutter" to launcher
            wanted += "shasum" to File(prefix, SHASUM_RELATIVE)
        }
        val rustup = File(prefix, RUSTUP_RELATIVE)
        if (rustInstalled && (!rustup.exists() && !isLink(rustup) || carries(rustup, RUSTUP_MARK))) wanted += "rustup" to rustup
        return wanted.mapNotNull { (name, target) ->
            val bytes = runCatching { asset(name) }.getOrNull() ?: return@mapNotNull null
            name.takeIf { installIfChanged(target, bytes) }
        }
    }

    /** App start: [sync] with this APK's setup/ assets and dpkg's view of Rust. Blocking: call off the main thread. */
    fun refresh(context: Context) {
        runCatching {
            val rust = FlutterTool.rustInstalled(DpkgStatus.read(setOf(FlutterTool.RUST_PACKAGE)))
            sync(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), rust) { name -> context.assets.open("setup/$name").use { it.readBytes() } }
        }.onFailure { Log.w(TAG, "could not refresh the Flutter and Rust scripts", it) }
    }

    /** Writes [bytes] to [target] when it differs, by rename, mode 755. True when it wrote. */
    internal fun installIfChanged(target: File, bytes: ByteArray): Boolean {
        if (target.isFile && target.canExecute() && target.readBytes().contentEquals(bytes)) return false
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.setReadable(true, false)
        tmp.setExecutable(true, false)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("could not write $target")
        }
        return true
    }

    /** A dangling symlink doesn't exist() but is still someone's rustup. */
    private fun isLink(file: File) = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
