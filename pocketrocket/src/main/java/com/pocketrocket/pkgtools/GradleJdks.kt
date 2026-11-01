package com.pocketrocket.pkgtools

import java.io.File
import java.util.Properties

/**
 * The JDKs on the phone against what Gradle is told about them (decision 101). Gradle toolchains only look in the usual
 * Linux folders, and their auto-download fetches glibc JDKs that don't run here, so every Java job of the setup script
 * (`java_gradle_jdks`) writes [PATHS] and [AUTO_DOWNLOAD] into `~/.gradle/gradle.properties`. This reads both sides.
 * [jdks] are the JDK folders, oldest first; [paths] and [autoDownload] the file's values, null when unset.
 */
data class GradleJdks(val jdks: List<String> = emptyList(), val paths: String? = null, val autoDownload: String? = null) {
    /** The value [PATHS] should have: the folders, comma-separated, as the script writes it. */
    val expected: String get() = jdks.joinToString(",")

    /** Nothing to tell Gradle (no JDK), or the file says exactly what the script would write. */
    val current: Boolean get() = jdks.isEmpty() || (paths == expected && autoDownload == "false")

    companion object {
        const val PATHS = "org.gradle.java.installations.paths"
        const val AUTO_DOWNLOAD = "org.gradle.java.installations.auto-download"
        private val JDK_DIR = Regex("""java-(\d+)-openjdk""")

        /** `$PREFIX/lib/jvm/java-NN-openjdk` folders with a `bin/java`, oldest first, the way `java_jdk_dirs` lists them. */
        fun dirs(prefix: File): List<String> =
            File(prefix, "lib/jvm").listFiles().orEmpty()
                .mapNotNull { d -> JDK_DIR.matchEntire(d.name)?.let { it.groupValues[1].toInt() to d } }
                .filter { (_, d) -> File(d, "bin/java").let { it.isFile && it.canExecute() } }
                .sortedBy { it.first }
                .map { it.second.path }

        /** Both sides read from the phone: [prefix]'s JDKs and [properties] (a missing file has neither key). */
        fun read(prefix: File, properties: File): GradleJdks {
            val p = Properties()
            runCatching { properties.reader().use(p::load) }
            return GradleJdks(dirs(prefix), p.getProperty(PATHS)?.trim(), p.getProperty(AUTO_DOWNLOAD)?.trim())
        }
    }
}
