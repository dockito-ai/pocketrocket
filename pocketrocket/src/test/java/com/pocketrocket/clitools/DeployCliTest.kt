package com.pocketrocket.clitools

import com.pocketrocket.clilogin.CodeEntry
import com.pocketrocket.clilogin.LoginState
import com.pocketrocket.clilogin.LoginText
import com.pocketrocket.pkgtools.PackageTool
import com.pocketrocket.ui.tools.Tool
import com.pocketrocket.ui.tools.ToolModel
import com.pocketrocket.ui.tools.ToolStates
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.wrangler.WranglerLogin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deploy CLIs (decision 98): their tiles read npm's folders and the packages, their logins read each CLI's own
 * config and output as seen on the phone (docs/tool-sweep.md), and the script agrees with the app.
 */
class DeployCliTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val root = File.createTempFile("deploy", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr")
    private val home = File(root, "home")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(base: File, relative: String, text: String = "") = File(base, relative).apply { parentFile?.mkdirs(); writeText(text) }

    private fun shell(code: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("bash", "-c", ". \"$1\"; pkg_installed() { return 1; }; PR_LOG=/dev/null; $code", "_", script.path))
            .redirectErrorStream(true)
        pb.environment().putAll(mapOf("PREFIX" to prefix.path, "HOME" to home.path, "POCKETROCKET_SETUP_SOURCE_ONLY" to "1"))
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun anNpmInstallFromATerminalShowsOnTheTile() {
        assertNull(NpmTool.Vercel.presence(prefix))
        file(prefix, "lib/node_modules/vercel/package.json", """{"name": "vercel", "version": "65.9.0"}""")
        // npm stopped before linking the command: there, but needs Repair.
        assertEquals(NpmPresence("65.9.0", "vercel command missing"), NpmTool.Vercel.presence(prefix))
        file(prefix, "bin/vercel", "#!/usr/bin/env node\n")
        val here = NpmTool.Vercel.presence(prefix)!!
        assertNull(here.problem)
        val s = NpmToolsState(checked = true, present = mapOf(NpmTool.Vercel to here))
        val tile = ToolStates.npmTool(NpmTool.Vercel, s, LoginState(signedIn = true, account = "shankar"))
        assertEquals("Installed · shankar", ToolModel.status(Tool.Vercel.spec, tile).line)
        assertEquals(listOf("vercel" to "65.9.0"), tile.versions)
        assertEquals("Not installed · 263 MB download · about 3 min", ToolModel.status(Tool.Netlify.spec, ToolStates.npmTool(NpmTool.Netlify, s)).line)
    }

    @Test fun vercelSignedInIsItsAuthFile() {
        assertFalse(VercelLogin.signedIn(home))
        file(home, VercelLogin.AUTH_RELATIVE, """{"// Note": "x"}""")
        assertFalse(VercelLogin.signedIn(home))
        file(home, VercelLogin.AUTH_RELATIVE, """{"token": "t"}""")
        assertTrue(VercelLogin.signedIn(home))
        val url = "https://vercel.com/oauth/device?user_code=ABCD-EFGH"
        assertEquals(url, VercelLogin.url.find(LoginText.clean("  Visit $url"))?.value)
        assertEquals("shankar", VercelLogin.whoami(listOf("Vercel CLI 65.9.0", "> shankar")))
    }

    @Test fun netlifyReadsItsConfigBecauseStatusAlwaysExitsZero() {
        file(home, NetlifyLogin.CONFIG_RELATIVE, """{"cliId": "c", "telemetryDisabled": true}""")
        assertFalse(NetlifyLogin.signedIn(home))
        file(
            home, NetlifyLogin.CONFIG_RELATIVE,
            """{"userId": "u1", "users": {"u1": {"id": "u1", "email": "a@example.com", "auth": {"token": "t"}}}}""",
        )
        assertTrue(NetlifyLogin.signedIn(home))
        assertEquals("a@example.com", NetlifyLogin.account(home))
        val url = "https://app.netlify.com/authorize?response_type=ticket&ticket=abc&utm_source=cli"
        assertEquals(url, NetlifyLogin.url.find("Opening $url")?.value)
    }

    @Test fun firebaseTakesTheCodeInASecondCommand() {
        assertFalse(FirebaseLogin.signedIn(home))
        file(home, FirebaseLogin.CONFIG_RELATIVE, """{"tempLoginState": {"sessionId": "s"}}""")
        assertFalse(FirebaseLogin.signedIn(home)) // a login started, not finished
        file(home, FirebaseLogin.CONFIG_RELATIVE, """{"user": {"email": "b@example.com"}, "tokens": {"refresh_token": "r"}}""")
        assertTrue(FirebaseLogin.signedIn(home))
        assertEquals("b@example.com", FirebaseLogin.account(home))
        val code = FirebaseLogin.code as CodeEntry.Command
        assertEquals(listOf("login", "4/abc", "--non-interactive"), code.arguments("4/abc"))
        val url = "https://auth.firebase.tools/login?code_challenge=x&session=y&attest=z"
        assertEquals(url, FirebaseLogin.url.find("   $url")?.value)
    }

    @Test fun awsPassesARegionOnlyWhenNoneIsSet() {
        assertEquals(listOf("login", "--remote", "--region", AwsLogin.DEFAULT_REGION), AwsLogin.loginArguments(home))
        file(home, AwsLogin.CONFIG_RELATIVE, "[default]\nregion = eu-west-1\n")
        assertEquals(listOf("login", "--remote"), AwsLogin.loginArguments(home))
        assertEquals(CodeEntry.Stdin, AwsLogin.code)
        assertFalse(AwsLogin.signedIn(home))
        file(home, "${AwsLogin.CACHE_RELATIVE}/abc.json", "{}")
        assertTrue(AwsLogin.signedIn(home))
        for (url in listOf("https://us-east-1.signin.aws.amazon.com/v1/authorize?x=1", "https://us-east-1.signin.amazonaws.com/authorize?x=1")) {
            assertEquals(url, AwsLogin.url.find(url)?.value)
        }
        assertEquals("arn:aws:iam::123456789012:user/me", AwsLogin.whoami(listOf("arn:aws:iam::123456789012:user/me")))
    }

    @Test fun flyCleansUpACancelledLogin() {
        file(home, FlyLogin.CONFIG_RELATIVE, "access_token: \"\"\n")
        assertFalse(FlyLogin.signedIn(home))
        file(home, FlyLogin.CONFIG_RELATIVE, "access_token: fo1_abc\n")
        assertTrue(FlyLogin.signedIn(home))
        val pending = file(home, FlyLogin.PENDING_RELATIVE, "{}")
        FlyLogin.cancelled(home)
        assertFalse(pending.exists())
        assertEquals("c@example.com", FlyLogin.whoami(listOf("c@example.com")))
        assertNull(FlyLogin.whoami(listOf("Error: no access token available")))
        assertEquals("https://fly.io/app/auth/cli/0123abcd", FlyLogin.url.find("    https://fly.io/app/auth/cli/0123abcd")?.value)
    }

    @Test fun linksDoNotOutliveTheLogin() {
        assertEquals("Open this URL: (link removed)", LoginText.withoutUrl("Open this URL: https://fly.io/app/auth/cli/0123abcd"))
        assertEquals("Waiting for authorization...", LoginText.withoutUrl("\u001B[33mWaiting for authorization...\u001B[0m"))
    }

    @Test fun everyLoginToolHasOne() {
        assertEquals(WranglerLogin, Tool.Wrangler.login)
        listOf(Tool.Vercel, Tool.Netlify, Tool.Firebase, Tool.Aws, Tool.Fly).forEach { assertTrue("$it", it.login != null && it.spec.needsLogin) }
        assertNull(Tool.Go.login)
        assertTrue(UpdateComponent.of(PackageTool.Aws) != null && UpdateComponent.of(PackageTool.Fly) != null)
    }

    @Test fun theScriptAgrees() {
        if (!script.exists()) return
        val text = script.readText()
        NpmTool.entries.forEach { t ->
            assertEquals(t.npm, shell("npm_tool_package ${t.option}").second)
            assertEquals(t.bin, shell("npm_tool_bin ${t.option}").second)
            assertEquals("Installing ${t.title}", t.installSteps.last())
            assertEquals(t.title, shell("npm_tool_title ${t.option}").second)
            assertTrue(Regex("""[ |]${t.option}\|${t.removeOption}\|""").containsMatchIn(text))
        }
        assertEquals("awscli", shell("pkg_tool_packages aws").second)
        listOf("awscli-check", "awscli-update", "flyctl-check", "flyctl-update").forEach { assertTrue(it, Regex("""[ |]$it[|)]""").containsMatchIn(text)) }
    }

    @Test fun uninstallTakesTheirConfigs() {
        if (!script.exists()) return
        file(home, ".fly/config.yml", "access_token: x\n")
        file(home, ".aws/login/cache/a.json", "{}")
        assertEquals(0, shell("pkg_tool_cleanup flyctl; pkg_tool_cleanup aws").first)
        assertFalse(File(home, ".fly").exists())
        assertFalse(File(home, ".aws").exists())
        val (_, dirs) = shell("npm_tool_config vercel")
        assertTrue(dirs, dirs.contains("$home/.local/share/com.vercel.cli"))
        assertTrue(File(home, VercelLogin.AUTH_RELATIVE).path.startsWith(dirs.trim().split(" ").first()))
    }
}
