package com.pocketrocket.androidbuild

import android.content.Context
import com.pocketrocket.setup.ScriptTool
import com.pocketrocket.setup.ScriptToolState
import com.pocketrocket.setup.ToolPresence
import com.pocketrocket.termux.DpkgStatus
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The optional Android build tool (decision 45). Settings → Tools installs and removes it through the setup script;
 * nothing is bundled and first-run setup never touches it. It also writes the Gradle hook that keeps native projects
 * from downloading Google's NDK (decision 96).
 */
object AndroidBuild {
    private val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val sdk = File("$prefix/${AndroidBuildTool.SDK_RELATIVE}")
    private val gradleProperties = File(TermuxConstants.TERMUX_HOME_DIR_PATH, AndroidBuildTool.GRADLE_PROPERTIES_RELATIVE)
    // Removing Android takes the NDK with it.
    private val tool = ScriptTool(AndroidBuildTool.ID, ::presence, afterRun = { AndroidNdk.refresh() })

    val state: StateFlow<ScriptToolState> = tool.state

    /** What is on the phone now (the SDK folder, dpkg, Gradle's setting), whoever put it there. Blocking: small reads. */
    fun presence(): ToolPresence = AndroidBuildTool.presence(sdk, gradleProperties, DpkgStatus.read(AndroidBuildTool.PACKAGES.map { it.first }.toSet()))

    fun installed(): Boolean = sdk.isDirectory

    /** Reads [presence] off the main thread. */
    fun refresh() {
        tool.refresh()
    }

    /** For a new session's process; its Bash tool inherits it. Empty until the tool is installed. */
    fun environment(): Map<String, String> = if (installed()) AndroidBuildTool.environment(prefix) else emptyMap()

    /** Only called after the user accepted the SDK license on the tool page. */
    fun install(context: Context) {
        tool.install(context, mapOf(SdkLicense.HASH_ENV to SdkLicense.ACCEPTED_HASH))
    }

    fun remove(context: Context) {
        tool.remove(context)
    }
}
