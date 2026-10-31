package com.pocketrocket.androidbuild

/** The fixed facts of the Android build tool (decision 45). Sizes are measured on the emulator, docs/android-tools-spike.md. */
object AndroidBuildTool {
    /** Under the SDK folder; the setup script writes it last, so it means the whole install finished. */
    const val INSTALLED_MARKER = ".pocketrocket-installed"
    const val SDK_RELATIVE = "opt/android-sdk"
    const val JAVA_RELATIVE = "lib/jvm/java-17-openjdk"

    const val DOWNLOAD_MB = 390
    const val INSTALLED_MB = 880
    const val FIRST_BUILD_DOWNLOAD_MB = 260
    const val FIRST_BUILD_DISK_MB = 800
    const val BUILD_MEMORY_GB = "3"

    val INSTALL_STEPS = listOf("Checking the device", "Installing Java, Gradle and build tools", "Downloading the Android SDK", "Setting up builds")
    val REMOVE_STEPS = listOf("Checking the device", "Removing the Android SDK", "Removing Java, Gradle and build tools")

    /** What sessions need to build. Terminals get the same through the profile.d file the setup script writes. */
    fun environment(prefix: String): Map<String, String> = mapOf(
        "ANDROID_HOME" to "$prefix/$SDK_RELATIVE",
        "JAVA_HOME" to "$prefix/$JAVA_RELATIVE",
    )
}
