package com.pocketrocket.wrangler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WranglerToolTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val url = "https://dash.cloudflare.com/oauth2/auth?response_type=code&client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A8976%2Foauth%2Fcallback&state=s3cret&code_challenge=c1"

    /** Runs a function from the real setup script. */
    private fun shell(code: String, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(
            listOf("bash", "-c", "PREFIX=/tmp/pr-none POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; shift; $code", "_", script.path) + args,
        ).redirectErrorStream(true).apply { environment()["PREFIX"] = "/tmp/pr-none" }.start() // stays set after sourcing
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun theAppAndTheScriptAgree() {
        if (!script.exists()) return
        assertEquals(WranglerTool.VERSION, shell("printf %s \"\$WRANGLER_VERSION\"").second)
        assertEquals("/tmp/pr-none/${WranglerTool.PACKAGE_RELATIVE}", shell("printf %s \"\$WRANGLER_DIR\"").second)
        assertEquals(WranglerTool.WORKERD_VERSION, shell("printf %s \"\$WORKERD_VERSION\"").second)
        assertTrue(shell("printf %s \"\$WORKERD_URL\"").second.contains("/dockito-ai/pocketrocket/releases/download/workerd-v${WranglerTool.WORKERD_VERSION}/"))
    }

    @Test fun theSignInAddressIsFoundInWranglersLine() {
        assertEquals(url, WranglerTool.oauthUrl("Visit this link to authenticate: $url"))
        assertEquals(url, WranglerTool.oauthUrl("\u001B[32mVisit this link to authenticate: $url\u001B[0m"))
        assertNull(WranglerTool.oauthUrl("Attempting to login via OAuth..."))
        assertNull(WranglerTool.oauthUrl("See https://developers.cloudflare.com/workers/"))
    }

    @Test fun theSignInAddressDoesNotOutliveTheSignIn() {
        val kept = WranglerTool.withoutUrl("Visit this link to authenticate: $url")
        assertFalse(kept.contains("s3cret"))
        assertFalse(kept.contains("dash.cloudflare.com"))
        assertEquals("Visit this link to authenticate: (link removed)", kept)
        assertEquals("Successfully logged in.", WranglerTool.withoutUrl("Successfully logged in."))
    }

    @Test fun aLoginIsTheTokenKeyInWranglersConfig() {
        assertTrue(WranglerTool.hasLogin("oauth_token = \"x\"\nexpiration_time = \"y\"\n"))
        assertFalse(WranglerTool.hasLogin(""))
        assertFalse(WranglerTool.hasLogin(null))
        assertFalse(WranglerTool.hasLogin("# oauth_token was here\n"))
    }

    @Test fun whoamiGivesTheEmail() {
        assertEquals("a@example.com", WranglerTool.email(listOf("Getting User settings...", "You are logged in with an OAuth Token, associated with the email a@example.com.")))
        assertNull(WranglerTool.email(listOf("You are not authenticated. Please run `wrangler login`.")))
    }

    @Test fun workerdLoaderIsPointedAtTheAndroidWrapperOnce() {
        if (!script.exists()) return
        val dir = File.createTempFile("workerd", "").apply { delete(); mkdirs() }
        val main = File(dir, "main.js").apply { writeText("var x = 1;\nfunction generateBinPath() {\n  throw new Error('Unsupported platform');\n}\n") }
        assertEquals(0, shell("wrangler_patch_workerd \"$1\"", main.path).first)
        assertEquals(0, shell("wrangler_patch_workerd \"$1\"", main.path).first) // Retry adds nothing twice
        val patched = main.readText()
        assertEquals(1, Regex("workerd-android").findAll(patched).count())
        assertTrue(patched.contains("{ binPath:")) // the loader destructures { binPath }
        val wrapper = File(dir, "workerd-android")
        assertTrue(wrapper.canExecute())
        val w = wrapper.readText()
        assertTrue(w.contains("unset LD_PRELOAD"))
        assertTrue(w.contains("/tmp/pr-none/glibc/lib/ld-linux-aarch64.so.1"))
        assertTrue(w.contains("\${SSL_CERT_FILE:-/tmp/pr-none/etc/tls/cert.pem}")) // left for the shell, not filled in
        assertTrue(w.contains("${dir.canonicalPath}/workerd-glibc\" \"\$@\""))
        assertFalse(File(dir, "main.js.orig").exists())
        dir.deleteRecursively()
    }

    @Test fun anUnknownWorkerdIsAFailureNotASilentPass() {
        if (!script.exists()) return
        val dir = File.createTempFile("workerd", "").apply { delete(); mkdirs() }
        val main = File(dir, "main.js").apply { writeText("var somethingElse = 1;\n") }
        assertEquals(1, shell("wrangler_patch_workerd \"$1\"", main.path).first)
        dir.deleteRecursively()
    }

    @Test fun theScriptTakesTheNewModes() {
        if (!script.exists()) return
        val body = script.readText()
        assertTrue(body.contains("--ignore-scripts"))
        assertTrue(body.contains("wrangler@\${WRANGLER_VERSION}"))
        assertTrue(body.contains("|wrangler|wrangler-remove|"))
    }
}
