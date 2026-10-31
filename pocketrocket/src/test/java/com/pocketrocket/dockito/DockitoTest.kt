package com.pocketrocket.dockito

import com.pocketrocket.profiles.DockitoAccountEntry
import com.pocketrocket.profiles.DockitoAccounts
import com.pocketrocket.profiles.DockitoLogins
import com.pocketrocket.updater.ClaudeVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DockitoTest {
    private val sha = "ce32f628381635cb07213e44015d184df8bbab179e5a8bf27f10994e2ba0351f"

    private fun formula(version: String = "0.0.1-beta.27", linuxArmUrl: String = DockitoFormula.assetUrl(version), linuxArmSha: String = sha) = """
        # Written by apps/cli/scripts/release-brew.mjs in dockito-ai/dockito. Don't edit by hand.
        class Dockito < Formula
          desc "Publish and share docs and sheets with Dockito from the terminal"
          homepage "https://dockito.ai"
          version "$version"
          license :cannot_represent

          on_macos do
            on_arm do
              url "https://github.com/dockito-ai/homebrew-tap/releases/download/v$version/dockito-darwin-arm64.tar.gz"
              sha256 "5181ff55a4175f7c5881dd4a57f5ebb050a535f5a7fcb3856e3967e66a36d281"
            end
            on_intel do
              url "https://github.com/dockito-ai/homebrew-tap/releases/download/v$version/dockito-darwin-x64.tar.gz"
              sha256 "08ea9d6110ef7f74cdf846e688a4c7d8c0ec90428b886bfa0cbc878978cc08d8"
            end
          end

          on_linux do
            on_intel do
              url "https://github.com/dockito-ai/homebrew-tap/releases/download/v$version/dockito-linux-x64.tar.gz"
              sha256 "38100e61eb9ebeb14bf0232f5e79545806b84ac6ff5845be2fc388eb6c46566a"
            end
            on_arm do
              url "$linuxArmUrl"
              sha256 "$linuxArmSha"
            end
          end

          def install
            bin.install "dockito"
          end
        end
    """.trimIndent()

    @Test fun parsesTheLinuxArmRelease() {
        val r = DockitoFormula.parse(formula())!!
        assertEquals("0.0.1-beta.27", r.version)
        assertEquals("https://github.com/dockito-ai/homebrew-tap/releases/download/v0.0.1-beta.27/dockito-linux-arm64.tar.gz", r.url)
        assertEquals(sha, r.sha256)
    }

    @Test fun refusesAUrlOffTheTapOrForAnotherVersion() {
        assertNull(DockitoFormula.parse(formula(linuxArmUrl = "https://evil.example/dockito-linux-arm64.tar.gz")))
        assertNull(DockitoFormula.parse(formula(linuxArmUrl = DockitoFormula.assetUrl("0.0.1-beta.25"))))
    }

    @Test fun refusesAMalformedChecksum() {
        assertNull(DockitoFormula.parse(formula(linuxArmSha = "abc")))
        assertNull(DockitoFormula.parse("class Dockito < Formula\nend"))
    }

    @Test fun prereleasesCompareByNumber() {
        assertTrue(ClaudeVersions.isNewer("0.0.1-beta.26", "0.0.1-beta.25"))
        assertTrue(ClaudeVersions.isNewer("0.0.1-beta.25", "0.0.1-beta.9"))
        assertTrue(ClaudeVersions.isNewer("0.0.1", "0.0.1-beta.30"))
        assertTrue(!ClaudeVersions.isNewer("0.0.1-beta.25", "0.0.1-beta.25"))
        assertTrue(ClaudeVersions.isNewer("2.1.313", "2.1.312"))
    }

    @Test fun whoamiJsonIsSignedIn() {
        val out = """
            {
              "user": { "id": "u1", "name": "Shankar Kakumani", "email": "s@example.com", "username": "shankarkakumani" },
              "token": { "id": "tok_1", "name": "Dockito CLI", "source": "cli", "expiresAt": "2027-01-27T17:40:27.378Z" },
              "workspaces": [ { "id": "ws_1", "slug": "shankarkakumani", "name": "Shankar's Space", "kind": "personal", "plan": "free", "role": "owner", "level": "everything" } ]
            }
        """.trimIndent()
        val s = DockitoStatus.from(0, out, hasLogin = true) as DockitoStatus.SignedIn
        assertEquals("Shankar Kakumani", s.account.name)
        assertEquals("shankarkakumani", s.account.username)
        assertEquals("s@example.com", s.account.email)
        assertEquals("2027-01-27T17:40:27.378Z", s.account.expiresAt)
        assertEquals(listOf(DockitoWorkspace("shankarkakumani", "Shankar's Space", "everything")), s.account.workspaces)
    }

    @Test fun whoamiTextIsParsedToo() {
        val out = "Shankar Kakumani shankarkakumani · s@example.com\nDockito CLI · ends after 90 days without use\n"
        val s = DockitoStatus.from(0, out, hasLogin = true) as DockitoStatus.SignedIn
        assertEquals("Shankar Kakumani", s.account.name)
        assertEquals("shankarkakumani", s.account.username)
    }

    @Test fun noTokenIsSignedOutAndATokenThatFailsIsUnknown() {
        val err = """{ "error": "network", "message": "Can't reach dockito.ai" }"""
        assertEquals(DockitoStatus.SignedOut, DockitoStatus.from(1, err, hasLogin = false))
        assertEquals(DockitoStatus.Unknown("Can't reach dockito.ai"), DockitoStatus.from(1, err, hasLogin = true))
    }

    @Test fun loginOutputGivesLinkAndCode() {
        assertEquals("https://dockito.ai/cli/login?c=abc", DockitoLoginOutput.link("Open https://dockito.ai/cli/login?c=abc."))
        assertEquals("WXYZ-1234", DockitoLoginOutput.code("Check the code: WXYZ-1234"))
        assertEquals("ABCD-EFGH", DockitoLoginOutput.code("\u001B[1mABCD-EFGH\u001B[0m"))
        assertNull(DockitoLoginOutput.code("Waiting for you to confirm in the browser…"))
    }

    @Test fun refusesACliWithoutAccounts() {
        assertNull(DockitoFormula.parse(formula(version = "0.0.1-beta.26")))
        assertTrue(DockitoCli.supports("0.0.1-beta.27"))
        assertTrue(DockitoCli.supports("0.0.1-beta.30"))
        assertTrue(DockitoCli.supports("0.0.1"))
        assertTrue(!DockitoCli.supports("0.0.1-beta.9"))
        assertTrue(!DockitoCli.supports(null))
    }

    // `dockito accounts --json` from the real 0.0.1-beta.27 CLI: `[]` signed out, else one entry per login.
    @Test fun accountsJsonGivesLoginsAndTheOneInUse() {
        assertEquals(DockitoLogins(emptyList(), null), DockitoAccounts.parse("[]\n"))
        val out = """
            [
              { "email": "a@example.com", "name": "Ann", "username": "ann", "active": false },
              { "email": "b@work.example", "name": "", "username": "bee", "active": true }
            ]
        """.trimIndent()
        val logins = DockitoAccounts.parse(out)!!
        assertEquals(listOf(DockitoAccountEntry("a@example.com", "Ann", "ann"), DockitoAccountEntry("b@work.example", "bee", "bee")), logins.accounts)
        assertEquals("b@work.example", logins.active)
        assertEquals("a@example.com", logins.accounts[0].id)
        assertNull(DockitoAccounts.parse("Not logged in. Run `dockito login`."))
    }

    @Test fun envUsesOneStoreAndPicksTheAccountByEmail() {
        val shared = DockitoAccounts.env()
        assertEquals(setOf("DOCKITO_CONFIG_DIR"), shared.keys)
        assertTrue(shared.getValue("DOCKITO_CONFIG_DIR").endsWith("/.pocketrocket/accounts/dockito"))
        val one = DockitoAccounts.env(DockitoAccountEntry("a@example.com", "Ann", "ann"))
        assertEquals("a@example.com", one["DOCKITO_ACCOUNT"])
        assertEquals(shared["DOCKITO_CONFIG_DIR"], one["DOCKITO_CONFIG_DIR"])
    }

    // The setup script's own version gate, run from the real script.
    @Test fun setupScriptRequiresBeta27() {
        val script = File("../setup/pocketrocket-setup.sh").takeIf { it.exists() } ?: return
        fun atLeast(v: String, min: String = "0.0.1-beta.27"): Boolean {
            val p = ProcessBuilder(
                "bash", "-c", "POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; version_at_least \"$2\" \"$3\"", "_", script.path, v, min,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            return p.waitFor() == 0
        }
        assertTrue(atLeast("0.0.1-beta.27"))
        assertTrue(atLeast("0.0.1-beta.28"))
        assertTrue(atLeast("0.0.1"))
        assertTrue(atLeast("0.0.2-alpha.1"))
        assertTrue(!atLeast("0.0.1-beta.26"))
        assertTrue(!atLeast("0.0.1-beta.9"))
        assertTrue(!atLeast("0.0.1-alpha.30"))
        assertTrue(!atLeast("0.0.0"))
    }

    // The setup script's own check, run from the real script: a download whose SHA-256 is not the
    // formula's is refused.
    @Test fun setupScriptRefusesAChecksumMismatch() {
        val script = File("../setup/pocketrocket-setup.sh").takeIf { it.exists() } ?: return
        val file = File.createTempFile("dockito", ".tar.gz").apply { writeText("not the release"); deleteOnExit() }
        val right = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        fun verify(expected: String): Int {
            val p = ProcessBuilder(
                "bash", "-c", "POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; verify_sha256 \"$2\" \"$3\"", "_",
                script.path, file.path, expected,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            return p.waitFor()
        }
        assertEquals(0, verify(right))
        assertNotEquals(0, verify(sha))
        assertNotEquals(0, verify("not-a-sha"))
    }
}
