package com.pocketrocket.appupdate

import android.content.Context
import com.pocketrocket.sessions.SessionManager
import com.pocketrocket.sessions.SessionStatus
import com.pocketrocket.updater.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/** What the PocketRocket screen and its Settings row show. */
data class AppUpdateState(
    /** The running app's versionName. */
    val installed: String = "",
    /** The newest release from the last check (this run of the app). */
    val release: AppRelease? = null,
    /** The newest version from the last check, kept across restarts for the Settings row. */
    val latest: String? = null,
    val checking: Boolean = false,
    val checkedAt: Long = 0,
    /** Why the last check failed, or null. */
    val checkError: String? = null,
    /** Where the last check looked ("GitHub as …" or the test source). */
    val source: String? = null,
    val step: Step = Step.Idle,
    /** Bytes downloaded and the APK's size while [step] is Downloading. */
    val downloaded: Long = 0,
    val total: Long = 0,
    val outcome: Outcome? = null,
    /** The step the last update failed at (Idle: before the download started). */
    val failedAt: Step? = null,
) {
    val updateAvailable: Boolean get() = installed.isNotEmpty() && AppReleases.isNewer(latest, installed)
    /** Downloading, verifying or handing over; Android's own confirm screen doesn't count (it can be dismissed). */
    val busy: Boolean get() = step != Step.Idle && step != Step.Confirming

    enum class Step { Idle, Downloading, Verifying, Installing, Confirming }
}

/**
 * Updates PocketRocket itself from a button (decisions 26 and 83), from the code repo's public releases with no
 * login. Checks GitHub only when the screen opens or Check
 * again is tapped; downloads, verifies and installs only when Update is tapped. Nothing runs on its own.
 */
object AppUpdater {
    private const val PREFS = "pocketrocket"
    private const val KEY_LATEST = "app_latest"
    private const val KEY_CHECKED_AT = "app_checked_at"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    @Volatile private var loaded = false

    /** Loads the installed version and the cached result of the last check. Never touches the network. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        _state.update {
            it.copy(installed = installed, latest = prefs.getString(KEY_LATEST, null), checkedAt = prefs.getLong(KEY_CHECKED_AT, 0))
        }
    }

    /** Asks the release source for the newest release. Only from the screen opening or Check again. */
    fun check(context: Context) {
        load(context)
        if (_state.value.checking || _state.value.busy) return
        val app = context.applicationContext
        val source = ReleaseSource.pick(app)
        _state.update { it.copy(checking = true, checkError = null, source = source.label) }
        scope.launch {
            val result = runCatching { AppReleases.latest(source.releasesJson()) }
            val now = System.currentTimeMillis()
            result.onSuccess { release ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LATEST, release?.version).putLong(KEY_CHECKED_AT, now).apply()
            }
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(checking = false, source = source.label, release = it, latest = it?.version, checkedAt = now, checkError = if (it == null) "No PocketRocket releases yet." else null) },
                    onFailure = { s.copy(checking = false, checkError = it.message ?: it.toString()) },
                )
            }
        }
    }

    /** Sessions mid-turn or waiting on a permission, which the restart pauses. */
    fun activeSessions(): Int = SessionManager.sessions.value.count {
        !it.state.value.demo && it.state.value.status in setOf(SessionStatus.Starting, SessionStatus.Working, SessionStatus.WaitingForPermission)
    }

    /**
     * Downloads the release's APK into the cache with progress, checks its SHA-256 against GitHub's digest or
     * the notes' line (refusing when neither is there or they disagree), checks it is this app at a higher
     * versionCode, and hands it to Android's installer. The caller has made sure unknown apps are allowed.
     */
    fun update(context: Context) {
        val app = context.applicationContext
        val s = _state.value
        val release = s.release ?: return
        if (s.busy) return
        val asset = release.apk ?: return fail("Release ${release.tag} has no arm64 APK.")
        val expected = AppReleases.expectedSha256(release)
        if (expected !is AppReleases.Expected.Hash) {
            return fail(
                if (expected is AppReleases.Expected.Conflict) "Not installing: GitHub's digest and the SHA-256 in the release notes disagree."
                else "Not installing: the release has no SHA-256 to check the download against.",
            )
        }
        val source = ReleaseSource.pick(app)
        _state.update { it.copy(step = AppUpdateState.Step.Downloading, downloaded = 0, total = asset.size, outcome = null, failedAt = null) }
        scope.launch {
            val dir = File(app.cacheDir, "app-update").apply { mkdirs() }
            dir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
            val apk = File(dir, asset.name)
            runCatching {
                source.download(asset, apk) { n -> _state.update { it.copy(downloaded = n) } }
                _state.update { it.copy(step = AppUpdateState.Step.Verifying) }
                val actual = sha256(apk)
                if (actual != expected.hex) {
                    apk.delete()
                    return@launch fail("Not installing: the download's SHA-256 ($actual) doesn't match ${expected.from} (${expected.hex}).")
                }
                val (pkg, code) = ApkInstaller.inspect(app, apk) ?: run {
                    apk.delete(); return@launch fail("Not installing: Android can't read the downloaded APK.")
                }
                if (pkg != app.packageName) { apk.delete(); return@launch fail("Not installing: the APK is $pkg, not ${app.packageName}.") }
                if (code <= ApkInstaller.installedVersionCode(app)) {
                    apk.delete(); return@launch fail("Not installing: the APK's versionCode $code isn't newer than this app's.")
                }
                _state.update { it.copy(step = AppUpdateState.Step.Installing) }
                ApkInstaller.install(app, apk)
            }.onFailure { e -> apk.delete(); fail(e.message ?: e.toString()) }
        }
    }

    /** From [ApkInstaller.InstallStatusReceiver]. Success ends this process, so it is rarely seen. */
    internal fun onInstallerStatus(waiting: Boolean, message: String?) {
        _state.update {
            when {
                waiting -> it.copy(step = AppUpdateState.Step.Confirming)
                message != null -> it.copy(step = AppUpdateState.Step.Idle, failedAt = AppUpdateState.Step.Installing, outcome = Outcome(message, failed = true))
                else -> it.copy(step = AppUpdateState.Step.Idle, outcome = Outcome("Installed. Reopen PocketRocket.", failed = false))
            }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(step = AppUpdateState.Step.Idle, failedAt = it.step, outcome = Outcome(message, failed = true)) }
    }

    private fun sha256(file: File): String {
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
