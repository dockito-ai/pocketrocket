package com.pocketrocket.appupdate

import com.pocketrocket.updater.ClaudeVersions
import org.json.JSONArray
import org.json.JSONObject

/** One PocketRocket release on GitHub, as far as the updater needs it. */
data class AppRelease(
    /** The tag, `vX.Y.Z`. */
    val tag: String,
    /** The tag without its `v`. */
    val version: String,
    val notes: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    /** The arm64 APK, or null when the release has none. */
    val apk: ApkAsset?,
)

data class ApkAsset(
    val id: Long,
    val name: String,
    val size: Long,
    /** The asset's `browser_download_url`. */
    val url: String,
    /** The API's `digest` field (`sha256:<hex>`), lowercase hex only; null on older releases. */
    val sha256: String?,
)

/**
 * Pure parts of the app updater (decision 26): reading the GitHub releases list, picking the newest by semver
 * from its tag (prereleases included, drafts skipped), and choosing the SHA-256 the download must match.
 */
object AppReleases {
    private val TAG = Regex("""^v(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$""")
    private val NOTES_SHA = Regex("""(?im)^\s*[*_`-]*\s*SHA-?256\s*:?[*_`\s]*:?\s*`?([0-9a-f]{64})`?""")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** `v0.1.1` → `0.1.1`; null when the tag isn't `vX.Y.Z`. */
    fun versionOf(tag: String): String? = TAG.matchEntire(tag.trim())?.groupValues?.get(1)

    /** True when [candidate] is a higher semver than [installed]. */
    fun isNewer(candidate: String?, installed: String?): Boolean = ClaudeVersions.isNewer(candidate, installed)

    /** Parses `GET /repos/{o}/{r}/releases` and returns the newest release with a `vX.Y.Z` tag. */
    fun latest(json: String): AppRelease? {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { parse(list.getJSONObject(it)) }
            .maxWithOrNull { a, b -> ClaudeVersions.compare(a.version, b.version) }
    }

    fun parse(o: JSONObject): AppRelease? {
        if (o.optBoolean("draft")) return null
        val tag = o.optString("tag_name")
        val version = versionOf(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: JSONArray()
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { val n = it.optString("name"); n.endsWith("-arm64.apk") || n.endsWith("arm64-v8a.apk") }
            ?.let {
                ApkAsset(
                    id = it.getLong("id"),
                    name = it.getString("name"),
                    size = it.optLong("size"),
                    url = it.optString("browser_download_url"),
                    sha256 = it.optString("digest").takeIf { d -> d.startsWith("sha256:") }
                        ?.removePrefix("sha256:")?.lowercase()?.takeIf(HEX64::matches),
                )
            }
        return AppRelease(
            tag = tag, version = version, notes = o.optString("body"), htmlUrl = o.optString("html_url"),
            prerelease = o.optBoolean("prerelease"), apk = apk,
        )
    }

    /** The `SHA-256: <hex>` line in release notes (as scripts/release.sh writes it), lowercase; null if none. */
    fun notesSha256(notes: String): String? = NOTES_SHA.find(notes)?.groupValues?.get(1)?.lowercase()

    /**
     * What the downloaded APK must hash to: the asset's `digest` when GitHub gives one, else the notes' line.
     * When both exist and disagree, the release is inconsistent and nothing is trusted.
     */
    fun expectedSha256(release: AppRelease): Expected {
        val api = release.apk?.sha256
        val notes = notesSha256(release.notes)
        return when {
            api != null && notes != null && api != notes -> Expected.Conflict(api, notes)
            api != null -> Expected.Hash(api, "GitHub's digest")
            notes != null -> Expected.Hash(notes, "the release notes")
            else -> Expected.Missing
        }
    }

    sealed interface Expected {
        data class Hash(val hex: String, val from: String) : Expected
        data class Conflict(val api: String, val notes: String) : Expected
        data object Missing : Expected
    }
}
