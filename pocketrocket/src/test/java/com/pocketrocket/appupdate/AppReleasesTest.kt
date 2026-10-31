package com.pocketrocket.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleasesTest {
    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun release(tag: String, prerelease: Boolean = true, draft: Boolean = false, digest: String? = null, body: String = "") = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://github.com/x/$tag","body":${org.json.JSONObject.quote(body)},
         "assets":[{"id":7,"name":"pocketrocket-$tag-arm64.apk","size":100${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}]}
    """.trimIndent()

    @Test fun tagsAreParsedAsSemver() {
        assertEquals("0.1.1", AppReleases.versionOf("v0.1.1"))
        assertEquals("1.0.0-rc.1", AppReleases.versionOf("v1.0.0-rc.1"))
        assertNull(AppReleases.versionOf("0.1.1"))
        assertNull(AppReleases.versionOf("v0.1"))
        assertNull(AppReleases.versionOf("nightly"))
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(AppReleases.isNewer("0.1.10", "0.1.9"))
        assertTrue(AppReleases.isNewer("0.2.0", "0.1.99"))
        assertTrue(AppReleases.isNewer("1.0.0", "0.9.9"))
        assertTrue(AppReleases.isNewer("1.0.0", "1.0.0-rc.1"))
        assertFalse(AppReleases.isNewer("0.1.0", "0.1.1"))
        assertFalse(AppReleases.isNewer("0.1.1", "0.1.1"))
        assertFalse(AppReleases.isNewer(null, "0.1.1"))
    }

    @Test fun latestPicksHighestSemverIncludingPrereleasesSkippingDrafts() {
        val json = "[" + listOf(
            release("v0.1.9", prerelease = false),
            release("v0.1.10"),
            release("v0.3.0", draft = true),
            release("nightly"),
        ).joinToString(",") + "]"
        val latest = AppReleases.latest(json)!!
        assertEquals("0.1.10", latest.version)
        assertTrue(latest.prerelease)
        assertEquals("pocketrocket-v0.1.10-arm64.apk", latest.apk!!.name)
        assertNull(AppReleases.latest("[]"))
    }

    @Test fun digestFromApiWinsWhenNotesHaveNone() {
        val r = AppReleases.latest("[" + release("v0.2.0", digest = "sha256:${sha.uppercase()}") + "]")!!
        assertEquals(AppReleases.Expected.Hash(sha, "GitHub's digest"), AppReleases.expectedSha256(r))
    }

    @Test fun notesLineIsUsedWithoutDigest() {
        for (line in listOf("SHA-256: $sha", "**SHA-256:** `$sha`", "sha256 $sha", "- SHA-256: $sha")) {
            val r = AppReleases.latest("[" + release("v0.2.0", body = "First build.\n\n$line\n") + "]")!!
            assertEquals(line, AppReleases.Expected.Hash(sha, "the release notes"), AppReleases.expectedSha256(r))
        }
    }

    @Test fun disagreeingOrMissingHashesAreRefused() {
        val conflict = AppReleases.latest("[" + release("v0.2.0", digest = "sha256:$sha", body = "SHA-256: $other") + "]")!!
        assertTrue(AppReleases.expectedSha256(conflict) is AppReleases.Expected.Conflict)
        val none = AppReleases.latest("[" + release("v0.2.0", body = "No hash here") + "]")!!
        assertEquals(AppReleases.Expected.Missing, AppReleases.expectedSha256(none))
        val notSha = AppReleases.latest("[" + release("v0.2.0", digest = "md5:abc") + "]")!!
        assertEquals(AppReleases.Expected.Missing, AppReleases.expectedSha256(notSha))
    }
}
