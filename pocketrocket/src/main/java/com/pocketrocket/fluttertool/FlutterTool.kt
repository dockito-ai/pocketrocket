package com.pocketrocket.fluttertool

import org.json.JSONObject
import java.util.Locale

/** One Flutter release from Flutter's own list: its version and the day it came out ("2026-10-29"). */
data class FlutterRelease(val version: String, val date: String)

/**
 * The fixed facts of the Flutter & Rust tool (decision 75, docs/flutter-tool.md), and the pure parts: Flutter's release
 * list, version order, sizes, and which script option each action runs. Sizes are from the spike (docs/flutter-spike.md).
 */
object FlutterTool {
    /** FVM's folder under home. Versions are `versions/<version>`; the default is the link `default`. */
    const val FVM_RELATIVE = "fvm"

    /** In FVM's folder; the setup script writes it once a Flutter version is set up. Means "Flutter installed". */
    const val INSTALLED_MARKER = ".pocketrocket-installed"

    /** FVM itself, under $PREFIX: there from an install's second step. */
    const val FVM_DIR_RELATIVE = "opt/fvm"

    /** The packages the Flutter part added, and the list taken before its apt step. Under home. */
    const val PACKAGES_RELATIVE = ".pocketrocket/flutter.packages"
    const val BASELINE_RELATIVE = ".pocketrocket/flutter.baseline"

    /** What the Rust install added, written last; means "Rust installed". Under home. */
    const val RUST_MARKER_RELATIVE = ".pocketrocket/rust.packages"

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

    fun installAllSteps(version: String?) = flutterSteps(version) + "Installing Rust"
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

    /** Uninstall all: the script option that takes what is on the phone, or null when nothing is. */
    fun uninstallOption(flutter: Boolean, rust: Boolean): String? = when {
        flutter && rust -> "flutter-rust-remove"
        flutter -> "flutter-remove"
        rust -> "rust-remove"
        else -> null
    }

    fun uninstallSteps(flutter: Boolean, rust: Boolean): List<String> = when {
        flutter && rust -> FLUTTER_REMOVE_STEPS + "Removing Rust"
        flutter -> FLUTTER_REMOVE_STEPS
        else -> RUST_REMOVE_STEPS
    }

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

    /** Megabytes as the page shows them: "1.1 GB" from 1100. */
    fun sizeMb(mb: Int): String = size(mb * 1_000_000L)
}
