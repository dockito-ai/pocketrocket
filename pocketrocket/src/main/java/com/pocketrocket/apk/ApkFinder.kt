package com.pocketrocket.apk

import java.io.File

/** An APK a command wrote: where it is, its size and when it was written. [key] tells one build of it from the next. */
data class BuiltApk(val path: String, val size: Long, val modified: Long) {
    val name: String get() = File(path).name
    val key: String get() = "$path@$modified"
}

/**
 * Finds the APKs one finished command wrote (decision 99): files ending in `.apk` whose modified time falls within the
 * command's run, [startedAt] to [endedAt] with [SLACK_MS] either side. Candidates are the `.apk` paths the command or
 * its output names, and, when the command looks like a build, the usual output folders under the session folder and
 * any folder the command `cd`s into. The time window is what keeps `ls`, `find` and old APKs out. Disk work: call it
 * off the main thread.
 */
object ApkFinder {
    const val SLACK_MS = 5_000L
    /** More than this from one command is a listing, not a build. */
    const val MAX_PER_RUN = 4

    fun find(folder: File, home: File, command: String, output: String, startedAt: Long, endedAt: Long): List<BuiltApk> {
        val bases = listOf(folder) + cdTargets(command).map { resolve(it, folder, home) }
        val named = mentioned(command + "\n" + output).flatMap { path ->
            if (path.startsWith("/") || path.startsWith("~/")) listOf(resolve(path, folder, home)) else bases.map { File(it, path) }
        }
        val scanned = if (looksLikeBuild(command)) bases.flatMap(::outputApks) else emptyList()
        return (named + scanned)
            .asSequence()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .distinct()
            .map { BuiltApk(it.path, it.length(), it.lastModified()) }
            .filter { it.modified in (startedAt - SLACK_MS)..(endedAt + SLACK_MS) }
            .sortedBy { it.path }
            .take(MAX_PER_RUN)
            .toList()
    }

    private val apkPath = Regex("""[^\s'"`<>|;,()\[\]{}]+\.apk\b""", RegexOption.IGNORE_CASE)

    /** Paths ending in `.apk` named in [text], in order, without repeats. */
    fun mentioned(text: String): List<String> =
        apkPath.findAll(text).map { it.value.removePrefix("file://") }.filter { it.length > 4 }.distinct().toList()

    private val cd = Regex("""(?:^|[;&|(]|\s)cd\s+("[^"]+"|'[^']+'|[^\s;&|)]+)""")

    /** Folders the command changes into (`cd android && ./gradlew …`), quotes removed. */
    fun cdTargets(command: String): List<String> =
        cd.findAll(command).map { it.groupValues[1].trim('"', '\'') }.filter { it.isNotEmpty() && it != "-" }.distinct().toList()

    private val build = Regex("""gradle|flutter|assemble|bundle|build|make|apk""", RegexOption.IGNORE_CASE)

    /** True when the command may build an app: only then are the output folders searched. */
    fun looksLikeBuild(command: String): Boolean = build.containsMatchIn(command)

    private fun resolve(path: String, folder: File, home: File): File = when {
        path.startsWith("/") -> File(path)
        path == "~" -> home
        path.startsWith("~/") -> File(home, path.removePrefix("~/"))
        else -> File(folder, path)
    }

    /**
     * The `.apk` files under the build output folders of [root]: `build/outputs` and `build/app/outputs` (Flutter) in
     * [root], in a module (`app/build`) or a module inside a folder (`android/app/build`). Hidden folders and
     * node_modules are skipped.
     */
    fun outputApks(root: File): List<File> {
        val builds = buildList {
            add(File(root, "build"))
            subfolders(root).forEach { module ->
                add(File(module, "build"))
                subfolders(module).forEach { add(File(it, "build")) }
            }
        }
        return builds.filter { it.isDirectory }
            .flatMap { listOf(File(it, "outputs"), File(it, "app/outputs")) }
            .filter { it.isDirectory }
            .flatMap { outputs -> outputs.walkTopDown().maxDepth(OUTPUT_DEPTH).filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }.toList() }
    }

    private fun subfolders(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" && it.name != "build" }.orEmpty()

    /** `outputs/apk/<flavor>/<type>/app.apk` is as deep as Gradle goes. */
    private const val OUTPUT_DEPTH = 5
}
