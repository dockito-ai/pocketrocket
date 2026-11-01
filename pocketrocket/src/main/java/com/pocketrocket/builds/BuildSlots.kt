package com.pocketrocket.builds

import android.content.Context
import android.util.Log
import com.pocketrocket.relocate.PrefixRelocation.writeIfChanged
import com.pocketrocket.setup.Launchers
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Build slots, on by default (decision 97): builds across sessions and terminals take turns so the phone keeps enough
 * memory. The locking lives in `pocketrocket-build-slot` (setup/); this installs it and the pieces that route builds
 * through it, and reads the queue for the Background sheet.
 *
 * - `$PREFIX/bin/pocketrocket-build-slot`, from the app's assets.
 * - Wrappers for cargo, make and ninja in [WRAPPERS_RELATIVE], put first on PATH for sessions ([environment]) and
 *   terminals (a profile.d file). Only for commands that are on the phone, so `command -v cargo` stays honest.
 *   Flutter's own launcher calls the script itself.
 * - While the Android tool is installed, the Gradle hook `~/.gradle/init.d/pocketrocket-build-slots.gradle`.
 */
object BuildSlots {
    private const val TAG = "PocketRocketBuildSlots"
    const val SCRIPT = "pocketrocket-build-slot"
    const val GRADLE_HOOK = "pocketrocket-build-slots.gradle"
    const val WRAPPERS_RELATIVE = "libexec/pocketrocket/build-wrappers"
    const val PROFILE_RELATIVE = "etc/profile.d/pocketrocket-build-slots.sh"
    const val GRADLE_HOOK_RELATIVE = ".gradle/init.d/$GRADLE_HOOK"
    const val QUEUE_RELATIVE = ".pocketrocket/builds"
    const val HOOK_PLACEHOLDER = "@POCKETROCKET_BUILD_SLOT@"
    private const val ANDROID_SDK_RELATIVE = "opt/android-sdk"

    /** The commands that get a wrapper, and where else (besides $PREFIX/bin) each may live, under home. */
    val WRAPPED: Map<String, List<String>> =
        mapOf("cargo" to listOf(".cargo/bin/cargo"), "make" to emptyList<String>(), "ninja" to emptyList<String>())

    private val prefix get() = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    private val home get() = TermuxConstants.TERMUX_HOME_DIR_PATH

    /** A wrapper: hands the command to the script, which finds the real one further down PATH. */
    fun wrapperScript(prefix: String, name: String) = """
        |#!$prefix/bin/sh
        |# PocketRocket build slots (decision 97), written by the app (BuildSlots.kt); edits here are overwritten.
        |exec $prefix/bin/$SCRIPT wrap $name "${'$'}@"
        |""".trimMargin()

    /** The profile.d file: the wrappers first on PATH in terminals, once. */
    fun profileScript(wrappers: String) = """
        |# PocketRocket build slots (decision 97), written by the app (BuildSlots.kt); edits here are overwritten.
        |case ":${'$'}PATH:" in *":$wrappers:"*) ;; *) export PATH="$wrappers:${'$'}PATH" ;; esac
        |""".trimMargin()

    /** The Gradle hook from its template, pointed at the installed script. */
    fun gradleHook(template: String, prefix: String) = template.replace(HOOK_PLACEHOLDER, "$prefix/bin/$SCRIPT")

    /** Sessions' PATH: the wrappers, then Termux's (TermuxShellEnvironment's PATH is $PREFIX/bin). */
    fun environment(prefix: String, wrappersPresent: Boolean): Map<String, String> =
        if (wrappersPresent) mapOf("PATH" to "$prefix/$WRAPPERS_RELATIVE:$prefix/bin") else emptyMap()

    fun environment(): Map<String, String> = environment(prefix, File(prefix, WRAPPERS_RELATIVE).isDirectory)

    /** The running and waiting builds on the phone, from any session or terminal. Blocking: call off the main thread. */
    fun queue(): BuildQueueState = BuildQueue.read(File(home, QUEUE_RELATIVE)) { File("/proc/$it").exists() }

    /** App start: the script, the wrappers, the profile.d file and the Gradle hook as this APK has them. */
    fun refresh(context: Context) {
        Launchers.install(context, SCRIPT)
        if (!File(prefix, "bin/$SCRIPT").isFile) return
        runCatching { refreshWrappers() }.onFailure { Log.w(TAG, "could not write the build wrappers", it) }
        runCatching { refreshGradleHook(context) }.onFailure { Log.w(TAG, "could not write the Gradle hook", it) }
    }

    private fun refreshWrappers() {
        val dir = File(prefix, WRAPPERS_RELATIVE)
        for ((name, elsewhere) in WRAPPED) {
            val wrapper = File(dir, name)
            val present = File(prefix, "bin/$name").exists() || elsewhere.any { File(home, it).exists() }
            if (present) {
                writeIfChanged(wrapper, wrapperScript(prefix, name))
                wrapper.setExecutable(true, false)
            } else {
                wrapper.delete()
            }
        }
        writeIfChanged(File(prefix, PROFILE_RELATIVE), profileScript(dir.path))
    }

    private fun refreshGradleHook(context: Context) {
        val hook = File(home, GRADLE_HOOK_RELATIVE)
        if (!File(prefix, ANDROID_SDK_RELATIVE).isDirectory) {
            hook.delete()
            return
        }
        val template = context.assets.open("setup/$GRADLE_HOOK").use { it.readBytes().decodeToString() }
        writeIfChanged(hook, gradleHook(template, prefix))
    }
}
