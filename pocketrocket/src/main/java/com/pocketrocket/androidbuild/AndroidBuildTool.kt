package com.pocketrocket.androidbuild

import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.termux.DpkgStatus
import java.io.File

/** The fixed facts of the Android build tool (decision 45). Sizes are measured on the emulator, docs/android-tools-spike.md. */
object AndroidBuildTool {
    const val ID = "android-build"
    const val SDK_RELATIVE = "opt/android-sdk"
    const val JAVA_RELATIVE = "lib/jvm/java-17-openjdk"

    /** Under home: where the setup script puts the aapt2 setting (the plugin's own aapt2 is x86_64). */
    const val GRADLE_PROPERTIES_RELATIVE = ".gradle/gradle.properties"
    const val AAPT2_SETTING = "android.aapt2FromMavenOverride="

    /** The packages a build needs (the setup script's ANDROID_BUILD_PACKAGES, less unzip and adb), as the tile names them. */
    val PACKAGES = listOf("openjdk-17" to "Java 17", "gradle" to "Gradle", "aapt2" to "aapt2", "aapt" to "aapt", "aidl" to "aidl")

    const val DOWNLOAD_MB = 390
    const val INSTALLED_MB = 880
    const val FIRST_BUILD_DOWNLOAD_MB = 260
    const val FIRST_BUILD_DISK_MB = 800
    const val BUILD_MEMORY_GB = "3"

    val INSTALL_STEPS = listOf("Checking the device", "Installing Java, Gradle and build tools", "Downloading the Android SDK", "Setting up builds")
    val REMOVE_STEPS = listOf("Checking the device", "Removing the Android SDK", "Removing Java, Gradle and build tools")

    /**
     * The tool as it is now, whoever set it up. It is on the phone while the SDK folder is. It works when Java, Gradle
     * and the build tools are installed (dpkg), the SDK has a platform and build tools with the package.xml the plugin
     * reads and the accepted license, and Gradle has the aapt2 setting. Taking any of them away from a terminal shows.
     */
    fun presence(sdk: File, gradleProperties: File, pkgs: Map<String, DpkgStatus.Package>): ToolPresence {
        if (!sdk.isDirectory) return ToolPresence.Absent
        val platform = File(sdk, "platforms").listFiles()?.any { File(it, "android.jar").isFile && File(it, "package.xml").isFile } == true
        val aapt2 = runCatching { gradleProperties.readLines() }.getOrNull().orEmpty().any { it.trim().startsWith(AAPT2_SETTING) }
        return ToolPresence.of(
            true,
            ToolPresence.missingPackages(pkgs, PACKAGES) + listOfNotNull(
                "SDK platform".takeUnless { platform },
                "SDK build tools".takeUnless { ToolPresence.anyChildHas(File(sdk, "build-tools"), "package.xml") },
                "SDK license".takeUnless { File(sdk, "licenses/android-sdk-license").isFile },
                "aapt2 setting".takeUnless { aapt2 },
            ),
        )
    }

    /** What sessions need to build. Terminals get the same through the profile.d file the setup script writes. */
    fun environment(prefix: String): Map<String, String> = mapOf(
        "ANDROID_HOME" to "$prefix/$SDK_RELATIVE",
        "JAVA_HOME" to "$prefix/$JAVA_RELATIVE",
    )
}
