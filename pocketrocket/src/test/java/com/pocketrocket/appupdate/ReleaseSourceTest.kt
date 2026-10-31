package com.pocketrocket.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReleaseSourceTest {
    private val one = """[{"tag_name":"v1.0.5","draft":false,"prerelease":true,"html_url":"https://github.com/x/v1.0.5","body":"",
        "assets":[{"id":7,"name":"pocketrocket-v1.0.5-arm64.apk","size":100}]}]"""

    @Test fun theCodeRepoWinsWhenItHasARelease() {
        val asked = mutableListOf<String>()
        val (repo, json) = ReleaseSource.firstWithRelease { asked += it; one }
        assertEquals(ReleaseSource.REPO, repo)
        assertEquals(one, json)
        assertEquals(listOf(ReleaseSource.REPO), asked)
    }

    @Test fun aPrivateCodeRepoFallsBackToTheOldOne() {
        val (repo, _) = ReleaseSource.firstWithRelease { if (it == ReleaseSource.REPO) throw IOException("404") else one }
        assertEquals(ReleaseSource.OLD_REPO, repo)
    }

    @Test fun aPublicCodeRepoWithNoReleasesFallsBack() {
        val (repo, _) = ReleaseSource.firstWithRelease { if (it == ReleaseSource.REPO) "[]" else one }
        assertEquals(ReleaseSource.OLD_REPO, repo)
    }

    @Test fun whenBothFailTheOldRepoSaysWhy() {
        val e = runCatching { ReleaseSource.firstWithRelease { throw IOException("down: $it") } }.exceptionOrNull()
        assertTrue(e?.message == "down: ${ReleaseSource.OLD_REPO}")
    }
}
