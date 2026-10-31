package com.pocketrocket.appupdate

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Where releases come from: GitHub, or a local folder in debug testing. */
interface ReleaseSource {
    /** Shown on the screen under the versions, e.g. "github.com/dockito-ai/pocketrocket". */
    val label: String

    /** The JSON of `GET /repos/{owner}/{repo}/releases`. Blocking. */
    fun releasesJson(): String

    /** Writes [asset]'s bytes to [dest], calling [onBytes] with the bytes written so far. Blocking. */
    fun download(asset: ApkAsset, dest: File, onBytes: (Long) -> Unit)

    companion object {
        /** Where releases come from (decisions 66 and 68): the public code repo in Dockito's org. No login is needed. */
        const val REPO = "dockito-ai/pocketrocket"

        /** The old public releases repo (decision 30), read while [REPO] has no release yet. To be removed. */
        const val OLD_REPO = "ShankarKakumani/pocketrocket-releases"
        const val RELEASES_URL = "https://github.com/$REPO/releases"

        /** The debug folder when it holds a `releases.json` and the build is debuggable, else GitHub. */
        fun pick(context: Context): ReleaseSource = LocalReleaseSource.ifPresent(context) ?: GitHubReleaseSource

        /**
         * [REPO]'s releases when it answers with a usable one, else [OLD_REPO]'s. Until the first release there, the old
         * repo is what's read. Returns the repo used and its JSON.
         */
        fun firstWithRelease(fetch: (String) -> String): Pair<String, String> {
            val json = runCatching { fetch(REPO) }.getOrNull()
            if (json != null && runCatching { AppReleases.latest(json) }.getOrNull() != null) return REPO to json
            return OLD_REPO to fetch(OLD_REPO)
        }
    }
}

/** GitHub's public REST API over plain HTTPS; the APK comes from the asset's `browser_download_url`. */
object GitHubReleaseSource : ReleaseSource {
    @Volatile private var repo = ReleaseSource.REPO
    override val label get() = "github.com/$repo"

    override fun releasesJson(): String {
        val (used, json) = ReleaseSource.firstWithRelease { r ->
            get("https://api.github.com/repos/$r/releases?per_page=30", json = true, repo = r) { it.bufferedReader().readText() }
        }
        repo = used
        return json
    }

    override fun download(asset: ApkAsset, dest: File, onBytes: (Long) -> Unit) {
        dest.delete()
        get(asset.url, json = false) { input -> dest.outputStream().use { copy(input, it, onBytes) } }
    }

    private fun <T> get(url: String, json: Boolean, repo: String = this.repo, read: (InputStream) -> T): T {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("Bad release URL: $url")
        }
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PocketRocket")
        if (json) {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw IOException("Couldn't reach GitHub. Check the connection and try again.")
            }
            when {
                code == 403 || code == 429 -> throw IOException("GitHub is rate-limiting this network. Try again in a while.")
                code == 404 -> throw IOException("$repo wasn't found on GitHub.")
                code !in 200..299 -> throw IOException("GitHub answered HTTP $code.")
            }
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Debug testing only: a fake release from a folder in the app's private data dir (`<dataDir>/app-update-test/`),
 * holding a `releases.json` in GitHub's format and the APKs it names. Only a debuggable build reads it (release
 * builds signed with the release key never do), and the screen labels it. Put there with `adb shell run-as`.
 */
class LocalReleaseSource private constructor(private val dir: File) : ReleaseSource {
    override val label get() = "Test release source (${dir.name})"

    override fun releasesJson(): String = File(dir, "releases.json").readText()

    override fun download(asset: ApkAsset, dest: File, onBytes: (Long) -> Unit) {
        val src = File(dir, asset.name)
        if (!src.isFile) throw IOException("${asset.name} is not in the test release folder.")
        dest.delete()
        src.inputStream().use { input -> dest.outputStream().use { copy(input, it, onBytes) } }
    }

    companion object {
        fun ifPresent(context: Context): LocalReleaseSource? {
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val dir = File(context.applicationInfo.dataDir, "app-update-test")
            return if (debuggable && File(dir, "releases.json").isFile) LocalReleaseSource(dir) else null
        }
    }
}

private fun copy(input: InputStream, output: java.io.OutputStream, onBytes: (Long) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var reported = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        output.write(buffer, 0, n)
        total += n
        if (total - reported >= 256 * 1024) { onBytes(total); reported = total }
    }
    onBytes(total)
}
