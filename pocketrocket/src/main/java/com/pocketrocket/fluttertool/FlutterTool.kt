package com.pocketrocket.fluttertool

import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.termux.DpkgStatus
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One Flutter release from Flutter's own list: its version and the day it came out ("2026-10-29"). */
data class FlutterRelease(val version: String, val date: String)

/**
 * The fixed facts of the Flutter & Dart and Rust tools (decisions 75 and 87, docs/flutter-tool.md), and the pure parts: Flutter's release
 * list, version order, sizes, and which script option each action runs. Sizes are from the spike (docs/flutter-spike.md).
 */
object FlutterTool {
    /** FVM's folder under home. Versions are `versions/<version>`; the default is the link `default`. */
    const val FVM_RELATIVE = "fvm"

    /** FVM itself, under $PREFIX: there from an install's second step. Its snapshot is what `fvm` runs. */
    const val FVM_DIR_RELATIVE = "opt/fvm"
    const val FVM_SNAPSHOT_RELATIVE = "$FVM_DIR_RELATIVE/src/fvm.snapshot"

    /** The `flutter` launcher (setup/flutter) under $PREFIX; `dart` and `fvm` link to it. */
    const val LAUNCHER_RELATIVE = "bin/flutter"

    /** In a version's folder: Flutter's script, and the Dart SDK it runs on. */
    const val VERSION_FLUTTER = "bin/flutter"
    const val VERSION_DART = "bin/cache/dart-sdk/bin/dart"

    /**
     * Debug APK builds (decision 100): the exec helper the install builds with clang, under $PREFIX (the setup script's
     * FLUTTER_EXEC_DIR, the launcher's PR_EXEC_DIR), and the clang it needs (the C and C++ add-on).
     */
    const val EXEC_RELATIVE = "lib/pocketrocket-flutter-exec"
    const val EXEC_GLIBC_RELATIVE = "$EXEC_RELATIVE/aarch64/2.so"
    const val CLANG_RELATIVE = "bin/clang"

    /** The package list taken before the install's apt step, under home; only a run that stopped there leaves it. */
    const val BASELINE_RELATIVE = ".pocketrocket/flutter.baseline"

    /**
     * Flutter & Dart as it is now, whoever set it up: the versions FVM holds ([home]/fvm/versions, from the tile, `fvm
     * install` or a terminal), FVM and the launcher ([prefix]). On the phone while any of them is, or a run stopped in its
     * apt step (so Uninstall cleans up). Works when FVM and the launcher are there and at least one version has its Dart
     * SDK. `rm -rf ~/fvm` from a terminal leaves FVM, so it shows "Flutter SDK missing" with Repair and Uninstall.
     * With clang on the phone but no exec helper (Flutter installed before C and C++, or before decision 100) it shows
     * "APK builds missing", and Repair builds the helper. Without clang APK builds aren't expected, so nothing is missing.
     */
    fun presence(prefix: File, home: File): ToolPresence {
        val versions = File(home, "$FVM_RELATIVE/versions").listFiles()?.filter { File(it, VERSION_FLUTTER).isFile }.orEmpty()
        val fvm = File(prefix, FVM_SNAPSHOT_RELATIVE).isFile
        val launcher = File(prefix, LAUNCHER_RELATIVE).isFile
        val present = versions.isNotEmpty() || fvm || launcher || File(prefix, FVM_DIR_RELATIVE).exists() || File(home, BASELINE_RELATIVE).exists()
        return ToolPresence.of(
            present,
            listOfNotNull(
                "FVM".takeUnless { fvm },
                "flutter command".takeUnless { launcher },
                when {
                    versions.isEmpty() -> "Flutter SDK"
                    versions.none { File(it, VERSION_DART).isFile } -> "Dart SDK"
                    else -> null
                },
                "APK builds".takeIf { File(prefix, CLANG_RELATIVE).exists() && !File(prefix, EXEC_GLIBC_RELATIVE).exists() },
            ),
        )
    }

    /**
     * Rust is Termux's `rust` package (decision 87). Whether it is installed, its version and its size come from dpkg,
     * so a `pkg install rust` or `pkg uninstall rust` from a terminal or a Claude session shows on the tile too (as
     * decision 92 has for Python and Bun). The script's `~/.pocketrocket/rust.packages`, written by the tile's
     * install, only lists what that install brought in, for Uninstall; it no longer means "installed".
     */
    const val RUST_PACKAGE = "rust"

    /** Rust's own packages: the compiler and every target's standard library (`rust-std-*`, the wasm target too). */
    fun isRustPackage(name: String): Boolean = name == RUST_PACKAGE || name.startsWith("rust-std-")

    fun rustInstalled(pkgs: Map<String, DpkgStatus.Package>): Boolean = RUST_PACKAGE in pkgs

    /** The installed version as shown ("1.99.1"), or null. */
    fun rustVersion(pkgs: Map<String, DpkgStatus.Package>): String? = pkgs[RUST_PACKAGE]?.let { DpkgStatus.display(it.version) }

    /** What Rust takes on the phone, its targets included (Uninstall removes them all), in MB; null when it isn't there. */
    fun rustSizeMb(pkgs: Map<String, DpkgStatus.Package>): Int? =
        if (!rustInstalled(pkgs)) null else DpkgStatus.sizeMb(pkgs.filterKeys(::isRustPackage).values)

    /** Flutter's release list. The same file the setup script reads for the newest stable and each release's commit. */
    const val RELEASES_URL = "https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json"

    /** The setup script reads the version from here (the app's way to pass it). */
    const val VERSION_ENV = "POCKETROCKET_FLUTTER_VERSION"

    /** Set to 1 to make the version being added the default. */
    const val DEFAULT_ENV = "POCKETROCKET_FLUTTER_DEFAULT"

    /** A version to remove once the one being added is the default, if it was the default (Updates). */
    const val REPLACE_ENV = "POCKETROCKET_FLUTTER_REPLACE"

    /** The FVM release the setup script pins and checks. */
    const val FVM_VERSION = "4.3.2"

    /** Clone and Dart SDK; engine tools come on top. */
    const val FLUTTER_DOWNLOAD_MB = 450
    const val FLUTTER_INSTALLED_MB = 1100
    const val RUST_DOWNLOAD_MB = 128
    const val RUST_INSTALLED_MB = 590
    const val PUB_CACHE_NOTE = "A project's packages go to ~/.pub-cache: about 1.1 GB for a large app."

    /** How many recent stable releases Add version offers. */
    const val PICKER_SIZE = 12

    private val STABLE = Regex("""^\d+\.\d+\.\d+$""")

    // ---- script options and their steps

    fun flutterSteps(version: String?) = listOf(
        "Checking the device",
        "Installing FVM and helpers",
        "Downloading Flutter${version?.let { " $it" }.orEmpty()}",
        "Downloading the Dart SDK",
        "Setting up Flutter${version?.let { " $it" }.orEmpty()}",
    )
    val RUST_STEPS = listOf("Checking the device", "Installing Rust")
    val RUST_REMOVE_STEPS = listOf("Checking the device", "Removing Rust")
    val FLUTTER_REMOVE_STEPS = listOf("Checking the device", "Removing Flutter, Dart and FVM")
    fun removeVersionSteps(version: String) = listOf("Checking the device", "Removing Flutter $version")
    fun defaultSteps(version: String) = listOf("Checking the device", "Making Flutter $version the default")

    // ---- Flutter's release list

    /** The newest stable releases, newest first, each version once. Empty when the text isn't the list. */
    fun stableReleases(json: String, limit: Int = PICKER_SIZE): List<FlutterRelease> = runCatching {
        val releases = JSONObject(json).getJSONArray("releases")
        val seen = LinkedHashMap<String, FlutterRelease>()
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            val version = r.optString("version")
            if (r.optString("channel") != "stable" || !isStable(version) || version in seen) continue
            seen[version] = FlutterRelease(version, r.optString("release_date").take(10))
        }
        seen.values.sortedWith { a, b -> compareVersions(b.version, a.version) }.take(limit)
    }.getOrDefault(emptyList())

    /** The current stable release, as Flutter's list names it. */
    fun latestStable(json: String): String? = runCatching {
        val root = JSONObject(json)
        val hash = root.getJSONObject("current_release").getString("stable")
        val releases = root.getJSONArray("releases")
        (0 until releases.length()).map { releases.getJSONObject(it) }
            .firstOrNull { it.optString("hash") == hash && it.optString("channel") == "stable" }
            ?.optString("version")?.takeIf(::isStable)
    }.getOrNull()

    fun isStable(version: String): Boolean = STABLE.matches(version)

    /**
     * Version order: numbers part by part; a pre-release ("3.50.0-0.3.pre") comes before its release. Folder names
     * that aren't versions (FVM's "stable", a commit) sort after every version, by name.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa == null || pb == null) return if (pa == null && pb == null) a.compareTo(b) else if (pa == null) 1 else -1
        for (i in 0 until 3) if (pa.first[i] != pb.first[i]) return pa.first[i].compareTo(pb.first[i])
        val ra = pa.second
        val rb = pb.second
        return when {
            ra == rb -> 0
            ra == null -> 1
            rb == null -> -1
            else -> ra.compareTo(rb)
        }
    }

    private fun parse(v: String): Pair<List<Int>, String?>? {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$""").find(v) ?: return null
        return m.groupValues.subList(1, 4).map { it.toInt() } to m.groups[4]?.value
    }

    /** Whether Updates offers [latest] for the default: only a stable newer than a stable default. A project's pin is never touched. */
    fun newerStable(default: String?, latest: String?): Boolean =
        default != null && latest != null && isStable(default) && isStable(latest) && compareVersions(latest, default) > 0

    /** The installed versions from FVM's version folders (those with bin/flutter), newest first. */
    fun installedVersions(folders: List<String>): List<String> {
        val (versions, others) = folders.filter { it.isNotEmpty() && !it.startsWith(".") }.partition { parse(it) != null }
        return versions.sortedWith { a, b -> compareVersions(b, a) } + others.sorted()
    }

    /**
     * Flutter's part of the tile's size in MB: nothing when no version is there (a failed install's leftovers count as
     * installed), the measured total, else the estimate per version.
     */
    fun flutterSizeMb(installed: Boolean, versions: Int, measuredBytes: Long?): Int = when {
        !installed || versions == 0 -> 0
        measuredBytes != null -> (measuredBytes / 1_000_000).toInt()
        else -> FLUTTER_INSTALLED_MB * versions
    }

    /** A size as the page shows it: "1.1 GB", "590 MB", "Under 1 MB". Decimal units, as Android's storage screens. */
    fun size(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
            bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
            else -> "Under 1 MB"
        }
    }

    /**
     * Flutter apps among [folders] (workspaces) that keep Rust in a `rust/` crate, as flutter_rust_bridge does: a
     * pubspec.yaml beside rust/Cargo.toml. Two file checks each, so cheap enough for the page to run as it opens.
     */
    fun rustBridgeProjects(folders: List<File>): List<File> =
        folders.filter { File(it, "pubspec.yaml").isFile && File(it, "rust/Cargo.toml").isFile }

    /** Megabytes as the page shows them: "1.1 GB" from 1100. */
    fun sizeMb(mb: Int): String = size(mb * 1_000_000L)
}
